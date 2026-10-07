package app.svan

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Visualizer
import android.content.Context
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Answers "does the device *audibly* honour N bands?" (the band-limit probe
 * only proves the values are stored).
 *
 * Method: play test tones on our own session through a DynamicsProcessing whose
 * bands alternate +6 / -6 dB, and read the level with a Visualizer on the same
 * session. Adjacent bands should differ by 12 dB. If the implementation smears
 * bands together (coarse FFT bins), the measured difference shrinks toward 0.
 *
 * Plays audible tones; keep the volume low. Needs RECORD_AUDIO (Visualizer).
 */
object ResolutionProbe {

    private const val TONE_SEC = 0.35

    /** Fast way to push a whole curve: one Eq object instead of N per-band calls. */
    fun timeBulkSet(context: Context): String = buildString {
        appendLine("Bulk vs per-band update timing:")
        withSession(context) { session ->
            for (bands in intArrayOf(128, 1024)) {
                val dp = DynamicsProcessing(0, session, config(bands))
                try {
                    val centers = GlobalEqEngine.logSpaced(bands, 20.0, 20000.0)
                    var t0 = System.nanoTime()
                    for (i in 0 until bands) dp.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, centers[i].toFloat(), 3f))
                    val perBandMs = (System.nanoTime() - t0) / 1e6
                    val eq = DynamicsProcessing.Eq(true, true, bands)
                    for (i in 0 until bands) eq.setBand(i, DynamicsProcessing.EqBand(true, centers[i].toFloat(), -3f))
                    t0 = System.nanoTime()
                    dp.setPreEqAllChannelsTo(eq)
                    val bulkMs = (System.nanoTime() - t0) / 1e6
                    val ok = abs(dp.getPreEqBandByChannelIndex(0, bands / 2).gain + 3f) < 0.01f
                    appendLine("  %4d bands: per-band %.1f ms, bulk %.1f ms (bulk applied=%s)".format(bands, perBandMs, bulkMs, ok))
                } finally {
                    dp.release()
                }
            }
        }
    }

    fun run(context: Context): String = buildString {
        appendLine("Audible resolution (expected 12.0 dB between adjacent bands):")
        withSession(context) { session ->
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(CaptureService.epoch?.sampleRate ?: RatePolicy.SAFE_HZ).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
                )
                .setSessionId(session)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            val vis = try {
                Visualizer(session).apply {
                    measurementMode = Visualizer.MEASUREMENT_MODE_PEAK_RMS
                    enabled = true
                }
            } catch (e: RuntimeException) {
                track.release()
                appendLine("  Visualizer unavailable (grant microphone permission via button 4 first): $e")
                return@withSession
            }
            track.play()
            try {
                for (bands in intArrayOf(31, 64, 128, 256, 1024)) {
                    val centers = GlobalEqEngine.logSpaced(bands, 20.0, 20000.0)
                    val dp = DynamicsProcessing(0, session, config(bands))
                    try {
                        for (i in 0 until bands) {
                            val cutoff = if (i + 1 < bands) kotlin.math.sqrt(centers[i] * centers[i + 1]) else 22000.0
                            dp.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, cutoff.toFloat(), if (i % 2 == 0) 6f else -6f))
                        }
                        val parts = mutableListOf<String>()
                        for (target in doubleArrayOf(60.0, 1000.0, 10000.0)) {
                            val i = centers.indices.minBy { abs(centers[it] - target) }.coerceAtMost(bands - 2)
                            val diff = gainDb(track, vis, dp, centers[i]) - gainDb(track, vis, dp, centers[i + 1])
                            parts += "%s: %+.1f".format(label(target), if (i % 2 == 0) diff else -diff)
                        }
                        appendLine("  %4d bands  %s dB".format(bands, parts.joinToString("  ")))
                    } finally {
                        dp.release()
                    }
                }
            } finally {
                vis.release()
                track.stop()
                track.release()
            }
        }
        appendLine("  ~12 = fully resolved, ~0 = bands smeared together.")
        appendLine("  All ~0 at every count = Visualizer sees pre-effect audio (inconclusive).")
    }

    /** Level with DP on minus level with DP off, at [freq]. */
    private fun gainDb(track: AudioTrack, vis: Visualizer, dp: DynamicsProcessing, freq: Double): Double {
        dp.enabled = true
        val on = levelMb(track, vis, freq)
        dp.enabled = false
        val off = levelMb(track, vis, freq)
        return (on - off) / 100.0
    }

    private fun levelMb(track: AudioTrack, vis: Visualizer, freq: Double): Int {
        val rate = track.sampleRate
        val n = (rate * TONE_SEC).toInt()
        val buf = FloatArray(n * 2)
        for (i in 0 until n) {
            val v = (0.1 * sin(2 * PI * freq * i / rate)).toFloat()
            buf[2 * i] = v; buf[2 * i + 1] = v
        }
        // Two chunks: by the time the second is queued the first is playing,
        // so the Visualizer window holds only this tone at this setting.
        track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
        track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
        val m = Visualizer.MeasurementPeakRms()
        vis.getMeasurementPeakRms(m)
        return m.mRms
    }

    private fun label(f: Double) = if (f >= 1000) "${(f / 1000).toInt()}k" else "${f.toInt()}"

    private fun config(bands: Int) = DynamicsProcessing.Config.Builder(
        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
        true, bands, false, 0, false, 0, false,
    ).build()

    private inline fun withSession(context: Context, block: (Int) -> Unit) {
        val session = context.getSystemService(AudioManager::class.java).generateAudioSessionId()
        block(session)
    }
}
