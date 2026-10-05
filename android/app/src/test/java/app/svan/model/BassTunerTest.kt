package app.svan.model

import app.svan.NativeEngine.FilterType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BassTunerTest {
    @Test
    fun offAddsNoBands() = assertTrue(BassTuner().bands().isEmpty())

    @Test
    fun punchCutsMudSustainAddsBloom() {
        val punch = BassTuner(4.0, 80.0, 1.0).bands()
        assertEquals(FilterType.LOW_SHELF, punch[0].type)
        assertTrue(punch.any { it.type == FilterType.PEAK && it.freqHz == 250.0 && it.gainDb < 0 })
        val sustain = BassTuner(4.0, 80.0, -1.0).bands()
        assertTrue(sustain.any { it.type == FilterType.PEAK && it.gainDb > 0 })
    }

    @Test
    fun starterBassPresetsUseSmallBoostsAndOfferCleanImpactShape() {
        assertTrue(BassTuner.PRESETS.filter { it.first != "Off" }.all { it.second.amountDb in 0.0..2.5 })
        val clean = BassTuner.PRESETS.first { it.first == "Clean impact" }.second
        assertEquals(1.5, clean.amountDb, 0.0)
        assertTrue(clean.bands().any { it.type == FilterType.LOW_SHELF && it.freqHz == 75.0 && it.gainDb == 1.5 })
        assertTrue(clean.bands().any { it.type == FilterType.PEAK && it.freqHz == 250.0 && it.gainDb == -0.8 })
    }

    @Test
    fun layersStackInOrder() {
        val t = Tuning(headphone = "X", source = "s", signature = "Harman", bands = listOf(Band(gainDb = 1.0)), fitRmsDb = 0.1)
        val s = EqState(bands = listOf(Band(gainDb = 2.0)), tuning = t, bass = BassTuner(3.0, 80.0, 0.0))
        assertEquals(listOf(1.0, 2.0, 3.0), s.effectiveBands().map { it.gainDb })
        assertTrue(s.copy(enabled = false).effectiveBands().isEmpty())
        assertEquals(2, s.copy(tuning = t.copy(enabled = false)).effectiveBands().size)
    }
}
