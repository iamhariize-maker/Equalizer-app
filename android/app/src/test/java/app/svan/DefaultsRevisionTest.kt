package app.svan

import app.svan.model.AudioSettings
import app.svan.svaramanas.SmartRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.5.14 defaults: the output-mix fallback and selective dynamic EQ start off, once, even for older saves and backups. */
class DefaultsRevisionTest {
    @Test fun freshInstallsStartWithBothOff() {
        assertFalse(AudioSettings().wholeMixFallback)
        assertFalse(SmartRequest().selectiveEq)
    }

    @Test fun anOlderSaveWithTheOldDefaultsStartsOffOnce() {
        val oldSettings = JSONObject(AudioSettings().toJson().toString()).put("mixFallback", true).apply { remove("defaults") }
        val oldRequest = JSONObject(SmartRequest().toJson().toString()).put("selectiveEq", true).apply { remove("defaults") }
        assertFalse(AudioSettings.fromJson(oldSettings).wholeMixFallback)
        assertFalse(SmartRequest.fromJson(oldRequest).selectiveEq)
    }

    @Test fun choicesMadeAfterTheMigrationAreKept() {
        val settings = AudioSettings(wholeMixFallback = true)
        val request = SmartRequest(selectiveEq = true)
        assertTrue(AudioSettings.fromJson(JSONObject(settings.toJson().toString())).wholeMixFallback)
        assertTrue(SmartRequest.fromJson(JSONObject(request.toJson().toString())).selectiveEq)
        assertEquals(AudioSettings.DEFAULTS_REVISION, settings.toJson().getInt("defaults"))
        assertEquals(SmartRequest.DEFAULTS_REVISION, request.toJson().getInt("defaults"))
    }

    @Test fun theFallbackNeverRunsDuringACall() {
        assertTrue(MixFallbackPolicy.callActive(android.media.AudioManager.MODE_IN_CALL))
        assertTrue(MixFallbackPolicy.callActive(android.media.AudioManager.MODE_IN_COMMUNICATION))
        assertFalse(MixFallbackPolicy.callActive(android.media.AudioManager.MODE_NORMAL))
    }
}
