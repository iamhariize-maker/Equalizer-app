package app.svan.model
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class InstrumentDetailTest {
    @Test fun detailsSurvivePersistenceAndAutomaticGuidance() {
        val detail=InstrumentTuner(.2,.3,.4,.5)
        assertEquals(detail,InstrumentTuner.fromJson(JSONObject(detail.toJson().toString())))
        val eq=EqState(instrument=detail,smart=SmartLayer(bands=emptyList(),preampDb=0.0,space=.8,instruments=.9))
        assertEquals(.4,eq.activeInstrument.backingVocals,0.0)
        assertEquals(.5,eq.activeInstrument.spatialDetail,0.0)
        assertTrue(eq.copy(enabled=false).activeInstrument.isOff)
        assertFalse(detail.isOff)
    }
    @Test fun oldSettingsMigrateToNeutralAndMalformedValuesStayBounded() {
        assertEquals(InstrumentTuner(.5,.2),InstrumentTuner.fromJson(JSONObject("{\"space\":0.5,\"inst\":0.2}")))
        val p=InstrumentTuner.fromJson(JSONObject("{\"space\":-4,\"backingVocals\":20,\"spatialDetail\":-5}"))
        assertEquals(-1.0,p.space,0.0);assertEquals(1.0,p.backingVocals,0.0);assertEquals(0.0,p.spatialDetail,0.0)
    }
}
