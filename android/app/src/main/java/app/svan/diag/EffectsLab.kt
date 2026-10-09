package app.svan.diag

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.Visualizer
import app.svan.DynamicsBandGrid
import app.svan.EqController
import app.svan.GlobalEqEngine
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug-only measurement of what Android's audio effects do on this device. CI runs it on the emulator (`am start ...
 * --es cmd effects_lab`). It plays known signals on its own audio session and records the Visualizer's readings. The
 * runs compare DynamicsProcessing with Svan's current band edges against edges laid on whole FFT bins
 * ([DynamicsBandGrid]), and AOSP's own 5-band Equalizer. android/scripts/system_effects_lab.py measures the host
 * capture of the output and compares it with what the framework's source predicts. Nothing here ships to users, and
 * the probe changes nothing outside its own session.
 */
object EffectsLab {
    const val RATE = 48_000
    const val BANDS = 128
    /** Silence before and after each signal, longer than the processing latency so each segment can be found. */
    const val LEAD_S = 0.6
    const val TAIL_S = 0.6

    private val centers = GlobalEqEngine.logSpaced(BANDS, 20.0, 20_000.0)

    /** Upper edge of band [i], as Svan sends it to DynamicsProcessing (see GlobalEqEngine.applyTo). */
    fun cutoff(i: Int): Double = if (i + 1 < BANDS) sqrt(centers[i] * centers[i + 1]) else 22_000.0

    /** The band whose centre is nearest [hz] on the log scale. */
    fun bandFor(hz: Double): Int = (127.0 * log10(hz / 20.0) / 3.0).roundToInt().coerceIn(0, BANDS - 1)

    data class Tone(val hz: Double, val amp: Double, val durS: Double, val burstMs: Double = 0.0, val periodMs: Double = 0.0)

    /** [bands]: band index to gain in dB; every other band stays at 0 dB. */
    data class Seg(val label: String, val tone: Tone, val bands: Map<Int, Double> = emptyMap(), val mbcCompress: Boolean = false)

    data class Run(
        val label: String,
        val blockMs: Double,
        val limiter: Boolean = true,
        val inputGainDb: Double = 0.0,
        val mbcAttackMs: Double? = null,
        val visualizer: Boolean = false,
        val segs: List<Seg>,
        /** "log": Svan's current band edges. "bin": edges on whole bins of the 4096-sample grid. */
        val layout: String = "log",
        /** "dp": DynamicsProcessing with [layout]. "lvm": AOSP's 5-band Equalizer alone, for comparison. */
        val engine: String = "dp",
    )

    /** The fixed program. Block sizes are written as samples / 48, and the engine rounds them to a power of two. */
    fun program(): List<Run> {
        val runs = mutableListOf<Run>()
        val k1k = bandFor(1000.0)
        runs += Run("visualizer_tap", blockMs = 4096 / 48.0, visualizer = true, segs = listOf(
            Seg("flat_1k", Tone(1000.0, 0.1, 2.0)),
            Seg("boost_1k_12db", Tone(1000.0, 0.1, 2.0), bands = mapOf(k1k to 12.0)),
        ))
        for (block in listOf(2048, 4096, 8192, 16384)) {
            val segs = mutableListOf<Seg>()
            for (hz in listOf(25.0, 40.0, 63.0, 100.0, 160.0)) {
                segs += Seg("flat_${hz.toInt()}", Tone(hz, 0.1, 1.6))
                segs += Seg("boost_${hz.toInt()}_6db", Tone(hz, 0.1, 1.6), bands = mapOf(bandFor(hz) to 6.0))
            }
            runs += Run("bass_block_$block", blockMs = block / 48.0, segs = segs)
        }
        // The same bass comparison with Svan's edges laid on whole bins. The boost goes on the band that owns the bin nearest the tone.
        for (block in listOf(4096, 8192, 16384)) {
            val segs = mutableListOf<Seg>()
            for (hz in listOf(25.0, 40.0, 63.0, 100.0, 160.0)) {
                segs += Seg("flat_${hz.toInt()}", Tone(hz, 0.1, 1.6))
                segs += Seg("boost_${hz.toInt()}_6db", Tone(hz, 0.1, 1.6),
                    bands = mapOf(DynamicsBandGrid.bandFor(hz, BANDS, RATE, block) to 6.0))
            }
            runs += Run("bass_bin_$block", blockMs = block / 48.0, segs = segs, layout = "bin")
        }
        // AOSP's own 5-band Equalizer (time-domain biquads, centre 60 Hz, Q 0.96): +6 dB on band 0 against flat.
        val lvm = mutableListOf<Seg>()
        for (hz in listOf(40.0, 60.0, 100.0)) {
            lvm += Seg("flat_${hz.toInt()}", Tone(hz, 0.1, 1.6))
            lvm += Seg("boost_${hz.toInt()}_6db", Tone(hz, 0.1, 1.6), bands = mapOf(0 to 6.0))
        }
        runs += Run("equalizer_lvm", blockMs = 0.0, segs = lvm, engine = "lvm")
        // A 0.9 sine (-0.9 dBFS) with +12 dB at 1 kHz reaches about +11 dBFS before the limiter.
        val boost12 = mapOf(k1k to 12.0)
        runs += Run("clip_limiter_on", blockMs = 4096 / 48.0, limiter = true,
            segs = listOf(Seg("sine_0.9_plus12", Tone(1000.0, 0.9, 2.0), bands = boost12)))
        runs += Run("clip_limiter_off", blockMs = 4096 / 48.0, limiter = false,
            segs = listOf(Seg("sine_0.9_plus12", Tone(1000.0, 0.9, 2.0), bands = boost12)))
        runs += Run("clip_headroom_minus12", blockMs = 4096 / 48.0, limiter = true, inputGainDb = -12.0,
            segs = listOf(Seg("sine_0.9_plus12", Tone(1000.0, 0.9, 2.0), bands = boost12)))
        runs += Run("transient_limiter_on", blockMs = 4096 / 48.0, limiter = true, segs = listOf(
            Seg("bursts_5ms_0.9_plus12", Tone(1000.0, 0.9, 2.4, burstMs = 5.0, periodMs = 300.0), bands = boost12)))
        // A 63 Hz tone at -20 dBFS through a 4:1 band below 200 Hz, with attack 1 ms and then 40 ms.
        for (attack in listOf(1.0, 40.0)) {
            runs += Run("mbc_attack_${attack.toInt()}ms", blockMs = 4096 / 48.0, mbcAttackMs = attack, segs = listOf(
                Seg("ref_63hz", Tone(63.0, 0.1, 1.6), mbcCompress = false),
                Seg("comp_63hz", Tone(63.0, 0.1, 1.6), mbcCompress = true),
            ))
        }
        return runs
    }

    /** Samples of [t] as interleaved stereo float frames, with burst gating when [Tone.periodMs] is set. */
    fun toneFrames(t: Tone): FloatArray {
        val frames = (t.durS * RATE).roundToInt()
        val out = FloatArray(frames * 2)
        val period = (t.periodMs * RATE / 1000.0).roundToInt()
        val burst = if (t.burstMs > 0) (t.burstMs * RATE / 1000.0).roundToInt() else frames
        for (n in 0 until frames) {
            val on = period <= 0 || n % period < burst
            val v = if (on) (t.amp * sin(2 * PI * t.hz * n / RATE)).toFloat() else 0f
            out[2 * n] = v
            out[2 * n + 1] = v
        }
        return out
    }

    private fun mbcBand(attackMs: Double, compress: Boolean) = if (compress) {
        DynamicsProcessing.MbcBand(true, 200f, attackMs.toFloat(), 80f, 4f, -30f, 0f, -90f, 1f, 0f, 0f)
    } else {
        DynamicsProcessing.MbcBand(true, 200f, attackMs.toFloat(), 80f, 1f, 0f, 0f, -90f, 1f, 0f, 0f)
    }

    /** Upper edge of each band for [layout]: Svan's log edges, or the bin-aligned grid. */
    private fun cutoffsFor(layout: String): DoubleArray =
        if (layout == "bin") DynamicsBandGrid.cutoffsHz(BANDS, RATE) else DoubleArray(BANDS) { cutoff(it) }

    /** Runs the whole program and writes files/effects-lab.json. Blocking: call from a worker thread. */
    fun run(context: Context): File {
        val out = File(context.filesDir, "effects-lab.json")
        out.delete()
        val audio = context.getSystemService(AudioManager::class.java)
        val runs = JSONArray()
        val errors = JSONArray()
        for (run in program()) {
            EqController.log("FXLAB run ${run.label}")
            runCatching { runs.put(execute(audio, run)) }.onFailure {
                errors.put("${run.label}: ${it.javaClass.simpleName}: ${it.message}")
                EqController.log("FXLAB run ${run.label} failed: $it")
            }
        }
        out.writeText(JSONObject()
            .put("format", 2).put("rate", RATE).put("leadS", LEAD_S).put("tailS", TAIL_S)
            .put("runs", runs).put("errors", errors).put("done", true).toString(2))
        EqController.log("FXLAB_DONE")
        return out
    }

    private fun execute(audio: AudioManager, run: Run): JSONObject {
        val session = audio.generateAudioSessionId()
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setSessionId(session)
            .build()
        val mbc = run.mbcAttackMs != null
        var fx: AudioEffect? = null
        var meter: Visualizer? = null
        try {
            val cutoffs = cutoffsFor(run.layout)
            val dp = if (run.engine == "dp") {
                val cfg = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                    true, BANDS, mbc, if (mbc) 2 else 0, false, 0, run.limiter,
                ).setPreferredFrameDuration(run.blockMs.toFloat()).build()
                DynamicsProcessing(0, session, cfg).also { fx = it }
            } else null
            if (dp != null) {
                for (i in 0 until BANDS) dp.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, cutoffs[i].toFloat(), 0f))
                dp.setInputGainAllChannelsTo(run.inputGainDb.toFloat())
                if (run.limiter) dp.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -0.5f, 0f))
                if (mbc) dp.setMbcBandAllChannelsTo(1, DynamicsProcessing.MbcBand(true, 20_000f, 1f, 80f, 1f, 0f, 0f, -90f, 1f, 0f, 0f))
                dp.enabled = true
            }
            val eq = if (run.engine == "lvm") Equalizer(0, session).also { fx = it; it.enabled = true } else null
            if (run.visualizer) {
                meter = Visualizer(session).also {
                    it.measurementMode = Visualizer.MEASUREMENT_MODE_PEAK_RMS
                    it.enabled = true
                }
            }
            val current = DoubleArray(BANDS)
            track.play()
            val segments = JSONArray()
            for (seg in run.segs) {
                if (dp != null) {
                    for (i in 0 until BANDS) {
                        val want = seg.bands[i] ?: 0.0
                        if (abs(want - current[i]) > 1e-9) {
                            dp.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, cutoffs[i].toFloat(), want.toFloat()))
                            current[i] = want
                        }
                    }
                    if (mbc) dp.setMbcBandAllChannelsTo(0, mbcBand(run.mbcAttackMs ?: 1.0, seg.mbcCompress))
                }
                if (eq != null) {
                    for (b in 0 until eq.numberOfBands.toInt()) {
                        eq.setBandLevel(b.toShort(), ((seg.bands[b] ?: 0.0) * 100).roundToInt().toShort())
                    }
                }
                val readings = mutableListOf<Pair<Long, Double>>()
                val keep = AtomicBoolean(true)
                val began = System.currentTimeMillis()
                val probe = meter
                val poller = if (probe == null) null else thread(name = "fxlab-meter") {
                    val m = Visualizer.MeasurementPeakRms()
                    while (keep.get()) {
                        if (probe.getMeasurementPeakRms(m) == Visualizer.SUCCESS) {
                            synchronized(readings) { readings += (System.currentTimeMillis() - began) to m.mRms / 100.0 }
                        }
                        Thread.sleep(50)
                    }
                }
                writeFrames(track, FloatArray((LEAD_S * RATE).roundToInt() * 2))
                writeFrames(track, toneFrames(seg.tone))
                writeFrames(track, FloatArray((TAIL_S * RATE).roundToInt() * 2))
                keep.set(false)
                poller?.join()
                segments.put(JSONObject()
                    .put("label", seg.label).put("hz", seg.tone.hz).put("amp", seg.tone.amp).put("durS", seg.tone.durS)
                    .put("burstMs", seg.tone.burstMs).put("periodMs", seg.tone.periodMs)
                    .put("bands", JSONObject().apply { seg.bands.forEach { (i, g) -> put(i.toString(), g) } })
                    .put("mbcCompress", seg.mbcCompress)
                    .put("visualizer", JSONArray().apply { synchronized(readings) { readings.forEach { put(JSONArray(listOf(it.first, it.second))) } } }))
            }
            track.stop()
            val centre: Any = eq?.let { it.getCenterFreq(0) / 1000.0 } ?: JSONObject.NULL
            return JSONObject()
                .put("label", run.label).put("blockMs", run.blockMs).put("limiter", run.limiter)
                .put("inputGainDb", run.inputGainDb).put("mbcAttackMs", run.mbcAttackMs ?: JSONObject.NULL)
                .put("visualizer", run.visualizer).put("layout", run.layout).put("engine", run.engine)
                .put("bands", eq?.numberOfBands?.toInt() ?: (if (dp != null) BANDS else JSONObject.NULL))
                .put("centreHz", centre).put("segments", segments)
        } finally {
            runCatching { meter?.release() }
            runCatching { fx?.release() }
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun writeFrames(track: AudioTrack, samples: FloatArray) {
        var offset = 0
        while (offset < samples.size) {
            val n = track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
            if (n <= 0) error("AudioTrack write failed ($n)")
            offset += n
        }
    }
}
