package app.svan

import org.junit.Assert.*
import org.junit.Test

class SessionContinuityTest {
    @Test fun closeRetainsHistoryButNeverAnActiveSession() {
        val h = SessionConnectionHistory()
        h.opened(7, "apple", 12, 0)
        assertTrue(h.closed(7, "apple", 1))
        assertNull(h.active(7))
        assertEquals(1L, h.recent(2).single().closedMs)
    }
    @Test fun unrelatedCloseCannotDeleteAnotherPlayersConnection() {
        val h = SessionConnectionHistory()
        val first = h.opened(7, "apple", 12, 0)
        assertFalse(h.closed(7, "amazon", 1))
        assertEquals(first, h.active(7)?.generation)
    }
    @Test fun duplicateAnnouncementsKeepGenerationAndReopenChangesIt() {
        val h = SessionConnectionHistory()
        val g = h.opened(7, "apple", 12, 0)
        assertEquals(g, h.opened(7, "apple", 12, 1))
        h.closed(7, "apple", 2)
        assertTrue(h.opened(7, "apple", 12, 3) > g)
        assertNull(h.active(7)?.closedMs)
    }
    @Test fun numericSessionReuseDoesNotInheritTheOldOwner() {
        val h = SessionConnectionHistory()
        val g = h.opened(7, "apple", 12, 0)
        assertTrue(h.opened(7, "amazon", 13, 1) > g)
        assertFalse(h.closed(7, "apple", 2))
        assertEquals(13, h.active(7)?.uid)
    }
    @Test fun closedHistoryIsBoundedAndExpiresWithoutKeepingLiveEffects() {
        val h = SessionConnectionHistory(maxClosed = 2, retentionMs = 100)
        repeat(4) { h.opened(it + 1, "p", 12, it.toLong()); h.closed(it + 1, "p", it.toLong()) }
        assertEquals(listOf(3, 4), h.recent(5).map { it.sessionId })
        assertTrue(h.recent(104).isEmpty())
    }
    @Test fun sharedOutputIsExclusiveAndNeverAdmitsCapture() {
        assertTrue(SharedOutputPolicy.allowed(requested = true, service = true, capture = false))
        assertFalse(SharedOutputPolicy.allowed(true, true, true))
        assertFalse(SharedOutputPolicy.allowed(true, false, false))
        assertFalse(SharedOutputPolicy.allowed(false, true, false))
        val route = SessionRouter.Route(7, "p", 12, SessionRouter.Owner.SHARED_OUTPUT)
        assertTrue(CapturePolicy.eligibleUids(listOf(route), 99).isEmpty())
        assertNotNull(CapturePolicy.startupBlock(true, listOf(route), 99, sharedOutput = true))
    }
    @Test fun panelRequestNeedsARealSessionAndKnownOtherUid() {
        assertTrue(SessionAnnouncement.valid(7, "apple", 12, 99))
        assertFalse(SessionAnnouncement.valid(0, "apple", 12, 99))
        assertFalse(SessionAnnouncement.valid(7, "", 12, 99))
        assertFalse(SessionAnnouncement.valid(7, "apple", -1, 99))
        assertFalse(SessionAnnouncement.valid(7, "svan", 99, 99))
    }
    @Test fun claimedOwnerMustMatchTheAudioServiceWhenItNamesAnotherApp() {
        assertTrue(SessionAnnouncement.consistent(10_123, null))      // no report: cannot judge
        assertTrue(SessionAnnouncement.consistent(10_123, 10_123))    // matches
        assertTrue(SessionAnnouncement.consistent(10_123, 1_013))     // media server renders for players
        assertTrue(SessionAnnouncement.consistent(-1, 10_456))        // no claim to check
        assertFalse(SessionAnnouncement.consistent(10_123, 10_456))   // another app claims this session
    }
}
