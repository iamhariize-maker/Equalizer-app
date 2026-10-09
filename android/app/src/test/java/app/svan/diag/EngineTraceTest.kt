package app.svan.diag

import app.svan.diag.EngineTrace.Cat
import app.svan.diag.EngineTrace.Mark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EngineTraceTest {
    @Before fun reset() = EngineTrace.clearForTest()

    private fun classify(line: String) = LogClassifier.classify(line)

    @Test fun routeAndProbeAndFailOpenLinesAreClassifiedWithTheirPackage() {
        assertEquals(Cat.ROUTE, classify("route: com.spotify.music (session 42225) → Engine B (source muted)")?.cat)
        assertEquals("com.spotify.music", classify("route: com.spotify.music (session 42225) → Engine A")?.pkg)
        assertEquals(Cat.PROBE, classify("capture check: com.spotify.music → CAPTURABLE")?.cat)
        assertEquals(Cat.PROBE, classify("capture check failed for com.spotify.music: java.lang.IllegalStateException")?.cat)
        assertEquals(Cat.FAILOPEN, classify("fail-open: com.spotify.music (session 1) → Engine A (30 s)")?.cat)
        assertEquals(Cat.FAILOPEN, classify("capture: no frames for 2.5s while a muted source plays → failing open")?.cat)
        assertEquals(Cat.MUTE, classify("lost mute control of com.spotify.music (session 1): another effect app took over")?.cat)
        assertEquals(Cat.OUTPUT, classify("capture: output write failed (-6)")?.cat)
        assertEquals(Cat.OUTPUT, classify("capture: persistent underruns at maximum buffer; returning to system effects")?.cat)
        assertEquals(Cat.RECORDER, classify("capture: rate 48000 rejected: capture not initialized")?.cat)
        assertEquals(Cat.LIFECYCLE, classify("capture: start blocked — something")?.cat)
        assertEquals(Cat.DETECT, classify("sync: 2 session(s) in dump: a, b")?.cat)
    }

    @Test fun periodicLevelLinesAndUnrelatedLinesAreNotKept() {
        assertNull(classify("capture level: peak=0.5 over 96000 frames"))
        assertNull(classify("capture timing: readWaitMaxMs=1.0"))
        assertNull(classify("Svaramanas heard something"))
    }

    @Test fun logLinesFlowIntoTheTraceWithTimestamps() {
        EngineTrace.fromLog("route: com.spotify.music (session 1) → Engine B (source muted)", nowMs = 5_000)
        val e = EngineTrace.events().single()
        assertEquals(5_000L, e.atMs); assertEquals("com.spotify.music", e.pkg); assertEquals(Cat.ROUTE, e.cat)
    }

    @Test fun anIdenticalEventWithinTwoSecondsIsKeptOnce() {
        EngineTrace.record(Cat.ROUTE, "x", "a.b.c", 1_000)
        EngineTrace.record(Cat.ROUTE, "x", "a.b.c", 1_500)
        EngineTrace.record(Cat.ROUTE, "x", "a.b.c", 4_000)
        assertEquals(2, EngineTrace.events().size)
    }

    @Test fun theRingKeepsTheNewestEvents() {
        repeat(700) { EngineTrace.record(Cat.ROUTE, "event $it", nowMs = 1_000L + it * 3_000L) }
        val events = EngineTrace.events()
        assertEquals(500, events.size)
        assertEquals("event 699", events.last().text)
    }

    @Test fun aMilestoneKeepsTheTimeItWasFirstReached() {
        EngineTrace.beginRun(nowMs = 1_000)
        EngineTrace.mark(Mark.FIRST_AUDIO, nowMs = 2_000)
        EngineTrace.mark(Mark.FIRST_AUDIO, nowMs = 9_000)
        assertEquals(2_000L, EngineTrace.marks()[Mark.FIRST_AUDIO])
        EngineTrace.beginRun(nowMs = 20_000)
        assertTrue(EngineTrace.marks().isEmpty())
        assertEquals(2, EngineTrace.runCount)
    }

    @Test fun restartsAreCountedAndExplained() {
        EngineTrace.beginRun()
        EngineTrace.restarted("Capture read failed; retrying Fast at safe 48 kHz.")
        assertEquals(1, EngineTrace.restarts)
        assertTrue(EngineTrace.events().last().text.contains("Capture read failed"))
    }

    @Test fun samplesAreBounded() {
        repeat(200) { EngineTrace.sample(DiagTestFacts.window(it * 2_000L)) }
        assertEquals(120, EngineTrace.samples().size)
    }

    @Test fun aSavedTraceRoundTripsAndKeepsTheCleanStopFlag() {
        val events = listOf(EngineTrace.Event(10, Cat.LIFECYCLE, null, "capture run 1 requested"),
            EngineTrace.Event(20, Cat.ROUTE, "com.spotify.music", "route: a | pipe in text"))
        val parsed = EngineTrace.parseSaved(EngineTrace.render(events, 99, false).lines().filter { it.isNotEmpty() })
        assertNotNull(parsed)
        assertFalse(parsed!!.cleanStop)
        assertEquals(99L, parsed.savedAtMs)
        assertEquals(events, parsed.events)
    }

    @Test fun garbageInTheSavedFileIsIgnored() {
        assertNull(EngineTrace.parseSaved(listOf("hello")))
        assertNull(EngineTrace.parseSaved(emptyList()))
    }
}
