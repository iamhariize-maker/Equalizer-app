package app.svan.listening

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/** Independent, capture-excluded speaker track. Never connected to the recording rings or DSP. */
internal object ProofSpeakerCue {
    private val busy = AtomicBoolean(false)
    fun play(context: Context, note: (String?) -> Unit) {
        if (!busy.compareAndSet(false, true)) { note("Speaker cue is still playing. The new sync frame was saved."); return }
        Thread({
            var track: AudioTrack? = null
            var listener: AudioRouting.OnRoutingChangedListener? = null
            val wrongRoute = AtomicBoolean(false)
            try {
                val manager = context.applicationContext.getSystemService(AudioManager::class.java)
                val speaker = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?: error("Built-in speaker unavailable; use the flash or clock")
                val rate = 48000
                val audio = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                    .setBufferSizeInBytes(maxOf(1920, AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)))
                    .setTransferMode(AudioTrack.MODE_STREAM).build()
                track = audio
                check(audio.state == AudioTrack.STATE_INITIALIZED) { "Speaker track unavailable" }
                check(audio.setPreferredDevice(speaker)) { "Speaker route rejected; use the flash or clock" }
                val routing = AudioRouting.OnRoutingChangedListener { routed ->
                    if (routed.routedDevice?.id != speaker.id) {
                        wrongRoute.set(true)
                        runCatching { audio.pause(); audio.flush() }
                    }
                }
                listener = routing
                // Establish the actual route with silence before submitting any click samples.
                audio.play()
                val silence = FloatArray(240)
                var primed = 0L
                val deadline = System.nanoTime() + 1_000_000_000L
                while (audio.routedDevice == null && System.nanoTime() < deadline) {
                    check(audio.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING) == silence.size)
                    primed += silence.size
                    Thread.sleep(5)
                }
                check(audio.routedDevice?.id == speaker.id) { "Speaker routing could not be verified; use the flash or clock" }
                audio.addOnRoutingChangedListener(routing, Handler(Looper.getMainLooper()))
                val burst = FloatArray(240)
                var frame = 0L
                while (frame < rate * 51L / 100) {
                    check(!wrongRoute.get() && audio.routedDevice?.id == speaker.id) { "Speaker route changed; cue stopped" }
                    val n = minOf(burst.size.toLong(), rate * 51L / 100 - frame).toInt()
                    for (i in 0 until n) burst[i] = ProofSyncCue.sample(frame + i, rate)
                    check(audio.write(burst, 0, n, AudioTrack.WRITE_BLOCKING) == n) { "Speaker cue write failed" }
                    frame += n
                }
                // Wait for the queued cue to leave the track before releasing it.
                val finish = System.nanoTime() + 2_000_000_000L
                while (audio.playbackHeadPosition.toLong() < primed + frame && System.nanoTime() < finish) {
                    check(!wrongRoute.get() && audio.routedDevice?.id == speaker.id) { "Speaker route changed; cue stopped" }
                    Thread.sleep(5)
                }
                note(null)
            } catch (e: Exception) { note(e.message ?: "Speaker cue unavailable; use the flash or clock") }
            finally {
                listener?.let { runCatching { track?.removeOnRoutingChangedListener(it) } }
                runCatching { track?.stop() }; track?.release(); busy.set(false)
            }
        }, "svan-sync-speaker").start()
    }
}
