package app.svan.listening
import org.junit.Test
import org.junit.Assert.*
import app.svan.model.*
import app.svan.svaramanas.SmartRequest

class ListeningTest {
    @Test fun calibrationAndSelectiveChoiceSurviveRestart(){val c=CorrectionCalibration(.6,.5,20.0,20000.0,"Measurement-derived target","test","target");val tuning=Tuning(true,"Fixture","Measured","Flat",emptyList(),.1,calibration=c);assertEquals(c,Tuning.fromJson(tuning.toJson()).calibration);val request=SmartRequest(selectiveEq=false);assertFalse(SmartRequest.fromJson(request.toJson()).selectiveEq)}
    @Test fun automaticDynamicEqBypassesAndPreservesManualSmoothness(){
        val smart=SmartLayer(emptyList(),0.0,smoothness=.5,dynamicEq=1.0)
        val e=EqState(smart=smart,vocal=VocalTuner(smoothness=.3))
        assertEquals(1.0,e.dynamicEq,0.0);assertEquals(.3,e.activeVocal.smoothness,0.0)
        assertEquals(.5,e.systemSmoothness,0.0) // native replacement must not remove Engine A's smoother
        assertEquals(.3,e.copy(smartBypass=true).systemSmoothness,0.0)
        assertEquals(0.0,e.copy(enabled=false).systemSmoothness,0.0)
        assertEquals(0.0,e.copy(smartBypass=true).dynamicEq,0.0);assertEquals(0.0,e.copy(enabled=false).dynamicEq,0.0)
    }
}