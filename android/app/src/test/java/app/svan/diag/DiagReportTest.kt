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
        assertEquals(2, j.getInt("format"))
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

    // ---- engine pipeline in the report

    private val engineStages = Pipeline.evaluate(DiagTestFacts.facts(detection = DiagTestFacts.onEngineB(), env = DiagTestFacts.env().copy(reportAccess = false)),
        DiagTestFacts.engine(samples = DiagTestFacts.windows(3) { DiagTestFacts.window(DiagTestFacts.NOW - 2_000L * (3 - it), captured = 0) }))
    private val withEngine = extras.copy(
        pipeline = engineStages, engineEvents = DiagTestFacts.healthyEvents, engineRunStartedMs = 0L,
        engineSamples = DiagTestFacts.windows(), unobservable = listOf("The audio server's capture flags for the player's stream."),
        allRoutes = listOf("com.spotify.music session 1 uid 10520 → ENGINE_B_MUTED playing=true"),
    )

    @Test fun summaryShowsEveryStageAndTheFirstBrokenOne() {
        val s = DiagReport.summary(failing, findings, withEngine)
        assertTrue(s.contains("PIPELINE"))
        assertTrue(s.contains("C8  [FAIL] Frames arrive from the recorder"))
        assertTrue(s.contains("D5  [N/A ]"))
        assertTrue(s.contains("First broken stage: C8"))
        assertTrue(s.contains("Not observable on this phone without enhanced reports: 1 item(s)"))
        assertTrue(s.length < 6_000)
    }

    @Test fun fullReportCarriesTimelineWindowsAndTheUnobservableList() {
        val full = DiagReport.full(failing, findings, withEngine)
        listOf("== PIPELINE, STAGE BY STAGE ==", "== NOT OBSERVABLE ON THIS PHONE", "== ALL ROUTED PLAYERS ==", "== ENGINE TIMELINE",
            "== ENGINE WINDOWS", "route: com.spotify.music (session 42225) → Engine B", "A clean result above says nothing about these.",
            "(evidence: enhanced reports)").forEach { assertTrue(it, full.contains(it)) }
    }

    @Test fun reportWithoutAnEngineStillRenders() {
        assertTrue(DiagReport.full(failing, findings, extras).contains("none: the audiophile engine produced no window in this process"))
    }

    @Test fun jsonCarriesThePipelineAndWindows() {
        val j = DiagReport.json(failing, findings, withEngine)
        assertEquals("C8", j.getString("firstBrokenStage"))
        assertEquals(engineStages.size, j.getJSONArray("pipeline").length())
        assertEquals(3, j.getJSONArray("engineWindows").length().coerceAtMost(3))
        assertEquals(1, j.getJSONArray("unobservable").length())
    }
}
