package app.svan

import org.junit.Assert.*
import org.junit.Test

class SessionCloseGraceTest {
    @Test fun trackReopenCancelsTeardown() {
        val g = SessionCloseGrace()
        g.closing(7, "apple", 1, 0)
        g.reopened(7)
        assertTrue(g.expired(1501).isEmpty())
    }
    @Test fun duplicateCloseCannotExtendGraceForever() {
        val g = SessionCloseGrace()
        g.closing(7, "apple", 1, 0)
        g.closing(7, "apple", 1, 1400)
        assertTrue(g.expired(1499).isEmpty())
        assertEquals(1L, g.nextDelay(1499))
        assertEquals(7, g.expired(1500).single().sid)
        assertNull(g.nextDelay(1500))
    }
    @Test fun newSessionLeavesOnlyTheOldSessionPending() {
        val g = SessionCloseGrace()
        g.closing(7, "yt", 1, 0)
        g.reopened(8)
        assertEquals(7, g.expired(1500).single().sid)
    }
    @Test fun reusedSessionAndLateTimerCannotCloseNewOwner() {
        val g = SessionCloseGrace()
        g.closing(7, "apple", 1, 0)
        g.reopened(7)
        g.closing(7, "yt", 2, 1000)
        assertTrue(g.expired(1500).isEmpty())
        assertEquals("yt", g.expired(2500).single().pkg)
    }
    @Test fun pendingConnectionsAreBoundedAndClearOnShutdown() {
        val g = SessionCloseGrace(capacity = 2)
        repeat(3) { g.closing(it, "p", 1, 0) }
        assertEquals(0, g.overflow().single().sid)
        g.clear()
        assertTrue(g.expired(2000).isEmpty())
    }
    @Test fun captureHandoffCanDrainPendingClosesBeforeMutingAnySource() {
        val g = SessionCloseGrace()
        g.closing(7, "apple", 1, 100)
        g.closing(8, "yt", 2, 200)
        assertEquals(listOf(7, 8), g.expired(Long.MAX_VALUE).map { it.sid })
        assertNull(g.nextDelay(201))
    }
}
