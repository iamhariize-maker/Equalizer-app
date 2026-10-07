package app.svan.svaramanas

import app.svan.model.BassTuner
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PolicyRulesTest {
    @Test fun kotlinBoundsMatchTheGeneratedRegistryMatrix() {
        val file = listOf(File("../../docs/audio-quality/POLICY_RULES.md"), File("../docs/audio-quality/POLICY_RULES.md"), File("docs/audio-quality/POLICY_RULES.md"))
            .firstOrNull(File::exists) ?: error("Generated policy matrix must be available during tests")
        val bounds = file.readLines().filter { it.startsWith("| SV-") }.associate { line ->
            val cells = line.split('|').map(String::trim)
            val range = Regex("\\[(-?[0-9.]+), (-?[0-9.]+)\\]").find(cells[9]) ?: error("Missing range in $line")
            cells[1] to (range.groupValues[1].toDouble() to range.groupValues[2].toDouble())
        }
        assertEquals(0.0 to SvaresaBrain.MAX_BASS_DB, bounds["SV-QUIET-1"])
        assertTrue(SvaresaBrain.MAX_TREBLE_DB <= bounds.getValue("SV-QUIET-1").second)
        assertEquals(0.0 to SvaresaBrain.SPEAKER_MAX_BASS_DB, bounds["SV-SPEAKER-1"])
        assertEquals(SvaresaBrain.NIGHT_SUB_DB to 0.0, bounds["SV-NIGHT-1"])
        assertTrue(SvaresaBrain.NIGHT_PRESENCE_DB >= bounds.getValue("SV-NIGHT-1").first)
        assertTrue(BassTuner.AUTO_RESOLVE in bounds.getValue("SV-RESOLVE-1").let { it.first..it.second })
        assertEquals(0.6, BassTuner.AUTO_RESOLVE, 0.0)
    }

    @Test fun readsNativeJsonContractAndRejectsInvalidBounds() {
        val rule = """[{"id":"SV-TEST-1","version":1,"owner":"context","inputs":[{"name":"volume","units":"index","proxy":true}],"min":0,"max":6,"units":"dB","parameter":"bass","minConfidence":0,"maxAgeSeconds":5,"sameEpoch":false,"nativeOnly":false,"reason":"quiet","competes":"shared","rollback":"neutral"}]"""
        val parsed = PolicyRule.parse(rule).single()
        assertEquals("volume [index] (proxy)", parsed.inputs.single())
        assertEquals(6.0, parsed.max, 0.0)
        assertFalse(parsed.nativeOnly)
        try { PolicyRule.parse(rule.replace("\"max\":6", "\"max\":-1")); fail("invalid range accepted") }
        catch (_: IllegalArgumentException) { }
    }
}
