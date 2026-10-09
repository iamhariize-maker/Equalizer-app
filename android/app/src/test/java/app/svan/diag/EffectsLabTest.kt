package app.svan.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectsLabTest {
    @Test fun bandsFollowTheLogLayoutSvanSends() {
        assertEquals(72, EffectsLab.bandFor(1000.0))
        assertEquals(0, EffectsLab.bandFor(20.0))
        assertEquals(127, EffectsLab.bandFor(20_000.0))
        assertEquals(22_000.0, EffectsLab.cutoff(127), 1e-9)
        assertTrue(EffectsLab.cutoff(0) in 20.0..22.0)
        // Each band's upper edge lies above its own centre.
        for (i in 0 until EffectsLab.BANDS - 1) assertTrue(EffectsLab.cutoff(i) < EffectsLab.cutoff(i + 1))
    }

    @Test fun programHasTheSegmentsTheAnalysisExpects() {
        val runs = EffectsLab.program()
        assertEquals(11, runs.size)
        assertEquals(50, runs.sumOf { it.segs.size })
        // Every bass comparison is a flat reference followed by its +6 dB boost at the same frequency.
        val bass = runs.filter { it.label.startsWith("bass_block_") }
        assertEquals(4, bass.size)
        bass.forEach { run ->
            run.segs.chunked(2).forEach { (flat, boost) ->
                assertTrue(flat.label.startsWith("flat_"))
                assertEquals(flat.label.removePrefix("flat_"), boost.label.substringAfter("boost_").substringBefore("_"))
                assertEquals(setOf(EffectsLab.bandFor(flat.tone.hz)), boost.bands.keys)
            }
        }
    }

    @Test fun toneFramesAreStereoAndBurstGated() {
        val tone = EffectsLab.toneFrames(EffectsLab.Tone(1000.0, 0.5, 0.1))
        assertEquals(4800 * 2, tone.size)
        for (n in 0 until 4800) assertEquals(tone[2 * n].toDouble(), tone[2 * n + 1].toDouble(), 0.0)
        // 5 ms bursts every 300 ms: the first 240 frames of each period carry sound, the rest are silent.
        val bursts = EffectsLab.toneFrames(EffectsLab.Tone(1000.0, 0.9, 0.6, burstMs = 5.0, periodMs = 300.0))
        for (n in 240 until 14400) assertEquals("frame $n", 0.0, bursts[2 * n].toDouble(), 0.0)
        assertTrue(bursts.take(2 * 240).any { it != 0f })
        assertTrue(bursts.drop(2 * 14400).take(2 * 240).any { it != 0f })
    }
}
