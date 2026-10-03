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
        val quality = NativeEngine.Quality.entries[intent?.getIntExtra(EXTRA_QUALITY, 0) ?: 0]
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
        worker = Thread({ audioLoop(mp, quality) }, "eq-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission") // checked in MainActivity before starting
    private fun audioLoop(mp: MediaProjection, quality: NativeEngine.Quality) {
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

        // The platform mixer converts our float output; 24-bit dither keeps the
        // requantisation clean if the HAL runs at 24-bit.
        val engine = NativeEngine(rate, 2, quality, outputBits = 24)
        engine.loadParametricPreset(EqController.SAMPLE_PRESET)
        EqController.log("capture: started, quality=$quality, DSP latency=${engine.latencyFrames} frames")

        val frames = 256
        val buf = FloatArray(frames * 2)
        record.startRecording()
        track.play()
        try {
            while (running) {
                val n = record.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) continue
                engine.process(buf, buf, n / 2)
                track.write(buf, 0, n, AudioTrack.WRITE_BLOCKING)
            }
        } finally {
            record.stop(); record.release()
            track.stop(); track.release()
            engine.close()
            EqController.log("capture: stopped")
        }
    }

    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null

    /** With DUMP: re-sync sessions whenever any app starts/stops playing. */
    private fun startSessionWatch() {
        if (!PlaybackSessions.hasDumpPermission(this)) {
            EqController.log("session watch: DUMP not granted, relying on OPEN/CLOSE broadcasts only")
            return
        }
        val am = getSystemService(AudioManager::class.java)
        val sync = {
            Thread {
                val sessions = PlaybackSessions.query(this)
                if (sessions == null) EqController.log("session dump failed: ${PlaybackSessions.lastError}")
                else SessionRouter.sync(sessions)
            }.start()
        }
        val cb = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) = sync()
        }
        am.registerAudioPlaybackCallback(cb, null)
        playbackCallback = cb
        sync()
    }

    override fun onDestroy() {
        playbackCallback?.let { getSystemService(AudioManager::class.java).unregisterAudioPlaybackCallback(it) }
        SessionRouter.onCaptureStopped()
        running = false
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

        fun start(context: Context, resultCode: Int, data: Intent, quality: NativeEngine.Quality) {
            Log.i(TAG, "starting capture, quality=$quality")
            context.startForegroundService(
                Intent(context, CaptureService::class.java)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_RESULT_DATA, data)
                    .putExtra(EXTRA_QUALITY, quality.ordinal),
            )
        }
    }
}
