package app.svan.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SignalLedgerTest {
    @Before fun reset() = SignalLedger.clearForTest(0)

    @Test fun summarizesAcceptedAndDroppedAnnouncementsPerApp() {
        SignalLedger.record(SignalLedger.Kind.BROADCAST, "OPEN session=100 dropped: Svan's service was not running", "com.spotify.music", 1_000)
        SignalLedger.record(SignalLedger.Kind.BROADCAST, "OPEN session=101 accepted", "com.spotify.music", 5_000)
        SignalLedger.record(SignalLedger.Kind.BROADCAST, "CLOSE session=101 accepted", "com.spotify.music", 9_000)
        SignalLedger.record(SignalLedger.Kind.BROADCAST, "OPEN session=7 accepted", "org.videolan.vlc", 6_000)
        val s = AnnouncementAnalysis.summarize(SignalLedger.snapshot(), "com.spotify.music")
        assertEquals(2, s.opens); assertEquals(1, s.closes); assertEquals(1, s.accepted); assertEquals(1, s.dropped)
        assertEquals(setOf(100, 101), s.sessions)
        assertEquals(5_000L, s.lastAcceptedMs)
        assertEquals("Svan's service was not running", s.dropReasons.single().first)
    }

    @Test fun anAcceptedCloseDoesNotMakeAnAppLookAnnounced() {
        SignalLedger.record(SignalLedger.Kind.BROADCAST, "OPEN session=5 dropped: Svan's service was not running", "p", 1)
        SignalLedger.record(SignalLedger.Kind.BROADCAST, "CLOSE session=5 accepted", "p", 2)
        val s = AnnouncementAnalysis.summarize(SignalLedger.snapshot(), "p")
        assertEquals(0, s.accepted); assertEquals(1, s.dropped); assertEquals(null, s.lastAcceptedMs)
    }

    @Test fun announcersCountsOpensOnly() {
        SignalLedger.record(SignalLedger.Kind.BROADCAST, "OPEN session=7 accepted", "org.videolan.vlc", 1)
        SignalLedger.record(SignalLedger.Kind.BROADCAST, "CLOSE session=7 accepted", "org.videolan.vlc", 2)
        assertEquals(mapOf("org.videolan.vlc" to 1), AnnouncementAnalysis.announcers(SignalLedger.snapshot()))
    }

    @Test fun aChattySignalCannotPushOutRareAnnouncements() {
        SignalLedger.record(SignalLedger.Kind.BROADCAST, "OPEN session=1 accepted", "a", 1)
        repeat(2_000) { SignalLedger.record(SignalLedger.Kind.PLAYBACK, "active players=$it", null, 10L + it * 5000L) }
        val events = SignalLedger.snapshot()
        assertTrue(events.any { it.kind == SignalLedger.Kind.BROADCAST })
        assertTrue(events.count { it.kind == SignalLedger.Kind.PLAYBACK } <= 150)
    }

    @Test fun immediateRepeatsAreCollapsed() {
        repeat(5) { SignalLedger.record(SignalLedger.Kind.PLAYBACK, "active players=1", null, 1_000L + it) }
        assertEquals(1, SignalLedger.snapshot().size)
    }

    @Test fun snapshotIsInTimeOrderAcrossKinds() {
        SignalLedger.record(SignalLedger.Kind.DEVICE, "later", null, 50)
        SignalLedger.record(SignalLedger.Kind.BROADCAST, "OPEN session=1 accepted", "a", 10)
        assertEquals(listOf(10L, 50L), SignalLedger.snapshot().map { it.atMs })
    }
}
