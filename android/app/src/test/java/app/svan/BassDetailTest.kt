package app.svan

import android.media.AudioDeviceInfo
import app.svan.model.AudioSettings
import app.svan.model.EqState
import app.svan.model.SmartLayer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BassDetailTest {
    private val withBody = EqState(smart = SmartLayer(bands = emptyList(), preampDb = 0.0, groundingBody = 0.6))

    @Test fun experimentsAreOffByDefaultAndTheTextureFollowsTheHouseBodyAndRoute() {
        val l = BassDetail.levels(AudioSettings(), withBody, routeFactor = 0.7)
        assertEquals(0.42, l.texture, 1e-9)
        assertEquals(BassDetail.Levels.OFF.copy(texture = l.texture), l)
        assertEquals(0.0, BassDetail.levels(AudioSettings(), EqState(), 1.0).texture, 0.0)
    }

    @Test fun eachExperimentMapsToItsOwnControl() {
        val all = AudioSettings(bassAttack = true, bassDimension = true, bassSustain = true, bassTube = true)
        val l = BassDetail.levels(all, EqState(), routeFactor = 1.0)
        assertEquals(1.0, l.attack, 0.0); assertEquals(1.0, l.spread, 0.0)
        assertEquals(1.0, l.sustain, 0.0); assertEquals(1.0, l.evenMix, 0.0)
        // Tube and dimension act on the harmonics, so they bring the house texture even without Svaresa.
        assertEquals(BassDetail.HOUSE_TEXTURE, l.texture, 1e-9)
        val attackOnly = BassDetail.levels(AudioSettings(bassAttack = true), EqState(), 1.0)
        assertEquals(0.0, attackOnly.texture, 0.0)
    }

    @Test fun bypassedEqTurnsEverythingOff() {
        val all = AudioSettings(bassAttack = true, bassDimension = true, bassSustain = true, bassTube = true)
        assertEquals(BassDetail.Levels.OFF, BassDetail.levels(all, withBody.copy(enabled = false), 1.0))
    }

    @Test fun speakerGetsTheFullTextureAndOtherOutputsStartLower() {
        assertEquals(1.0, BassDetail.routeFactor(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), 0.0)
        assertEquals(0.7, BassDetail.routeFactor(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP), 0.0)
        assertEquals(0.7, BassDetail.routeFactor(AudioDeviceInfo.TYPE_WIRED_HEADPHONES), 0.0)
        assertTrue(BassDetail.routeFactor(null) in 0.7..1.0)
    }

    @Test fun analogueTopIsOffByDefaultAndFollowsTheSwitchAndTheEq() {
        assertEquals(0.0, BassDetail.analogTop(AudioSettings(), EqState()), 0.0)
        assertEquals(1.0, BassDetail.analogTop(AudioSettings(analogTop = true), EqState()), 0.0)
        assertEquals(0.0, BassDetail.analogTop(AudioSettings(analogTop = true), EqState(enabled = false)), 0.0)
        assertTrue(AudioSettings().withLiveCaptureControls(AudioSettings(analogTop = true)).analogTop)
        assertEquals(0.0, BassDetail.expression(AudioSettings(), EqState()), 0.0)
        assertEquals(1.0, BassDetail.expression(AudioSettings(expression = true), EqState()), 0.0)
        assertTrue(AudioSettings().withLiveCaptureControls(AudioSettings(expression = true)).expression)
        val saved = AudioSettings(expression = true, analogTop = true)
        assertEquals(saved, AudioSettings.fromJson(org.json.JSONObject(saved.toJson().toString())))
    }

    @Test fun bassExperimentsSurviveSavingAndApplyLive() {
        val s = AudioSettings(bassAttack = true, bassSustain = true)
        assertEquals(s, AudioSettings.fromJson(JSONObject(s.toJson().toString())))
        assertEquals(AudioSettings(), AudioSettings.fromJson(JSONObject(AudioSettings().toJson().toString().replace("\"bassAttack\":false,", ""))))
        val live = AudioSettings().withLiveCaptureControls(AudioSettings(bassDimension = true, bassTube = true))
        assertTrue(live.bassDimension && live.bassTube)
    }
}
