package app.svan.diag

import app.svan.diag.DiagTestFacts.announcements
import app.svan.diag.DiagTestFacts.client
import app.svan.diag.DiagTestFacts.codes
import app.svan.diag.DiagTestFacts.detection
import app.svan.diag.DiagTestFacts.env
import app.svan.diag.DiagTestFacts.facts
import app.svan.diag.DiagTestFacts.lab
import app.svan.diag.DiagTestFacts.policy
import app.svan.diag.DiagTestFacts.trial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagRulesTest {
    private fun eval(f: DiagFacts) = DiagRules.evaluate(f, 0)
    private fun sev(findings: List<Finding>, code: String) = findings.first { it.code == code }.severity
    private val silentExcept = { heard: Set<String> -> DiagTestFacts.allSilent.map { if (it.id in heard) trial(it.id, true) else it } }

    // ---------------------------------------------------------------- detection

    private val noReports = env().copy(reportAccess = false, dumpGranted = false, shizukuRunning = false, shizukuPermitted = false)
    private val unseen = detection(announcements(0, 0), routes = emptyList())

    @Test fun otherAppsAnnounceButTheTargetNeverDoes() {
        val f = facts(env = noReports, detection = unseen.copy(otherAnnouncers = mapOf("org.videolan.vlc" to 2)))
        val r = eval(f)
        assertTrue("TARGET_NEVER_ANNOUNCED" in codes(r)); assertEquals(Severity.FAIL, sev(r, "TARGET_NEVER_ANNOUNCED"))
        assertTrue(r.first { it.code == "TARGET_NEVER_ANNOUNCED" }.evidence.any { it.contains("org.videolan.vlc") })
    }

    @Test fun nobodyAnnouncesButReceivingWorks() {
        val r = eval(facts(env = noReports, detection = unseen))
        assertTrue("NO_ANNOUNCEMENTS_AT_ALL" in codes(r))
        assertTrue(r.first { it.code == "NO_ANNOUNCEMENTS_AT_ALL" }.advice.any { it.contains("restart the player", ignoreCase = true) })
    }

    @Test fun aSelfTestThatNeverArrivesMeansBroadcastsAreBlockedHere() {
        val r = eval(facts(env = noReports, detection = unseen.copy(receiverSelfTestRan = true, receiverSelfTestMs = null)))
        assertTrue("RECEIVER_DEAF" in codes(r)); assertFalse("NO_ANNOUNCEMENTS_AT_ALL" in codes(r))
        assertTrue(r.first { it.code == "RECEIVER_DEAF" }.advice.any { it.contains("HiOS") })
    }

    @Test fun aSelfTestThatWasNotRunIsNeverReportedAsABrokenReceiver() {
        val r = eval(facts(env = noReports, detection = unseen.copy(receiverSelfTestRan = false, receiverSelfTestMs = null)))
        assertFalse("RECEIVER_DEAF" in codes(r))
    }

    @Test fun droppedAnnouncementsExplainTheirReason() {
        val a = announcements(opens = 2, accepted = 0, dropped = 2, reasons = listOf("Svan's service was not running" to 2))
        val r = eval(facts(env = noReports, detection = detection(a, routes = emptyList())))
        val f = r.first { it.code == "ANNOUNCEMENT_DROPPED" }
        assertEquals(Severity.FAIL, f.severity); assertTrue(f.evidence.any { it.contains("service was not running") })
    }

    @Test fun enhancedAccessThatCannotReadReportsIsFlagged() {
        val r = eval(facts(detection = detection().copy(reportsReadable = false)))
        assertTrue("REPORTS_UNREADABLE" in codes(r))
    }

    @Test fun developerOptionsOffWithoutEnhancedAccessIsExplainedNotBlamedOnThePlayer() {
        val r = eval(facts(env = noReports.copy(developerOptionsOn = false), detection = unseen))
        assertEquals(Severity.INFO, sev(r, "ENHANCED_DETECTION_OFF"))
    }

    @Test fun aHiddenPlayerIsSeenThroughThePublicCount() {
        val r = eval(facts(env = noReports, detection = unseen.copy(publicActiveMedia = 1)))
        assertTrue("HIDDEN_PLAYER" in codes(r))
    }

    @Test fun oemBackgroundLimitsAreOnlyReportedWhenSvanIsNotExempt() {
        val limited = env().copy(batteryOptimizationIgnored = false)
        assertTrue("OEM_BACKGROUND_LIMITS" in codes(eval(facts(env = limited))))
        assertFalse("OEM_BACKGROUND_LIMITS" in codes(eval(facts(env = env()))))
        val stock = limited.copy(oem = Oem("Pixel", false, emptyList()))
        assertFalse("OEM_BACKGROUND_LIMITS" in codes(eval(facts(env = stock))))
    }

    @Test fun aStoppedServiceIsAFailure() {
        assertEquals(Severity.FAIL, sev(eval(facts(env = env().copy(serviceRunning = false, serviceUptimeMs = null))), "SERVICE_NOT_RUNNING"))
    }

    @Test fun anUninstalledTargetStopsEarly() {
        val r = eval(facts(target = TargetFacts("x.y", null, false)))
        assertTrue("TARGET_NOT_VISIBLE" in codes(r))
    }

    // ---------------------------------------------------------------- capture, static evidence

    @Test fun aNativeStreamOptOutIsFoundEvenWhenAndroidsPlayerListShowsNone() {
        val blocking = client(flags = 0x1400)
        val r = eval(facts(policy = policy(blocking), detection = detection().copy(audioServiceFlags = 0)))
        val f = r.first { it.code == "STREAM_OPTS_OUT" }
        assertEquals(Severity.FAIL, f.severity); assertTrue(f.title.contains("player list does not show it"))
    }

    @Test fun aUidWideOptOutIsFound() {
        assertTrue("UID_POLICY_BLOCKS_CAPTURE" in codes(eval(facts(policy = policy(mask = 0x1400)))))
    }

    @Test fun aManifestOptOutIsFound() {
        assertTrue("MANIFEST_DISABLES_CAPTURE" in codes(eval(facts(target = DiagTestFacts.target().copy(manifestAllows = false)))))
    }

    // ---------------------------------------------------------------- capture lab

    @Test fun currentSettingsHearingAudioIsAGoodResult() {
        val r = eval(facts(lab = lab(silentExcept(setOf(TrialIds.UID_MEDIA_F32)))))
        assertEquals(Severity.OK, sev(r, "CAPTURE_WORKS"))
    }

    @Test fun onlyAWiderUsageRuleHearingItMeansTheFilterMissesTheStream() {
        val r = eval(facts(lab = lab(silentExcept(setOf(TrialIds.UID_ANY_F32)), live = policy(client()))))
        assertEquals(Severity.FAIL, sev(r, "USAGE_FILTER_MISSES_STREAM"))
        assertTrue(r.first { it.code == "USAGE_FILTER_MISSES_STREAM" }.evidence.any { it.contains("AUDIO_USAGE_MEDIA") })
    }

    @Test fun onlyAnotherFormatHearingItIsFormatSensitive() {
        assertTrue("FORMAT_SENSITIVE" in codes(eval(facts(lab = lab(silentExcept(setOf(TrialIds.UID_MEDIA_S16)))))))
    }

    @Test fun onlyTheMuteFirstOrderHearingItIsReported() {
        val trials = silentExcept(emptySet()) + trial(TrialIds.UID_ANY_EFFECT_OFF, false) + trial(TrialIds.UID_ANY_MUTED, true)
        assertTrue("CAPTURE_ONLY_AFTER_MUTE" in codes(eval(facts(lab = lab(trials)))))
    }

    @Test fun onlyDetachingSvansEffectHearingItIsReported() {
        val trials = silentExcept(emptySet()) + trial(TrialIds.UID_ANY_EFFECT_OFF, true) + trial(TrialIds.UID_ANY_MUTED, true)
        assertTrue("CAPTURE_ONLY_WITHOUT_EFFECT" in codes(eval(facts(lab = lab(trials)))))
    }

    @Test fun broadCaptureHearingAudioButNotTheUidFilterIsAUidFilterProblem() {
        val r = eval(facts(lab = lab(silentExcept(setOf(TrialIds.ALL_MEDIA_F32)))))
        assertEquals(Severity.FAIL, sev(r, "UID_FILTER_MISSES_STREAM"))
    }

    @Test fun otherPlayersAreMentionedWhenBroadCaptureMightHaveHeardThem() {
        val r = eval(facts(lab = lab(silentExcept(setOf(TrialIds.ALL_MEDIA_F32)), others = 2)))
        assertTrue(r.first { it.code == "UID_FILTER_MISSES_STREAM" }.evidence.any { it.contains("other media players") })
    }

    @Test fun silenceWithTheStreamNotAttachedToTheCaptureMixPointsAtTheAudioServer() {
        val r = eval(facts(lab = lab(DiagTestFacts.allSilent, live = policy(client(flags = 0, secondary = emptyList())))))
        val f = r.first { it.code == "NOT_ATTACHED_TO_CAPTURE_MIX" }
        assertEquals(Severity.FAIL, f.severity); assertTrue(f.advice.any { it.contains("vendor") })
    }

    @Test fun silenceWithTheStreamAttachedPointsAtTheRecorderSide() {
        val r = eval(facts(lab = lab(DiagTestFacts.allSilent, live = policy(client(flags = 0, secondary = listOf(91))))))
        assertTrue("ATTACHED_BUT_SILENT" in codes(r))
    }

    @Test fun silenceWithAStreamThatForbidsCaptureNamesTheOptOut() {
        val r = eval(facts(lab = lab(DiagTestFacts.allSilent, live = policy(client(flags = 0x1000)))))
        assertTrue("STREAM_OPTS_OUT" in codes(r))
    }

    @Test fun silenceWithNoPolicyClientIsItsOwnFinding() {
        val r = eval(facts(lab = lab(DiagTestFacts.allSilent, live = policy())))
        assertTrue("NO_POLICY_CLIENT" in codes(r))
    }

    @Test fun silenceWithoutTheAudioServerViewSaysSo() {
        val r = eval(facts(lab = lab(DiagTestFacts.allSilent, live = policy(readable = false))))
        assertTrue("ALL_CAPTURE_SILENT" in codes(r))
    }

    @Test fun aRecorderThatAndroidSilencedIsCalledOut() {
        val trials = DiagTestFacts.allSilent.map { it.copy(silencedSeen = setOf("true")) }
        assertTrue("RECORDER_SILENCED" in codes(eval(facts(lab = lab(trials, live = policy(client()))))))
    }

    @Test fun silenceWhileThePlayerWasNotPlayingProvesNothing() {
        val r = eval(facts(lab = lab(DiagTestFacts.allSilent, playing = false)))
        assertTrue("LAB_PLAYER_NOT_PLAYING" in codes(r)); assertFalse("NOT_ATTACHED_TO_CAPTURE_MIX" in codes(r))
    }

    @Test fun noRecorderAtAllIsReportedWithAndroidsReason() {
        val trials = DiagTestFacts.allSilent.map { it.copy(started = false, error = "SecurityException: nope") }
        val f = eval(facts(lab = lab(trials))).first { it.code == "LAB_NO_RECORDER" }
        assertTrue(f.evidence.any { it.contains("SecurityException") })
    }

    @Test fun aLabThatDidNotRunSaysWhy() {
        val r = eval(facts(lab = LabFacts(false, "The audiophile engine is not running", null, null, null, emptyList(), null, emptyList(), emptyList())))
        assertTrue(r.first { it.code == "LAB_NOT_RUN" }.evidence.single().contains("not running"))
    }

    // ---------------------------------------------------------------- effects and ordering

    @Test fun verifiedSystemEffectsAreReportedAsGood() {
        assertEquals(Severity.OK, sev(eval(facts()), "EFFECT_VERIFIED"))
    }

    @Test fun anUnattachedEffectIsAFailure() {
        val r = eval(facts(detection = detection(routes = listOf(RouteFacts(1, "UNPROCESSED", true, null)))))
        assertTrue("EFFECT_NOT_ATTACHED" in codes(r))
    }

    @Test fun bypassPathsAreFlagged() {
        assertTrue("BYPASS_PATH" in codes(eval(facts(detection = detection().copy(path = "offload")))))
    }

    @Test fun findingsAreOrderedWorstFirstAndTheHeadlineNamesTheWorst() {
        val r = eval(facts(env = env().copy(batteryOptimizationIgnored = false), lab = lab(DiagTestFacts.allSilent, live = policy(client()))))
        assertEquals(Severity.FAIL, r.first().severity)
        assertTrue(DiagRules.headline(r).startsWith("FAIL"))
    }

    @Test fun aCleanRunSaysNothingWasFound() {
        assertEquals("No fault found in what was measured.", DiagRules.headline(listOf(Finding(Severity.OK, "X", "fine"))))
    }

    @Test fun twoSightingsOfTheSameFaultBecomeOneFindingWithAllEvidence() {
        val a = Finding(Severity.FAIL, "X", "first", listOf("e1"), listOf("a1"))
        val b = Finding(Severity.WARN, "X", "second", listOf("e1", "e2"), listOf("a1", "a2"))
        val merged = DiagRules.mergeSameCode(listOf(a, b, Finding(Severity.OK, "Y", "y")))
        assertEquals(2, merged.size)
        val x = merged.first { it.code == "X" }
        assertEquals("first", x.title); assertEquals(Severity.FAIL, x.severity)
        assertEquals(listOf("e1", "e2"), x.evidence); assertEquals(listOf("a1", "a2"), x.advice)
    }

    @Test fun theVerdictNamesTheMeasuredCauseBeforeAPipelineSymptomOfTheSameSeverity() {
        val cause = Finding(Severity.FAIL, "STREAM_OPTS_OUT", "The audio server marks this player's stream as not capturable")
        val symptom = Finding(Severity.FAIL, "CAPTURE_CHECK_NEGATIVE", "C6 Capture check verdict for the player")
        val combined = DiagRules.combine(listOf(cause), listOf(symptom))
        assertTrue(DiagRules.headline(combined).contains("not capturable"))
        assertEquals(Severity.FAIL, DiagRules.combine(listOf(Finding(Severity.WARN, "W", "w")), listOf(symptom)).first().severity)
    }
}
