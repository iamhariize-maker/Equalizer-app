package app.svan.diag

import app.svan.diag.DiagTestFacts.client
import app.svan.diag.DiagTestFacts.facts
import app.svan.diag.DiagTestFacts.lab
import app.svan.diag.DiagTestFacts.policy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagReportTest {
    private val extras = DiagExtras(
        generatedAtMs = 1_000_000L, svanVersion = "0.5.10", device = listOf("TECNO LH7n (TECNO-LH7n)"),
        buildProps = mapOf("ro.build.version.release" to "14"), audioFacts = mapOf("output sample rate (mixer)" to "48000"),
        outputDevices = listOf("speaker · phone · rates=any"), installedEffects = listOf("Dynamics Processing · type=x · by The Android Open Source Project"),
        setupSummary = "ok", notificationsEnabled = true, processUptimeMs = 120_000, routeEvidence = listOf("session 42225 started USAGE_MEDIA"),
        captureReport = "Svan 0.5.10; Android API 34", scanSummary = "scans=3", policyTable = emptyList(), flingerStatic = emptyList(),
        ledger = listOf(SignalLedger.Event(900_000, SignalLedger.Kind.BROADCAST, "com.spotify.music", "OPEN session=1 accepted")),
        svanLog = listOf("route: x"),
    )
    private val failing = facts(lab = lab(DiagTestFacts.allSilent, live = policy(client(flags = 0, secondary = emptyList()))))
    private val findings = DiagRules.evaluate(failing, 0)

    @Test fun summaryLeadsWithTheVerdictAndTheLadder() {
        val s = DiagReport.summary(failing, findings, extras)
        assertTrue(s.contains("VERDICT: FAIL: While capturing, the audio server did not copy this player's stream"))
        assertTrue(s.contains("uid_media_f32"))
        assertTrue(s.contains("secondary outputs (capture mixes): [none]"))
        assertTrue(s.indexOf("VERDICT") < s.indexOf("Capture ladder"))
    }

    @Test fun summaryIsShortEnoughToPasteIntoAChat() {
        assertTrue(DiagReport.summary(failing, findings, extras).length < 6_000)
    }

    @Test fun fullReportCarriesRawEvidenceAndEverySection() {
        val full = DiagReport.full(failing, findings, extras)
        listOf("== FINDINGS", "== DEVICE AND BUILD ==", "== TARGET APP ==", "== DETECTION ==", "== CAPTURE LAB ==",
            "== SIGNAL TIMELINE", "== BUILD PROPERTIES", "OPEN session=1 accepted", "ro.build.version.release=14").forEach { assertTrue(it, full.contains(it)) }
    }

    @Test fun jsonHasTheVerdictFindingsAndTrials() {
        val j = DiagReport.json(failing, findings, extras)
        assertEquals(1, j.getInt("format"))
        assertTrue(j.getString("verdict").startsWith("FAIL"))
        assertEquals(7, j.getJSONArray("trials").length())
        assertTrue(j.getJSONArray("findings").length() >= 1)
        assertFalse(j.isNull("liveClient"))
    }

    @Test fun aRunWithoutTheLabStillRendersCleanly() {
        val f = facts()
        val s = DiagReport.summary(f, DiagRules.evaluate(f, 0), extras)
        assertFalse(s.contains("Capture ladder"))
        assertTrue(DiagReport.full(f, DiagRules.evaluate(f, 0), extras).contains("Did not run: not requested"))
    }
}
