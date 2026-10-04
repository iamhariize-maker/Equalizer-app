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
    @Volatile private var activeRecord: AudioRecord? = null
    private var systemPackages: Set<String> = emptySet()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        SvanRepository.init(this)
        if (SvanRepository.settings.value.engineMode == app.svan.model.EngineMode.SYSTEM_ONLY) {
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
        if (running) return START_NOT_STICKY
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(resultCode, data) ?: return START_NOT_STICKY
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { running = false; stopSelf() }
        }, null)
        projection = mp

        SessionRouter.init(this)
        systemPackages = SessionRouter.appPreferences().systemOnlyPackages()

        running = true
        isRunning = true
        worker = Thread({ audioLoop(mp) }, "eq-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission") // checked before starting
    private fun audioLoop(mp: MediaProjection) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val rate = EqController.SAMPLE_RATE
        val frames = 256
        var record: AudioRecord? = null
        var track: AudioTrack? = null
        var engine: NativeEngine? = null
        var eqWatcher: Thread? = null
        try {
            val minOut = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            require(minOut > 0) { "unsupported output format ($minOut)" }
            val output = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(maxOf(minOut, frames * 2 * 4))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            track = output
            check(output.state == AudioTrack.STATE_INITIALIZED) { "output not initialized" }
            output.setBufferSizeInFrames(maxOf(minOut / 8, frames))
            var settings = SvanRepository.settings.value
            var dsp = buildEngine(settings)
            engine = dsp
            synchronized(engineLock) { current = dsp }
            eqWatcher = Thread({
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

            var allowed: Set<Int> = emptySet()
            var input = openRecord(mp, allowed)
            record = input
            activeRecord = input
            input.startRecording()
            output.play()
            SessionRouter.onCaptureStarted(mp, systemPackages)
            EqController.log("capture: started, quality=${settings.quality}, DSP latency=${dsp.latencyFrames} frames, output buffer=${output.bufferSizeInFrames} frames")
            val buf = FloatArray(frames * 2)
            var levelPeak = 0f
            var levelFrames = 0L
            var writtenFrames = 0L
            var dspNanos = 0L
            var processedFrames = 0L
            while (running) {
                val next = SessionRouter.captureUids
                if (next !== allowed) {
                    // Route changes are rare. No record, filter or buffer allocation
                    // occurs in steady playback. Never capture unknown/unmuted apps.
                    input.stop(); input.release(); record = null; activeRecord = null
                    allowed = next
                    input = openRecord(mp, allowed)
                    record = input; activeRecord = input
                    input.startRecording()
                    EqController.log("capture filter: ${allowed.size} muted UID(s)")
                }
                val now = SvanRepository.settings.value
                if (now != settings) {
                    // Build before swapping so an unsuccessful rebuild leaves a
                    // valid handle for cleanup. Native parameter edits stay separate.
                    val replacement = buildEngine(now)
                    synchronized(engineLock) {
                        current = replacement
                        dsp.close()
                        dsp = replacement
                        engine = replacement
                        settings = now
                    }
                    EqController.log("capture: engine rebuilt, quality=${now.quality}")
                }
                val n = input.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n < 0) { if (running) EqController.log("capture: read failed ($n)"); break }
                if (n == 0) continue
                for (i in 0 until n) levelPeak = maxOf(levelPeak, kotlin.math.abs(buf[i]))
                levelFrames += n / 2
                val begin = System.nanoTime()
                dsp.process(buf, buf, n / 2)
                dspNanos += System.nanoTime() - begin
                processedFrames += n / 2
                var written = 0
                while (running && written < n) {
                    val count = output.write(buf, written, n - written, AudioTrack.WRITE_BLOCKING)
                    if (count <= 0) { running = false; break }
                    written += count
                    writtenFrames += count / 2
                }
                if (levelFrames >= rate * 2) {
                    val played = output.playbackHeadPosition.toLong() and 0xffffffffL
                    val queued = (writtenFrames - played).coerceAtLeast(0)
                    stats = Stats(output.bufferSizeInFrames * 1000.0 / rate, queued * 1000.0 / rate,
                        dsp.latencyFrames * 1000.0 / rate, output.underrunCount,
                        dspNanos.toDouble() / (processedFrames * 1e9 / rate) * 100.0,
                        dsp.appliedGainDb, dsp.gainProtectionDb)
                    EqController.log("capture level: peak=%.4f over %d frames; output queued=%.1f ms, underruns=%d, DSP=%.1f%%".format(levelPeak, levelFrames, stats!!.queuedMs, stats!!.underruns, stats!!.dspPercent))
                    levelPeak = 0f; levelFrames = 0; dspNanos = 0; processedFrames = 0
                }
            }
        } catch (e: Exception) {
            EqController.log("capture: audio loop failed: $e")
        } finally {
            running = false
            activeRecord = null
            record?.let { runCatching { it.stop() }; it.release() }
            track?.let { runCatching { it.pause(); it.flush(); it.stop() }; it.release() }
            eqWatcher?.join(200)
            synchronized(engineLock) { current = null; engine?.close() }
            isRunning = false
            stats = null
            SessionRouter.onCaptureStopped()
            EqController.log("capture: stopped")
            stopSelf()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(mp: MediaProjection, allowed: Set<Int>): AudioRecord {
        val rate = EqController.SAMPLE_RATE
        val builder = AudioPlaybackCaptureConfiguration.Builder(mp)
        CaptureCompat.MIX_USAGES.forEach { builder.addMatchingUsage(it) }
        // An empty route list must yield silence, not an unrestricted capture.
        // Our own output opts out of capture in the manifest.
        if (allowed.isEmpty()) builder.addMatchingUid(Process.myUid())
        else allowed.forEach { builder.addMatchingUid(it) }
        val minIn = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        require(minIn > 0) { "unsupported capture format ($minIn)" }
        return AudioRecord.Builder()
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build())
            .setBufferSizeInBytes(maxOf(minIn, 256 * 8))
            .setAudioPlaybackCaptureConfig(builder.build()).build().also {
                if (it.state != AudioRecord.STATE_INITIALIZED) { it.release(); error("capture not initialized") }
            }
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
        engine.resetGainProtection()
        engine.setBands(eq.effectiveBands().map { it.toNative() })
        engine.setPreampDb(eq.effectivePreampDb())
        engine.setBassCharacter(eq.bassCharacter, eq.bass.crossoverHz)
        val v = eq.activeVocal
        val i = eq.activeInstrument
        engine.setStereoTuner(v.intimacy, v.warmth, v.smoothness, i.space, i.instruments)
    }

    override fun onDestroy() {
        running = false
        runCatching { activeRecord?.stop() }
        isRunning = false
        worker?.join(500)
        projection?.stop()
        if (worker == null) SessionRouter.onCaptureStopped()
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

    data class Stats(val bufferMs: Double, val queuedMs: Double, val dspLatencyMs: Double,
        val underruns: Int, val dspPercent: Double, val gainDb: Double, val protectionDb: Double)

    companion object {
        @Volatile var stats: Stats? = null
            private set
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
