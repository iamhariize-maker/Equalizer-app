package app.svan.model
import app.svan.NativeEngine.FilterType
import org.junit.Assert.*
import org.junit.Test

class SmartEqControlTest {
    private val manual = listOf(Band(freqHz = 1000.0, gainDb = 8.0))
    private val automatic = listOf(Band(freqHz = 300.0, gainDb = -2.0))
    private fun state() = EqState(bands = manual, smart = SmartLayer(automatic, -1.0), smartEqControl = true)
    @Test fun automatedEqReplacesManualInsteadOfStackingItTwice() {
        assertEquals(automatic, state().effectiveBands())
        assertEquals(manual, state().manualBands())
    }
    @Test fun leavingAutomaticRestoresTheUntouchedManualCurve() {
        val s = state().copy(smartEqControl = false)
        assertEquals(manual + automatic, s.effectiveBands()) // existing guide remains an independent layer
    }
    @Test fun comparingAutoDoesNotWakeTheHiddenManualBoost() {
        assertTrue(state().copy(smartBypass = true).effectiveBands().isEmpty())
        assertEquals(0.0, state().copy(smartBypass = true).effectivePreampDb(), 0.0)
    }
    @Test fun automaticEqPreservesHeadphoneAndBassLayers() {
        val tuning = Tuning(headphone="test",source="test",signature="test",bands=listOf(Band(gainDb=1.0)),fitRmsDb=0.0)
        val s = state().copy(tuning=tuning,bass=BassTuner(1.0))
        assertEquals(tuning.bands + s.bass.bands() + automatic, s.effectiveBands())
    }
    @Test fun graphicEndpointsAreShelvesForNewLayoutsButLegacyRemainsAvailable() {
        val gains = List(10) { 1.0 }
        assertEquals(FilterType.LOW_SHELF, GraphicLayout.bands(10,gains,true).first().type)
        assertEquals(FilterType.HIGH_SHELF, GraphicLayout.bands(10,gains,true).last().type)
        assertTrue(GraphicLayout.bands(10,gains,false).all { it.type == FilterType.PEAK })
    }
    @Test fun personalOffsetsAreFiniteBoundedAndKeyedByBandIdentity() {
        val b = automatic.first()
        val s = state().copy(smartEqOffsets=mapOf(EqState.smartBandKey(b) to 99.0))
        assertEquals(1.0,s.personalizeSmartBands(automatic).first().gainDb,0.0)
        assertEquals(automatic,s.personalizeSmartBands(listOf(b.copy(freqHz=400.0))).map { it.copy(freqHz=300.0) })
        assertEquals(-2.0,s.copy(smartEqOffsets=mapOf(EqState.smartBandKey(b) to Double.NaN)).personalizeSmartBands(automatic).first().gainDb,0.0)
    }
    @Test fun disabledEqDoesNotApplyAutomaticBandsOrTrim() {
        assertTrue(state().copy(enabled=false).effectiveBands().isEmpty())
        assertEquals(0.0,state().copy(enabled=false).effectivePreampDb(),0.0)
    }    @Test fun automaticLayoutsDoNotChangeTheSavedManualModeOrCount() {
        val s=state().copy(mode=EqMode.GRAPHIC,graphicCount=15,graphicGains=List(15) { 2.0 },smartEqMode=EqMode.PARAMETRIC,smartGraphicCount=64)
        assertEquals(EqMode.PARAMETRIC,s.workspaceMode)
        assertEquals(64,s.workspaceGraphicCount)
        val restored=s.copy(smartEqControl=false)
        assertEquals(EqMode.GRAPHIC,restored.workspaceMode)
        assertEquals(15,restored.workspaceGraphicCount)
        assertEquals(s.manualBands(),restored.manualBands())
    }
    @Test fun offsetsDoNotLeakBetweenGraphicLayoutsOrIntoParametric() {
        val band=automatic.first()
        val key="g31:${EqState.smartBandKey(band)}"
        val s=state().copy(smartEqMode=EqMode.GRAPHIC,smartGraphicCount=31,smartEqOffsets=mapOf(key to 1.0))
        assertEquals(-1.0,s.personalizeSmartBands(automatic).first().gainDb,0.0)
        assertEquals(-2.0,s.copy(smartGraphicCount=64).personalizeSmartBands(automatic).first().gainDb,0.0)
        assertEquals(-2.0,s.copy(smartEqMode=EqMode.PARAMETRIC).personalizeSmartBands(automatic).first().gainDb,0.0)
    }

}
