package app.svan.diag

import app.svan.diag.DiagTestFacts.NOW
import app.svan.diag.DiagTestFacts.PKG
import app.svan.diag.DiagTestFacts.engine
import app.svan.diag.DiagTestFacts.ev
import app.svan.diag.DiagTestFacts.facts
import app.svan.diag.DiagTestFacts.onEngineB
import app.svan.diag.DiagTestFacts.stage
import app.svan.diag.DiagTestFacts.window
import app.svan.diag.DiagTestFacts.windows
import app.svan.diag.EngineTrace.Cat
import app.svan.diag.EngineTrace.Mark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineTest {
    private val onB = facts(detection = onEngineB())
    private fun run(e: EngineFacts, f: DiagFacts = onB) = Pipeline.evaluate(f, e)
    private fun status(s: List<Stage>, id: String) = stage(s, id).status

    @Test fun aHealthyEnginePassesEveryCaptureStageAndRaisesNoFinding() {
        val s = run(engine())
        listOf("C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8", "C9", "C10", "C11", "C12", "C13", "C14").forEach {
            assertEquals(it, StageStatus.PASS, status(s, it))
        }
        assertTrue(Pipeline.findings(s).isEmpty())
        assertNull(Pipeline.firstBroken(s))
    }

    @Test fun anEngineThatWasNeverStartedWaitsInsteadOfFailing() {
        val s = run(engine(running = false, marks = emptyMap(), events = emptyList(), samples = emptyList()), facts())
        assertEquals(StageStatus.WAITING, status(s, "C1"))
        assertEquals(StageStatus.SKIPPED, status(s, "C2"))
        assertTrue(Pipeline.findings(s).isEmpty())
    }

    @Test fun engineModeSystemEffectsOnlyIsNotAFailure() {
        val s = run(engine(running = false, systemOnly = true, marks = emptyMap(), events = emptyList(), samples = emptyList()), facts())
        assertEquals(StageStatus.SKIPPED, status(s, "C1"))
        assertTrue(Pipeline.findings(s).isEmpty())
    }

    @Test fun aBlockedStartIsNamedWithItsReason() {
        val s = run(engine(running = false, marks = emptyMap(), samples = emptyList(), startupMessage = "Turn off shared output first",
            events = listOf(ev(Cat.LIFECYCLE, "capture: start blocked — Turn off shared output first", pkg = null))), facts())
        assertEquals(StageStatus.FAIL, status(s, "C1"))
        assertTrue(stage(s, "C1").evidence.any { it.contains("shared output") })
        assertEquals("ENGINE_B_START_FAILED", Pipeline.findings(s).first().code)
    }

    @Test fun aTokenThatNeverArrivesFailsTheForegroundStage() {
        val marks = mapOf(Mark.FOREGROUND to 100L)
        val s = run(engine(marks = marks, samples = emptyList(), events = emptyList()), facts())
        assertEquals(StageStatus.FAIL, status(s, "C2"))
        assertTrue(Pipeline.findings(s).any { it.code == "PROJECTION_NOT_GRANTED" })
    }

    @Test fun startupRoutingThatNeverFinishesIsReported() {
        val marks = mapOf(Mark.FOREGROUND to 100L, Mark.PROJECTION to 400L)
        val s = run(engine(marks = marks, samples = emptyList(), events = emptyList()), facts())
        assertEquals(StageStatus.FAIL, status(s, "C3"))
    }

    @Test fun everyRejectedRateOnAStoppedEngineIsAFormatFailure() {
        val e = engine(running = false, marks = emptyMap(), samples = emptyList(),
            events = listOf(ev(Cat.RECORDER, "capture: rate 48000 rejected: capture not initialized", pkg = null),
                ev(Cat.LIFECYCLE, "capture: startup failed: java.lang.IllegalStateException", pkg = null)))
        val s = run(e, facts())
        assertEquals(StageStatus.FAIL, status(s, "C4"))
        assertTrue(Pipeline.findings(s).any { it.code == "RECORDER_FORMAT_REJECTED" })
    }

    @Test fun aPlayerParkedOnSystemEffectsNamesTheReason() {
        val parked = facts(detection = DiagTestFacts.detection(routes = listOf(RouteFacts(1, "ENGINE_A", true, "SILENT_RECENTLY"))))
        val s = run(engine(samples = emptyList()), parked)
        assertEquals(StageStatus.FAIL, status(s, "C5"))
        assertTrue(Pipeline.findings(s).any { it.code == "TARGET_NOT_ON_ENGINE_B" && it.evidence.any { e -> e.contains("SILENT_RECENTLY") } })
    }

    @Test fun transientAndChosenReasonsAreNotFailures() {
        fun c5(reason: String) = status(run(engine(samples = emptyList()),
            facts(detection = DiagTestFacts.detection(routes = listOf(RouteFacts(1, "ENGINE_A", true, reason))))), "C5")
        assertEquals(StageStatus.WAITING, c5("CHECKING"))
        assertEquals(StageStatus.WAITING, c5("WAITING_FOR_PLAYBACK"))
        assertEquals(StageStatus.SKIPPED, c5("SYSTEM_ONLY"))
    }

    @Test fun aNegativeCaptureCheckIsStatedWithItsOwnLine() {
        val e = engine(events = listOf(ev(Cat.PROBE, "capture check: $PKG → silent (no audio reached capture while it was audible); not a permanent verdict")))
        assertEquals(StageStatus.FAIL, status(run(e), "C6"))
    }

    @Test fun aRecorderThatNeverStartsAfterRoutingFails() {
        val marks = DiagTestFacts.healthyMarks - Mark.RECORDER_STARTED - Mark.FIRST_FRAME - Mark.FIRST_AUDIO - Mark.FIRST_OUTPUT
        val s = run(engine(marks = marks, samples = listOf(window(NOW - 2_000, captured = 0, state = "waiting for recorder lease"))))
        assertEquals(StageStatus.FAIL, status(s, "C7"))
        assertTrue(stage(s, "C7").evidence.any { it.contains("recorder lease") })
    }

    @Test fun zeroFramesInSeveralWindowsIsTheNoFramesFailure() {
        val s = run(engine(samples = windows(3) { window(NOW - (3 - it) * 2_000L, captured = 0, inDb = -120.0) }))
        assertEquals(StageStatus.FAIL, status(s, "C8"))
        assertEquals(StageStatus.SKIPPED, status(s, "C9"))
        assertTrue(Pipeline.findings(s).any { it.code == "NO_FRAMES_DELIVERED" })
    }

    @Test fun noWindowAtAllEightSecondsAfterTheRecorderStartedIsTheSameFailure() {
        val s = run(engine(marks = DiagTestFacts.healthyMarks + (Mark.RECORDER_STARTED to 2_000L), samples = emptyList()))
        assertEquals(StageStatus.FAIL, status(s, "C8"))
        assertTrue(stage(s, "C8").evidence.any { it.contains("delivers nothing at all") })
    }

    @Test fun framesOfDigitalSilenceFailTheSoundStage() {
        val s = run(engine(samples = windows(4) { window(NOW - (4 - it) * 2_000L, captured = 96_000, inDb = -120.0) }))
        assertEquals(StageStatus.PASS, status(s, "C8"))
        assertEquals(StageStatus.FAIL, status(s, "C9"))
        assertEquals("CAPTURE_DELIVERS_SILENCE", Pipeline.findings(s).first { it.severity == Severity.FAIL }.code)
    }

    @Test fun androidSilencingTheRecorderIsCaughtLive() {
        val s = run(engine(samples = windows(3) { window(NOW - (3 - it) * 2_000L, inDb = -120.0, silenced = true) }))
        assertEquals(StageStatus.FAIL, status(s, "C10"))
    }

    @Test fun anUnreadableSilencedFlagIsUnknownNotPassed() {
        val s = run(engine(samples = windows(3) { window(NOW - (3 - it) * 2_000L, silenced = null) }))
        assertEquals(StageStatus.UNKNOWN, status(s, "C10"))
    }

    @Test fun oneFailOpenWarnsAndTwoFail() {
        val one = listOf(ev(Cat.FAILOPEN, "fail-open: $PKG (session 1) → Engine A (30 s)"))
        assertEquals(StageStatus.WARN, status(run(engine(events = one)), "C11"))
        val two = one + ev(Cat.FAILOPEN, "fail-open: $PKG (session 1) → Engine A (60 s)", at = 40_000)
        val s = run(engine(events = two))
        assertEquals(StageStatus.FAIL, status(s, "C11"))
        assertTrue(Pipeline.findings(s).any { it.code == "FAIL_OPEN_REPEATED" })
    }

    @Test fun losingMuteControlFailsEvenOnce() {
        val s = run(engine(events = listOf(ev(Cat.MUTE, "lost mute control of $PKG (session 1): another effect app took over"))))
        assertEquals(StageStatus.FAIL, status(s, "C11"))
        assertTrue(Pipeline.findings(s).any { it.code == "MUTE_CONTROL_LOST" })
    }

    @Test fun dspLoadAndBudgetMisses() {
        assertEquals(StageStatus.WARN, status(run(engine(samples = windows(3) { window(NOW - 2_000L * (3 - it), dsp = 75.0) })), "C12"))
        assertEquals(StageStatus.FAIL, status(run(engine(samples = windows(3) { window(NOW - 2_000L * (3 - it), dsp = 95.0) })), "C12"))
        assertEquals(StageStatus.WARN, status(run(engine(samples = windows(3) { window(NOW - 2_000L * (3 - it), over = 2) })), "C12"))
    }

    @Test fun growingUnderrunsWarnThenFail() {
        val few = windows(5) { window(NOW - 2_000L * (5 - it), underruns = if (it >= 3) 4 else 0) }
        assertEquals(StageStatus.WARN, status(run(engine(samples = few)), "C13"))
        val many = windows(5) { window(NOW - 2_000L * (5 - it), underruns = it * 3) }
        assertEquals(StageStatus.FAIL, status(run(engine(samples = many)), "C13"))
    }

    @Test fun aFailedOutputWriteFailsTheOutputStage() {
        val s = run(engine(events = DiagTestFacts.healthyEvents + ev(Cat.OUTPUT, "capture: output write failed (-6)", pkg = null)))
        assertEquals(StageStatus.FAIL, status(s, "C13"))
        assertTrue(Pipeline.findings(s).any { it.code == "OUTPUT_WRITE_FAILED" })
    }

    @Test fun restartsAndAKilledPreviousProcessAreSurfaced() {
        assertEquals(StageStatus.WARN, status(run(engine(restarts = 1, recoveryMessage = "Capture read failed; retrying Fast at safe 48 kHz.")), "C14"))
        assertEquals(StageStatus.FAIL, status(run(engine(restarts = 2)), "C14"))
        val killed = EngineTrace.PreviousRun(1_000, false, listOf(ev(Cat.LIFECYCLE, "capture run 1 requested", pkg = null)))
        val s = run(engine(previous = killed))
        assertEquals(StageStatus.WARN, status(s, "C14"))
        assertTrue(Pipeline.findings(s).any { it.code == "PREVIOUS_RUN_KILLED" })
        val clean = killed.copy(cleanStop = true)
        assertEquals(StageStatus.PASS, status(run(engine(previous = clean)), "C14"))
    }

    // ---- detection chain

    @Test fun detectionStagesPassForAnAnnouncedAcceptedRoutedPlayer() {
        val s = run(engine(), facts())
        listOf("D1", "D2", "D3", "D4", "D6", "D7").forEach { assertEquals(it, StageStatus.PASS, status(s, it)) }
    }

    @Test fun aDeafReceiverFailsD2() {
        val d = DiagTestFacts.detection().copy(receiverSelfTestMs = null)
        assertEquals(StageStatus.FAIL, status(run(engine(), facts(detection = d)), "D2"))
    }

    @Test fun otherAppsAnnouncingButNotThePlayerFailsD3WhenAndroidHearsMedia() {
        val d = DiagTestFacts.detection(DiagTestFacts.announcements(0, 0), routes = emptyList()).copy(otherAnnouncers = mapOf("com.x.y.z" to 2), publicActiveMedia = 1)
        val s = run(engine(), facts(detection = d))
        assertEquals(StageStatus.FAIL, status(s, "D3"))
        assertEquals(StageStatus.SKIPPED, status(s, "D4"))
        assertTrue(stage(s, "D3").evidence.any { it.contains("com.x.y.z") })
    }

    @Test fun noAnnouncementAndNoMediaIsWaitingNotFailing() {
        val d = DiagTestFacts.detection(DiagTestFacts.announcements(0, 0), routes = emptyList()).copy(publicActiveMedia = 0)
        assertEquals(StageStatus.WAITING, status(run(engine(), facts(detection = d)), "D3"))
    }

    @Test fun droppedAnnouncementsFailD4WithTheirReasons() {
        val d = DiagTestFacts.detection(DiagTestFacts.announcements(2, 0, 2, listOf("cannot see package" to 2)), routes = emptyList())
        val s = run(engine(), facts(detection = d))
        assertEquals(StageStatus.FAIL, status(s, "D4"))
        assertTrue(stage(s, "D4").evidence.any { it.contains("cannot see package") })
        assertEquals(StageStatus.FAIL, status(s, "D6"))
    }

    @Test fun enhancedOnlyStagesAreNotApplicableWithoutReportsRatherThanPassed() {
        val noReports = facts(env = DiagTestFacts.env().copy(reportAccess = false), detection = onEngineB())
        val s = run(engine(), noReports)
        assertEquals(StageStatus.NEEDS_ENHANCED, status(s, "D5"))
        assertEquals(Evidence.ENHANCED_REPORTS, stage(s, "D5").needs)
        assertTrue(Pipeline.unobservable(noReports).isNotEmpty())
        assertTrue(Pipeline.unobservable(facts()).isEmpty())
    }

    @Test fun serverSideVerificationIsNeedsEnhancedOnEngineAAndSkippedOnEngineB() {
        val a = facts(env = DiagTestFacts.env().copy(reportAccess = false))
        assertEquals(StageStatus.NEEDS_ENHANCED, status(run(engine(), a), "D8"))
        assertEquals(StageStatus.SKIPPED, status(run(engine(), facts(env = DiagTestFacts.env().copy(reportAccess = false), detection = onEngineB())), "D8"))
    }

    @Test fun firstBrokenStageIsTheEarliestFailure() {
        val d = DiagTestFacts.detection().copy(receiverSelfTestMs = null)
        val s = run(engine(samples = windows(3) { window(NOW - 2_000L * (3 - it), captured = 0) }), facts(detection = d))
        assertEquals("D2", Pipeline.firstBroken(s)?.id)
        assertFalse(Pipeline.findings(s).any { it.code.startsWith("D") })
    }

    private val onEngineB get() = onEngineB()
}
