package dev.equalizer.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import kotlin.math.abs

/**
 * Answers spike question #1 on real hardware: how many DynamicsProcessing EQ
 * bands does *this* device accept, and does it keep the gains we set?
 * (The public docs don't state a limit; OEM implementations differ.)
 */
object DynamicsProbe {

    private val BAND_COUNTS = intArrayOf(10, 31, 64, 80, 128, 256, 512, 1024)

    fun run(context: Context): String = buildString {
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        val am = context.getSystemService(AudioManager::class.java)
        val session = am.generateAudioSessionId()
        // A live track on the session, like a real player would have.
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(48000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setSessionId(session)
            .build()
        try {
            for (bands in BAND_COUNTS) appendLine(probe(session, bands))
        } finally {
            track.release()
        }
    }

    private fun probe(session: Int, bands: Int): String {
        val t0 = System.nanoTime()
        var dp: DynamicsProcessing? = null
        return try {
            val cfg = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                true, bands, false, 0, false, 0, true,
            ).build()
            dp = DynamicsProcessing(0, session, cfg)
            val centers = GlobalEqEngine.logSpaced(bands, 20.0, 20000.0)
            for (i in 0 until bands) {
                val gain = if (i % 2 == 0) 6f else -6f
                dp.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, centers[i].toFloat(), gain))
            }
            val setMs = (System.nanoTime() - t0) / 1e6
            // Read back to detect silent clamping/ignoring by the implementation.
            var mismatches = 0
            for (i in 0 until bands) {
                val expected = if (i % 2 == 0) 6f else -6f
                if (abs(dp.getPreEqBandByChannelIndex(0, i).gain - expected) > 0.01f) mismatches++
            }
            val reported = dp.getPreEqByChannelIndex(0).bandCount
            "  %4d bands: OK   reported=%d  readback-mismatches=%d  setup=%.1f ms".format(bands, reported, mismatches, setMs)
        } catch (e: Throwable) {
            "  %4d bands: FAIL %s: %s".format(bands, e.javaClass.simpleName, e.message)
        } finally {
            dp?.release()
        }
    }
}
