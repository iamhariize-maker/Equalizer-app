package app.svan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkPolicyTest {
    private val pkg = "com.gaana"

    @Test fun provenPlayerIsParkedBrieflyAndListenedForAfterTheMinimumDwell() {
        val p = ParkPolicy()
        p.markProven(pkg)
        val d = p.handOver(pkg, nowMs = 0, silenced = false)
        assertFalse(d.strike)
        assertEquals(20_000L, d.untilMs)
        assertTrue(p.blocked(pkg, 4_999))
        assertFalse("no check inside the minimum dwell", p.listenDue(pkg, 4_999))
        assertTrue(p.listenDue(pkg, 5_000))
        p.listened(pkg, 5_000)
        assertFalse(p.listenDue(pkg, 7_999))
        assertTrue("listen-only checks every 3 s", p.listenDue(pkg, 8_000))
    }

    @Test fun repeatedStallsGrowTheTimerToTheCapAndNeverBlockAProvenPlayerForTheSession() {
        val p = ParkPolicy()
        p.markProven(pkg)
        var now = 0L
        val waits = (1..8).map {
            val d = p.handOver(pkg, now, silenced = null)
            assertFalse(d.strike)
            assertTrue(d.untilMs != Long.MAX_VALUE)
            p.released(pkg)
            (d.untilMs - now).also { now += 1_000_000 }
        }
        assertEquals(listOf(20_000L, 60_000L, 180_000L, 600_000L, 600_000L, 600_000L, 600_000L, 600_000L), waits)
    }

    @Test fun androidSilencingTheRecorderStartsHigherButStaysBounded() {
        val p = ParkPolicy()
        p.markProven(pkg)
        assertEquals(180_000L, p.handOver(pkg, 0, silenced = true).untilMs)
        p.released(pkg)
        assertEquals(1_600_000L, p.handOver(pkg, 1_000_000, silenced = true).untilMs)
    }

    @Test fun tenSecondsOfCapturedAudioForgetsEarlierHandOvers() {
        val p = ParkPolicy()
        p.markProven(pkg)
        p.handOver(pkg, 0, null); p.released(pkg)
        p.handOver(pkg, 100_000, null); p.released(pkg)
        assertEquals(2, p.handOvers(pkg))
        assertTrue(p.healthy(pkg))
        assertFalse("nothing left to forget", p.healthy(pkg))
        assertEquals(20_000L, p.handOver(pkg, 200_000, null).untilMs - 200_000)
    }

    @Test fun unprovenPlayersKeepTheStrikeLadder() {
        val p = ParkPolicy()
        val first = p.handOver(pkg, 0, null)
        assertTrue(first.strike)
        assertEquals(3 * 60_000L, first.untilMs)
        assertFalse("never listened for: its capture was never proven", p.listenDue(pkg, 10_000))
        p.released(pkg)
        assertEquals(1_000_000L + 15 * 60_000L, p.handOver(pkg, 1_000_000, null).untilMs)
        p.released(pkg)
        assertEquals(Long.MAX_VALUE, p.handOver(pkg, 2_000_000, null).untilMs)
        assertFalse(p.timerDue(pkg, Long.MAX_VALUE - 1))
    }

    @Test fun releasingEndsTheWindowButKeepsProofAndCount() {
        val p = ParkPolicy()
        p.markProven(pkg)
        p.handOver(pkg, 0, null)
        assertTrue(p.parked(pkg))
        p.released(pkg)
        assertFalse(p.parked(pkg))
        assertFalse(p.blocked(pkg, 1_000))
        assertTrue(p.proven(pkg))
        assertEquals(1, p.handOvers(pkg))
    }

    @Test fun timerDueOnlyAfterTheParkWindow() {
        val p = ParkPolicy()
        p.markProven(pkg)
        p.handOver(pkg, 0, null)
        assertFalse(p.timerDue(pkg, 19_999))
        assertTrue(p.timerDue(pkg, 20_000))
        assertFalse("no window, nothing due", ParkPolicy().timerDue(pkg, 1))
    }

    @Test fun clearAndForgetDropProof() {
        val p = ParkPolicy()
        p.markProven(pkg); p.handOver(pkg, 0, null)
        p.forget(pkg)
        assertFalse(p.proven(pkg)); assertFalse(p.parked(pkg))
        p.markProven(pkg); p.clear()
        assertFalse(p.proven(pkg))
    }

    @Test fun checksThatKeepHearingSilenceSlowDownAndANewStreamRestoresThePace() {
        val p = ParkPolicy()
        p.markProven(pkg)
        val delays = (1..60).map { p.silentListen(pkg) }
        assertEquals(List(10) { 3_000L } + List(40) { 10_000L } + List(10) { 30_000L }, delays)
        p.released(pkg)
        assertEquals(3_000L, p.silentListen(pkg))
        repeat(20) { p.silentListen(pkg) }
        p.handOver(pkg, 0, null); p.released(pkg); p.handOver(pkg, 1_000, null)
        assertTrue(p.healthy(pkg))
        assertEquals(3_000L, p.silentListen(pkg))
    }

    @Test fun provenPlayersAreReCheckedQuicklyAndNeverGiveUp() {
        val s = LateProbeSchedule(maxChecks = 2, silentRetryMs = 30_000)
        repeat(10) { i ->
            val now = i * 3_000L
            assertTrue("check $i due", s.due(pkg, now))
            s.started(pkg)
            s.silent(pkg, now, retryMs = 3_000, counts = false)
        }
        assertFalse(s.gaveUp(pkg))
        assertFalse(s.due(pkg, 27_000 + 2_999))
    }
}
