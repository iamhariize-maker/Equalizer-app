package app.svan.testsource

import android.app.Activity
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.audiofx.AudioEffect
import android.os.Bundle
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * adb shell am start -n <pkg>/app.svan.testsource.ToneActivity \
 *     --ef freq 1000 --ef amp 0.25 --ez broadcast true
 * adb shell am start -n <pkg>/app.svan.testsource.ToneActivity --ez stop true
 */
class ToneActivity : Activity() {

    @Volatile private var playing = false
    private var thread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        stopTone()
        if (intent.getBooleanExtra("stop", false)) return
        val freq = intent.getFloatExtra("freq", 1000f).toDouble()
        val amp = intent.getFloatExtra("amp", 0.25f)
        val broadcast = intent.getBooleanExtra("broadcast", true)
        playing = true
        thread = Thread { play(freq, amp, broadcast) }.also { it.start() }
    }

    private fun play(freq: Double, amp: Float, broadcast: Boolean) {
        val rate = 48000
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val session = track.audioSessionId
        if (broadcast) sendSession(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION, session)
        Log.i(TAG, "playing ${freq}Hz amp=$amp session=$session broadcast=$broadcast pkg=$packageName")
        val n = 960
        val buf = FloatArray(n * 2)
        var phase = 0.0
        val step = 2 * PI * freq / rate
        track.play()
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
                .setPackage(EQ_PACKAGE), // explicit: implicit broadcasts to manifest receivers are dropped
        )
    }

    private fun stopTone() {
        playing = false
        thread?.join(1000)
        thread = null
    }

    override fun onDestroy() {
        stopTone()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "EqTestSource"
        const val EQ_PACKAGE = "app.svan"
    }
}
