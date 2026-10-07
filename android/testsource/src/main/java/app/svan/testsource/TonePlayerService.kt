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

    @Volatile private var playing = false
    private var thread: Thread? = null
    private var explicitBroadcast = true
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
        stopTone()
        if (intent.getBooleanExtra("stop", false)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val freq = intent.getFloatExtra("freq", 1000f).toDouble()
        val amp = intent.getFloatExtra("amp", 0.25f)
        val broadcast = intent.getBooleanExtra("broadcast", true)
        explicitBroadcast = intent.getBooleanExtra("explicit", true)
        // Simulates a stream that opts out of playback capture mid-session (DRM'd track, ad, ...).
        val noCapture = intent.getBooleanExtra("nocapture", false)
        playing = true
        thread = Thread { play(freq, amp, broadcast, noCapture, intent.getIntExtra("usage", AudioAttributes.USAGE_MEDIA), intent.getIntExtra("content", AudioAttributes.CONTENT_TYPE_MUSIC)) }.also { it.start() }
    }

    private fun play(freq: Double, amp: Float, broadcast: Boolean, noCapture: Boolean, usage: Int, content: Int) {
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
        if (broadcast) sendSession(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION, session)
        Log.i(TAG, "playing ${freq}Hz amp=$amp session=$session broadcast=$broadcast noCapture=$noCapture pkg=$packageName")
        val n = 960
        val buf = FloatArray(n * 2)
        var phase = 0.0
        val step = 2 * PI * freq / rate
        track.play()
        mediaState(PlaybackState.STATE_PLAYING)
        try {
            while (playing) {
                for (i in 0 until n) {
                    val v = (amp * sin(phase)).toFloat()
                    buf[2 * i] = v; buf[2 * i + 1] = v
                    phase += step
                    if (phase > 2 * PI) phase -= 2 * PI
                }
                track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
            }
        } finally {
            track.stop()
            if (broadcast) sendSession(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION, session)
            track.release()
            Log.i(TAG, "stopped session=$session")
        }
    }

    private fun sendSession(action: String, session: Int) {
        sendBroadcast(
            Intent(action)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                .apply { if (explicitBroadcast) setPackage(EQ_PACKAGE) },
        )
    }

    private fun stopTone() {
        mediaState(PlaybackState.STATE_STOPPED)
        playing = false
        thread?.join(1000)
        thread = null
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
