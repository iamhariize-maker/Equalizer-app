package app.svan.listening

import app.svan.SettingsBackup
import app.svan.model.*
import app.svan.svaramanas.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SettingsBackupTest {
    private fun snapshot()=SettingsBackup.Snapshot(
        EqState(preampDb=-2.0,smartEqControl=true,smartEqOffsets=mapOf("PEAK:1000.0:1.0" to .5)),
        AudioSettings(quality=QualityMode.EXTREME),SmartRequest(enabled=true,mode=SmartMode.SVARESA,selectiveEq=false),
        listOf(Preset("Personal",-3.0,listOf(Band(freqHz=300.0,gainDb=-1.0)))),mapOf(SettingsBackup.hash("20 0\n20000 0") to "20 0\n20000 0"))
    @Test fun settingsAndCalibrationSurviveMigration() {
        val s=snapshot();val r=SettingsBackup.decode(s.encode())
        assertEquals(s,r);assertFalse(r.request.selectiveEq)
        assertNull(r.eq.smart)
    }
    @Test fun corruptedCurveAndUnsafeFiltersAreRejectedBeforeRestore() {
        val o=JSONObject(snapshot().encode());o.getJSONObject("curves").put(snapshot().curves.keys.first(),"corrupted")
        assertTrue(runCatching {SettingsBackup.decode(o.toString())}.isFailure)
        val p=JSONObject(snapshot().encode());p.getJSONObject("eq").getJSONArray("bands").getJSONObject(0).put("q",0)
        assertTrue(runCatching {SettingsBackup.decode(p.toString())}.isFailure)
        assertTrue(runCatching {SettingsBackup.decode("{\"format\":\"other\",\"version\":1}")}.isFailure)
    }
}
