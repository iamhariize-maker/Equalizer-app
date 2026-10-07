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
    fun fullImpactPresetAddsControlledLowShelfAndMudCut() {
        val full = BassTuner.PRESETS.first { it.first == "Full impact" }.second
        assertEquals(2.5, full.amountDb, 0.0)
        assertEquals(68.0, full.focusHz, 0.0)
        assertTrue(full.character in 0.0..1.0)
        assertTrue(full.bands().any { it.type == FilterType.LOW_SHELF && it.freqHz == 68.0 && it.gainDb == 2.5 })
        assertTrue(full.bands().any { it.type == FilterType.PEAK && it.freqHz == 250.0 && it.gainDb < 0.0 })
    }

    @Test
    fun layersStackInOrder() {
        val t = Tuning(headphone = "X", source = "s", signature = "Harman", bands = listOf(Band(gainDb = 1.0)), fitRmsDb = 0.1)
        val s = EqState(bands = listOf(Band(gainDb = 2.0)), tuning = t, bass = BassTuner(3.0, 80.0, 0.0))
        assertEquals(listOf(1.0, 2.0, 3.0), s.effectiveBands().map { it.gainDb })
        assertTrue(s.copy(enabled = false).effectiveBands().isEmpty())
        assertEquals(2, s.copy(tuning = t.copy(enabled = false)).effectiveBands().size)
    }

    @Test
    fun oldSavedBassMigratesToResolveOffManual() {
        val old = org.json.JSONObject().put("amt", 2.0).put("focus", 70.0).put("char", 0.4)
        val b = BassTuner.fromJson(old)
        assertEquals(0.0, b.resolve, 0.0)
        assertTrue(!b.resolveAuto)
        assertEquals(2.0, b.amountDb, 0.0)
    }

    @Test
    fun resolveJsonRoundTripsAndRejectsBadValues() {
        val b = BassTuner(1.0, 80.0, 0.5, 0.35, true)
        assertEquals(b, BassTuner.fromJson(b.toJson()))
        assertEquals(1.0, BassTuner.fromJson(org.json.JSONObject().put("res", 7.0)).resolve, 0.0)
        assertEquals(0.0, BassTuner.fromJson(org.json.JSONObject().put("res", -3.0)).resolve, 0.0)
    }

    @Test
    fun autoOwnershipNeverLowersOrOverwritesTheManualValue() {
        val layer = SmartLayer(emptyList(), 0.0)
        val manual = EqState(bass = BassTuner(0.0, 80.0, 0.5, 0.2, false), smart = layer)
        assertEquals(0.2, manual.bassResolve, 0.0)                                  // Manual wins when Auto is off
        val auto = manual.copy(bass = manual.bass.copy(resolveAuto = true))
        assertEquals(BassTuner.AUTO_RESOLVE, auto.bassResolve, 0.0)                // Svaresa driving: raised
        assertEquals(0.2, auto.bass.resolve, 0.0)                                  // saved manual value untouched
        assertEquals(0.9, auto.copy(bass = auto.bass.copy(resolve = 0.9)).bassResolve, 0.0)  // never lowered
        assertEquals(0.2, auto.copy(smart = null).bassResolve, 0.0)                // Svaresa not driving: manual
        assertEquals(0.0, auto.copy(enabled = false).bassResolve, 0.0)             // EQ off: off
    }
}
