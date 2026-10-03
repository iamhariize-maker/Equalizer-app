package app.svan

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.os.Process
import android.util.Log
import app.svan.model.AudioSettings
import app.svan.model.DitherChoice
import app.svan.model.EqState
import app.svan.model.QualityMode

/**
 * Engine B: capture other apps' playback, run the full native chain (parametric
 * EQ, Audiophile oversampling, dither), and play the result.
 *
 * The source apps' own output is muted by SessionRouter/SourceMuter (session
 * DynamicsProcessing at -200 dB; capture taps audio before session effects).
 * Apps that opt out of capture (reported: Spotify, Chrome, SoundCloud) are
 * detected by CaptureCompat and left unmuted on Engine A instead.
 */
class CaptureService : Service() {

    @Volatile private var running = false
    private var worker: Thread? = null
    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Must be in the foreground (type mediaProjection) *before* getMediaProjection on Android 14+.
        startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        SvanRepository.init(this)
        if (intent?.hasExtra(EXTRA_QUALITY) == true) {
            // Explicit quality (scripted tests) overrides the saved setting.
            val q = QualityMode.entries[intent.getIntExtra(EXTRA_QUALITY, 0).coerceIn(0, QualityMode.entries.size - 1)]
            SvanRepository.updateSettings { it.copy(quality = q) }
        }
        if (data == null || running) return START_NOT_STICKY

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(resultCode, data) ?: return START_NOT_STICKY
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { running = false }
        }, null)
        projection = mp

        SessionRouter.init(this)
        startSessionWatch()
        SessionRouter.onCaptureStarted(mp)

        running = true
        isRunning = true
        worker = Thread({ audioLoop(mp) }, "eq-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission") // checked in MainActivity before starting
    private fun audioLoop(mp: MediaProjection) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val rate = EqController.SAMPLE_RATE
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .excludeUid(Process.myUid()) // never re-capture our own output (feedback loop)
            .build()
        val minIn = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT)

        val record = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minIn * 2)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
        } catch (e: Exception) {
            // SecurityException without RECORD_AUDIO; UnsupportedOperationException on some builds.
            EqController.log("capture: cannot open playback capture: $e")
            stopSelf()
            return
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        SvanRepository.init(this)
        var settings = SvanRepository.settings.value
        var engine = buildEngine(settings)
        current = engine
        // EQ edits arrive on their own thread; setBands is thread-safe, and the
        // lock keeps an engine from being freed mid-update.
        val eqWatcher = Thread({
            var last: EqState? = null
            while (running) {
                val eq = SvanRepository.eq.value
                if (eq !== last) {
                    synchronized(engineLock) { current?.let { applyEq(it, eq) } }
                    last = eq
                }
                Thread.sleep(15)
            }
        }, "svan-eq-watch").apply { start() }
        EqController.log("capture: started, quality=${settings.quality}, DSP latency=${engine.latencyFrames} frames")

        var levelPeak = 0f
        var levelFrames = 0L
        val frames = 256
        val buf = FloatArray(frames * 2)
        record.startRecording()
        track.play()
        try {
            while (running) {
                val now = SvanRepository.settings.value
                if (now != settings) {
                    // Quality / dither / gain settings changed: rebuild between blocks.
                    synchronized(engineLock) {
                        engine.close()
                        settings = now
                        engine = buildEngine(now)
                        current = engine
                    }
                    EqController.log("capture: engine rebuilt, quality=${now.quality}")
                }
                val n = record.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) continue
                for (i in 0 until n) levelPeak = maxOf(levelPeak, kotlin.math.abs(buf[i]))
                levelFrames += n / 2
                if (levelFrames >= rate * 2) {
                    EqController.log("capture level: peak=%.4f over %d frames".format(levelPeak, levelFrames))
                    levelPeak = 0f
                    levelFrames = 0
                }
                engine.process(buf, buf, n / 2)
                track.write(buf, 0, n, AudioTrack.WRITE_BLOCKING)
            }
        } finally {
            record.stop(); record.release()
            track.stop(); track.release()
            eqWatcher.join(200)
            synchronized(engineLock) {
                current = null
                engine.close()
            }
            isRunning = false
            EqController.log("capture: stopped")
        }
    }

    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null
    private val syncExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** With DUMP: re-sync sessions whenever any app starts/stops playing. */
    private fun startSessionWatch() {
        if (!PlaybackSessions.hasDumpPermission(this)) {
            EqController.log("session watch: DUMP not granted, relying on OPEN/CLOSE broadcasts only")
            return
        }
        val am = getSystemService(AudioManager::class.java)
        // Playback callbacks arrive in bursts; one sync at a time, latest wins.
        val syncPending = java.util.concurrent.atomic.AtomicBoolean(false)
        val sync = {
            if (syncPending.compareAndSet(false, true)) {
                syncExecutor.execute {
                    syncPending.set(false)
                    val sessions = PlaybackSessions.query(this)
                    if (sessions == null) EqController.log("session dump failed: ${PlaybackSessions.lastError}")
                    else SessionRouter.sync(sessions)
                }
            }
        }
        val cb = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) = sync()
        }
        am.registerAudioPlaybackCallback(cb, null)
        playbackCallback = cb
        sync()
    }

    private val engineLock = Any()
    @Volatile private var current: NativeEngine? = null

    private fun buildEngine(s: AudioSettings): NativeEngine =
        NativeEngine(
            EqController.SAMPLE_RATE, 2,
            oversample = s.quality.oversample,
            stopbandDb = s.quality.stopbandDb,
            // Dither "off" means no word-length reduction at all: float goes straight out.
            ditherBits = if (s.dither == DitherChoice.OFF) 0 else s.outputBits,
            ditherMode = s.dither.nativeMode,
            autoHeadroom = s.autoHeadroom,
            gainProtection = s.gainProtection,
        ).also { applyEq(it, SvanRepository.eq.value) }

    private fun applyEq(engine: NativeEngine, eq: EqState) {
        engine.setBands(eq.effectiveBands().map { it.toNative() })
        engine.setPreampDb(eq.effectivePreampDb())
        engine.setBassCharacter(eq.bassCharacter, eq.bass.crossoverHz)
    }

    override fun onDestroy() {
        playbackCallback?.let { getSystemService(AudioManager::class.java).unregisterAudioPlaybackCallback(it) }
        SessionRouter.onCaptureStopped()
        running = false
        isRunning = false
        worker?.join(500)
        projection?.stop()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Capture engine", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Svan capture engine running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }

    companion object {
        private const val TAG = "CaptureService"
        private const val CHANNEL = "capture"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "app.svan.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_QUALITY = "quality"

        @Volatile var isRunning = false
            private set

        /** [quality] null = use the saved Audiophile setting. */
        fun start(context: Context, resultCode: Int, data: Intent, quality: QualityMode? = null) {
            Log.i(TAG, "starting capture, quality=${quality ?: "saved"}")
            context.startForegroundService(
                Intent(context, CaptureService::class.java)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_RESULT_DATA, data)
                    .apply { if (quality != null) putExtra(EXTRA_QUALITY, quality.ordinal) },
            )
        }
    }
}
