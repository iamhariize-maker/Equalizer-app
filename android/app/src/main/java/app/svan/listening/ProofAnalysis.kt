package app.svan.listening

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Running level statistics for one interleaved stereo stream. */
class LevelMeter {
    var peak = 0.0; private set
    var sumSquares = 0.0; private set
    var samples = 0L; private set
    /** Samples at or beyond full scale; they are clamped when written to the exported WAV. */
    var overs = 0L; private set
    fun add(x: Float) {
        val a = abs(x.toDouble())
        if (a > peak) peak = a
        if (a > 1.0) overs++
        sumSquares += a * a
        samples++
    }
    val rmsDbfs: Double get() = toDb(if (samples == 0L) 0.0 else sqrt(sumSquares / samples))
    val peakDbfs: Double get() = toDb(peak)
    companion object { fun toDb(x: Double) = 20.0 * log10(maxOf(x, 1e-9)) }
}

/** One third-octave row of the measured dry → processed change. */
data class SpectrumBand(val hz: Double, val dryDb: Double, val processedDb: Double) {
    val deltaDb: Double get() = processedDb - dryDb
}

/**
 * Welch-averaged third-octave spectrum of the mid (L+R)/2 signal, for the dry and the
 * processed stream together. Windows that are silent in BOTH streams are skipped so
 * gaps between tracks do not dilute the average. Only the difference between the two
 * streams is meaningful; levels are relative to a full-scale sine.
 */
class SpectrumPair(private val rate: Int, private val size: Int = 8192) {
    private val window = DoubleArray(size) { 0.5 - 0.5 * cos(2 * PI * it / size) }
    private val windowSum = window.sum()
    private val dryBuf = DoubleArray(size); private val wetBuf = DoubleArray(size)
    private var fill = 0
    private val centres = DoubleArray(BAND_COUNT) { 1000.0 * 2.0.pow((it + FIRST_K) / 3.0) }
    private val dryPower = DoubleArray(BAND_COUNT); private val wetPower = DoubleArray(BAND_COUNT)
    private val re = DoubleArray(size); private val im = DoubleArray(size)
    private val cosT = DoubleArray(size / 2) { cos(2 * PI * it / size) }
    private val sinT = DoubleArray(size / 2) { -sin(2 * PI * it / size) }
    var windows = 0; private set

    /** [samples] interleaved stereo floats in each array, starting at [from]. */
    fun add(dry: FloatArray, wet: FloatArray, from: Int, samples: Int) {
        var i = from
        val end = from + samples - 1
        while (i < end) {
            dryBuf[fill] = (dry[i] + dry[i + 1]) * 0.5
            wetBuf[fill] = (wet[i] + wet[i + 1]) * 0.5
            fill++; i += 2
            if (fill == size) { analyse(); fill = 0 }
        }
    }

    private fun analyse() {
        var eDry = 0.0; var eWet = 0.0
        for (k in 0 until size) { eDry += dryBuf[k] * dryBuf[k]; eWet += wetBuf[k] * wetBuf[k] }
        if (sqrt(eDry / size) < SILENT && sqrt(eWet / size) < SILENT) return
        accumulate(dryBuf, dryPower); accumulate(wetBuf, wetPower)
        windows++
    }

    private fun accumulate(src: DoubleArray, into: DoubleArray) {
        for (k in 0 until size) { re[k] = src[k] * window[k]; im[k] = 0.0 }
        fft()
        val binHz = rate.toDouble() / size
        val half = 2.0.pow(1.0 / 6.0)
        // Single-sided power, normalised so a full-scale sine reads 0 dB.
        val norm = 4.0 / (windowSum * windowSum)
        for (b in 0 until BAND_COUNT) {
            val lo = (centres[b] / half / binHz).toInt().coerceAtLeast(1)
            val hi = (centres[b] * half / binHz).toInt().coerceAtMost(size / 2 - 1)
            var p = 0.0
            for (k in lo..maxOf(lo, hi)) p += (re[k] * re[k] + im[k] * im[k]) * norm
            into[b] += p
        }
    }

    private fun fft() {
        val n = size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val tr = re[i]; re[i] = re[j]; re[j] = tr; val ti = im[i]; im[i] = im[j]; im[j] = ti }
        }
        var len = 2
        while (len <= n) {
            val step = n / len
            var i = 0
            while (i < n) {
                for (k in 0 until len / 2) {
                    val wr = cosT[k * step]; val wi = sinT[k * step]
                    val a = i + k; val b = a + len / 2
                    val xr = re[b] * wr - im[b] * wi; val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Empty when no window carried signal (e.g. the source was paused for the whole recording). */
    fun bands(): List<SpectrumBand> {
        if (windows == 0) return emptyList()
        return (0 until BAND_COUNT).filter { centres[it] * 2.0.pow(1.0 / 6.0) < rate / 2.0 }.map {
            SpectrumBand(centres[it], tenLog(dryPower[it] / windows), tenLog(wetPower[it] / windows))
        }
    }

    private fun tenLog(p: Double) = 10.0 * log10(maxOf(p, 1e-18))

    companion object {
        private const val FIRST_K = -15   // 1 kHz · 2^(-15/3) = 31.25 Hz
        private const val BAND_COUNT = 30 // … up to 1 kHz · 2^(14/3) ≈ 25.4 kHz, trimmed to Nyquist
        private const val SILENT = 1e-5   // −100 dBFS
    }
}

/** Plain-language label for what changed between two settings snapshots. */
object SettingsDiff {
    /** Settings worth naming in a segment label. Order is the display order. */
    private val NAMES = linkedMapOf(
        "spatialMode" to "Spatial mode", "captureRateHz" to "Capture rate Hz",
        "experimentalBassUnmask" to "Experimental bass unmask", "bassAttack" to "Bass attack", "bassSustain" to "Bass sustain",
        "bassDimension" to "Bass dimension", "bassTube" to "Bass tube colour", "analogTop" to "Analogue top", "expression" to "Expression", "qualityMode" to "Quality", "dither" to "Dither", "outputBitsIfDithered" to "Dither bits",
        "autoHeadroom" to "Auto headroom", "gainProtection" to "Gain protection", "eqEnabled" to "EQ",
        "preampDb" to "Preamp dB", "eqBandsApplied" to "EQ bands", "bassCharacter" to "Bass character",
        "headphoneCorrection" to "Headphone correction", "eqCurve" to "EQ curve",
        "vocalTuner" to "Vocal tuner", "instrumentTuner" to "Instrument tuner", "dynamicEq" to "Dynamic EQ",
    )

    /** "Quality: Balanced → Audiophile · Preamp dB: 0 → -3" */
    fun describe(before: Map<String, Any?>, after: Map<String, Any?>): String {
        val parts = NAMES.mapNotNull { (k, name) ->
            if (before[k] == after[k]) null
            else if (k in setOf("eqCurve", "vocalTuner", "instrumentTuner")) "$name changed"
            else "$name: ${show(before[k])} → ${show(after[k])}"
        }
        return parts.joinToString(" · ").ifEmpty { "Settings changed" }
    }

    private fun show(v: Any?) = when (v) {
        null -> "off"; true -> "on"; false -> "off"
        is Double -> if (v == Math.rint(v)) v.toInt().toString() else v.toString()
        else -> v.toString()
    }
}
