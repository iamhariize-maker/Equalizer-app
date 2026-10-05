package app.svan.listening

import android.content.Context
import android.media.*

/** Own UID is excluded from Svan's routes; capture NONE prevents reprocessing the test audio. */
class ClipPlayer(context: Context) : AutoCloseable {
    companion object { @Volatile var playing=false; private set }
    private val manager=context.getSystemService(AudioManager::class.java)
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE).build()
    private val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE).setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener {if(it<0)stop()}.build()
    private var closed=false
    private var track: AudioTrack?=null
    @Synchronized fun play(samples: FloatArray,rate: Int) {
        check(!closed)
        stop()
        check(manager.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {"Pause other audio before comparing"}
        playing=true
        try {
            val t=AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size*4).build()
            track=t;check(t.state==AudioTrack.STATE_INITIALIZED&&t.write(samples,0,samples.size,AudioTrack.WRITE_BLOCKING)==samples.size) {"Couldn't prepare comparison audio"};t.play()
        } catch(e: Exception){stop();throw e}
    }
    @Synchronized fun stop(){track?.let {runCatching{it.stop()};it.release()};track=null;playing=false;manager.abandonAudioFocusRequest(focus)}
    @Synchronized override fun close(){closed=true;stop()}
}
