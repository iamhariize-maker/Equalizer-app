package app.svan

import app.svan.diag.EffectsLab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicsBandGridTest {
    /** Bands that AOSP's frequency-domain DynamicsProcessing applies at [block]: a band keeps its bins only if its stop passes the previous one. */
    private fun appliedBands(cutoffs: DoubleArray, block: Int, rate: Int): Int {
        var next = 0
        var applied = 0
        for (c in cutoffs) {
            val stop = DynamicsBandGrid.stopBin(c, block, rate)
            if (stop >= next) applied++
            next = stop + 1
        }
        return applied
    }

    @Test fun everyBandFillsABinAtTheCoarserBlocks() {
        for (rate in listOf(44_100, 48_000)) for (block in listOf(4096, 8192, 16384)) {
            val cutoffs = DynamicsBandGrid.cutoffsHz(128, rate)
            assertEquals("rate $rate, block $block", 128, appliedBands(cutoffs, block, rate))
        }
    }

    @Test fun theLayoutDoesNotClaimTheSmallerBlock() {
        // Bins at 2048 samples are twice as wide, so neighbouring stops collide and some bands are lost.
        assertTrue(appliedBands(DynamicsBandGrid.cutoffsHz(128, 48_000), 2048, 48_000) < 128)
    }

    @Test fun svansCurrentLogLayoutLosesItsBassBandsAtTheDefaultBlock() {
        val current = DoubleArray(128) { EffectsLab.cutoff(it) }
        assertEquals(128 - 27, appliedBands(current, 4096, 48_000))
        assertEquals(128 - 16, appliedBands(current, 8192, 48_000))
    }

    @Test fun theBassUsesOneBandPerBinAndTheTopStaysInRange() {
        val stops = DynamicsBandGrid.stops(128, 48_000)
        for (i in 0 until 16) assertEquals("band $i", i + 2, stops[i])
        assertEquals(1877, stops.last())
        assertTrue(1877 * DynamicsBandGrid.binHz(48_000) <= 22_000.0)
    }

    @Test fun cutoffsAreWholeBins() {
        val bin = DynamicsBandGrid.binHz(48_000)
        val stops = DynamicsBandGrid.stops(128, 48_000)
        val cutoffs = DynamicsBandGrid.cutoffsHz(128, 48_000)
        for (i in stops.indices) assertEquals("band $i", stops[i].toDouble(), cutoffs[i] / bin, 1e-9)
    }

    @Test fun aBoostFollowsTheBinNearestTheToneAtEachBlock() {
        // 25 Hz sits nearest bin 2 of the 4096 grid, and bin 4 of an 8192 block, and bin 9 of a 16384 block.
        assertEquals(0, DynamicsBandGrid.bandFor(25.0, 128, 48_000, 4096))
        assertEquals(0, DynamicsBandGrid.bandFor(25.0, 128, 48_000, 8192))
        assertEquals(1, DynamicsBandGrid.bandFor(25.0, 128, 48_000, 16384))
    }

    @Test fun aBoostLandsOnTheBandThatOwnsItsBin() {
        val stops = DynamicsBandGrid.stops(128, 48_000)
        val bin = DynamicsBandGrid.binHz(48_000)
        for (hz in listOf(25.0, 40.0, 63.0, 100.0, 160.0)) {
            val band = DynamicsBandGrid.bandFor(hz, 128, 48_000)
            val k = (hz / bin + 0.5).toInt()
            val below = if (band == 0) -1 else stops[band - 1]
            assertTrue("$hz Hz: band $band owns bin $k", below < k && k <= stops[band])
        }
    }
}
