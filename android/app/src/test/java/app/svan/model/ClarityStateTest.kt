package app.svan.model

import org.junit.Assert.*
import org.junit.Test

class ClarityStateTest {
    private fun shaped() = EqState(enabled = false, preampDb = -12.0,
        tuning = Tuning(headphone = "Test", source = "Test", signature = "Test", bands = listOf(Band(gainDb = 6.0)), fitRmsDb = 0.0),
        bass = BassTuner(6.0, 80.0, 1.0), vocal = VocalTuner(1.0, 1.0, 1.0), instrument = InstrumentTuner(1.0, 1.0))

    @Test fun builtInFlatClearsAllSoundLayers() {
        val flat = shaped().withPreset(Preset.BUILT_IN.first { it.name == "Flat" })
        assertEquals(EqState(), flat)
        assertTrue(flat.effectiveBands().all { it.gainDb == 0.0 })
        assertTrue(flat.activeVocal.isOff && flat.activeInstrument.isOff)
    }

    @Test fun otherPresetsPreserveIndependentLayers() {
        val before = shaped()
        val after = before.withPreset(Preset("My EQ", -3.0, listOf(Band(gainDb = 3.0))))
        assertEquals(before.tuning, after.tuning)
        assertEquals(before.bass, after.bass)
        assertTrue(after.enabled)
        assertEquals(-3.0, after.preampDb, 0.0)
    }

    @Test fun freshSettingsUseSystemEffectsAndFloatOutput() {
        assertEquals(EngineMode.SYSTEM_ONLY, AudioSettings().engineMode)
        assertEquals(DitherChoice.OFF, AudioSettings().dither)
        assertEquals(40, AudioSettings().systemFrameMs)
    }
}
