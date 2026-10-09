package app.svan.testsource

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * The "music player": like every real player (YouTube Music, Poweramp, Spotify) it
 * plays from a foreground media-playback service. A bare activity gets frozen by
 * Android's cached-app freezer after ~20 s in the background, which starves its
 * AudioTrack — that made an earlier test look like Svan had silenced the app.
 */
class TonePlayerService : Service() {

    private class ToneRun(val explicit: Boolean, val component: Boolean, @Volatile var amplitude: Float) {
        @Volatile var stopped = false
        @Volatile var paused = false
        @Volatile var track: AudioTrack? = null
        var thread: Thread? = null
    }
    @Volatile private var activeRun: ToneRun? = null
    private lateinit var mediaSession: MediaSession

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSession(this, "Svan streaming-style test player").apply {
            setPlaybackToLocal(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            isActive = true
        }
    }

    private fun mediaState(state: Int) {
        mediaSession.setPlaybackState(PlaybackState.Builder().setState(state, 0, 1f).build())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        handle(intent ?: return START_NOT_STICKY)
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("player", "Test player", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, "player").setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Svan test player").build()
    }

    private fun handle(intent: Intent) {
        if (intent.hasExtra("live_amp")) {
            activeRun?.amplitude = intent.getFloatExtra("live_amp", 0.25f)
            return
        }
        if (intent.hasExtra("pause")) {
            activeRun?.let {
                it.paused = intent.getBooleanExtra("pause", false)
                mediaState(if (it.paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING)
            }
            return
        }
        stopTone()
        if (intent.getBooleanExtra("stop", false)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val freq = intent.getFloatExtra("freq", 1000f).toDouble()
        // Exercises a UID-wide opt-out while the new AudioTrack's own attributes still allow capture.
        getSystemService(AudioManager::class.java).setAllowedCapturePolicy(
            intent.getIntExtra("uid_capture_policy", AudioAttributes.ALLOW_CAPTURE_BY_ALL))
        val amp = intent.getFloatExtra("amp", 0.25f)
        val broadcast = intent.getBooleanExtra("broadcast", true)
        val run = ToneRun(intent.getBooleanExtra("explicit", true), intent.getBooleanExtra("component", false), amp)
        // Simulates a stream that opts out of playback capture mid-session (DRM'd track, ad, ...).
        val noCapture = intent.getBooleanExtra("nocapture", false)
        activeRun = run
        run.thread = Thread { play(run, freq, amp, broadcast, noCapture, intent.getIntExtra("usage", AudioAttributes.USAGE_MEDIA), intent.getIntExtra("content", AudioAttributes.CONTENT_TYPE_MUSIC)) }.also { it.start() }
    }

    private fun play(run: ToneRun, freq: Double, amp: Float, broadcast: Boolean, noCapture: Boolean, usage: Int, content: Int) {
        if (run.stopped) return
        val rate = 48000
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(usage)
                    .setContentType(content)
                    .setAllowedCapturePolicy(if (noCapture) AudioAttributes.ALLOW_CAPTURE_BY_NONE else AudioAttributes.ALLOW_CAPTURE_BY_ALL)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val session = track.audioSessionId
        run.track = track
        var announced = false
        try {
            if (run.stopped) return
            if (broadcast) { sendSession(run, AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION, session); announced = true }
            Log.i(TAG, "playing ${freq}Hz amp=$amp session=$session broadcast=$broadcast noCapture=$noCapture pkg=$packageName")
            val n = 960
            val buf = FloatArray(n * 2)
            var phase = 0.0
            val step = 2 * PI * freq / rate
            if (run.stopped) return
            track.play()
            if (activeRun === run) mediaState(PlaybackState.STATE_PLAYING)
            while (!run.stopped) {
                if (run.paused) { if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause(); Thread.sleep(20); continue }
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                val amplitude = run.amplitude
                for (i in 0 until n) {
                    val v = (amplitude * sin(phase)).toFloat()
                    buf[2 * i] = v; buf[2 * i + 1] = v
                    phase += step
                    if (phase > 2 * PI) phase -= 2 * PI
                }
                track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
            }
        } catch (e: RuntimeException) {
            if (!run.stopped) Log.e(TAG, "test player failed session=$session", e)
        } finally {
            runCatching { track.stop() }
            if (announced) sendSession(run, AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION, session)
            runCatching { track.release() }
            run.track = null
            Log.i(TAG, "stopped session=$session")
        }
    }

    private fun sendSession(run: ToneRun, action: String, session: Int) {
        sendBroadcast(
            Intent(action)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                .apply { if (run.explicit) setPackage(EQ_PACKAGE) }
                .apply { if (run.component) component = android.content.ComponentName(EQ_PACKAGE, "app.svan.SessionReceiver") },
        )
    }

    private fun stopTone() {
        mediaState(PlaybackState.STATE_STOPPED)
        val run = activeRun ?: return
        activeRun = null
        // A replacement must not revive an old blocked writer with a shared playing flag.
        run.stopped = true
        run.track?.let { track -> runCatching { track.pause(); track.flush(); track.stop() } }
        run.thread?.join(1000)
        run.thread = null
    }

    override fun onDestroy() {
        stopTone()
        mediaSession.release()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "EqTestSource"
        const val EQ_PACKAGE = "app.svan"
    }
}
