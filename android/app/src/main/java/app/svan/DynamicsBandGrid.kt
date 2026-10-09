package app.svan

import kotlin.math.min
import kotlin.math.sqrt

/**
 * Band edges that Android's frequency-domain DynamicsProcessing applies in full.
 *
 * AOSP's DynamicsProcessing (DPFrequency.cpp; byte-identical from android14 through android17 and main) sets each
 * band's last FFT bin to `(int)(0.5 + cutoff * block / rate)`, and gives the band the bins from the previous band's
 * last bin + 1 up to its own. A band whose last bin does not pass the previous one gets no bins, so its gain is never
 * applied. Svan's 128 log-spaced bands are narrower than one bin below about 200 Hz at a 4096-sample block (11.7 Hz
 * bins), so most of the bass bands were lost.
 *
 * This grid puts every edge on a whole bin of the 4096-sample grid and gives each band at least one bin. Blocks of 8192
 * and 16384 have bins at the same edges (every stop doubles or quadruples exactly), so the layout holds there too. At
 * 2048 samples bins are twice as wide, neighbouring stops collide, and bands are lost again.
 */
internal object DynamicsBandGrid {
    /** The bin width this grid relies on: a 4096-sample block. */
    const val GRID = 4096
    private const val TOP_HZ = 22_000.0

    /** Width of one bin in Hz at [rate]. */
    fun binHz(rate: Int): Double = rate.toDouble() / GRID

    /**
     * The last FFT bin each band reaches, strictly increasing: band i fills the bins (stops[i-1], stops[i]].
     * Edges follow Svan's log spacing wherever bins allow. Where two edges would share a bin, the later band takes
     * the next bin, so the bass uses one band per bin and the upper bands keep their log spacing.
     */
    fun stops(bands: Int, rate: Int): IntArray {
        require(bands >= 2) { "at least two bands" }
        val bin = binHz(rate)
        val centres = GlobalEqEngine.logSpaced(bands, 20.0, 20_000.0)
        val stops = IntArray(bands)
        for (i in 0 until bands) {
            val edge = if (i + 1 < bands) sqrt(centres[i] * centres[i + 1]) else TOP_HZ
            stops[i] = maxOf((edge / bin + 0.5).toInt(), if (i == 0) 0 else stops[i - 1] + 1)
        }
        val top = (TOP_HZ / bin + 0.5).toInt()
        if (stops[bands - 1] > top) {
            stops[bands - 1] = top
            for (i in bands - 2 downTo 0) stops[i] = min(stops[i], stops[i + 1] - 1)
        }
        check(stops[0] >= 0 && (1 until bands).all { stops[it] > stops[it - 1] }) { "band layout does not fit the grid" }
        return stops
    }

    /** Upper edge of each band in Hz: its stop times the bin width, so AOSP's rule returns the same stop. */
    fun cutoffsHz(bands: Int, rate: Int): DoubleArray {
        val bin = binHz(rate)
        return stops(bands, rate).let { s -> DoubleArray(bands) { s[it] * bin } }
    }

    /**
     * The band that owns the bin nearest [hz] at [block] samples: the first band whose last bin reaches it. The bands
     * cover the same frequency ranges at every block that is a multiple of [GRID]; only the bin positions move.
     */
    fun bandFor(hz: Double, bands: Int, rate: Int, block: Int = GRID): Int {
        require(block > 0 && block % GRID == 0) { "the layout holds for blocks that are multiples of $GRID" }
        val bin = (hz * block / rate + 0.5).toInt()
        val scale = block / GRID
        val s = stops(bands, rate)
        return s.indexOfFirst { it * scale >= bin }.let { if (it < 0) bands - 1 else it }
    }

    /** AOSP's last-bin rule for a cutoff (DPFrequency computeBinStartStop). */
    fun stopBin(cutoffHz: Double, block: Int, rate: Int): Int = (0.5 + cutoffHz * block / rate).toInt()
}
