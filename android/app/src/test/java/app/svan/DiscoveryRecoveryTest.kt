package app.svan

import org.junit.Assert.*
import org.junit.Test

class DiscoveryRecoveryTest {
    @Test fun scanRequestsDuringWorkAreCoalescedButNotLost() {
        val gate = ScanRequestGate()
        assertTrue(gate.request())
        repeat(50) { assertFalse(gate.request()) }
        assertTrue(gate.complete()) // one follow-up, even though 50 callbacks arrived
        assertFalse(gate.request()) // another callback during that follow-up
        assertTrue(gate.complete())
        assertFalse(gate.complete())
        assertTrue(gate.request()) // completion does not wedge subsequent scans
        assertFalse(gate.complete())
    }

    @Test fun transientEmptySnapshotsDoNotRemoveThePlayer() {
        val tracker = SessionAbsenceTracker()
        assertTrue(tracker.observe(setOf(10), emptySet(), 0).isEmpty())
        assertTrue(tracker.observe(setOf(10), emptySet(), 500).isEmpty())
        assertTrue(tracker.observe(setOf(10), emptySet(), 5_000).isEmpty())
        assertTrue(tracker.observe(setOf(10), setOf(10), 8_000).isEmpty())
        assertTrue(tracker.observe(setOf(10), emptySet(), 9_000).isEmpty())
        assertTrue(tracker.observe(setOf(10), emptySet(), 15_000).isEmpty())
        assertEquals(setOf(10), tracker.observe(setOf(10), emptySet(), 19_000))
    }

    @Test fun missingSessionNeedsTimeAndMultipleSnapshots() {
        val tracker = SessionAbsenceTracker()
        tracker.observe(setOf(1, 2), setOf(2), 0)
        assertTrue(tracker.observe(setOf(1, 2), setOf(2), 30_000).isEmpty())
        assertEquals(setOf(1), tracker.observe(setOf(1, 2), setOf(2), 35_000))
        tracker.forget(1)
        assertTrue(tracker.observe(setOf(1, 2), setOf(2), 40_000).isEmpty())
    }

    @Test fun failedAttachmentsRetryWithACappedDelayAndResetOnRecovery() {
        val retry = AttachmentRetry()
        assertTrue(retry.ready(7, 0))
        retry.failed(7, 0)
        assertFalse(retry.ready(7, 999))
        assertTrue(retry.ready(7, 1_000))
        retry.failed(7, 1_000)
        assertFalse(retry.ready(7, 2_999))
        assertTrue(retry.ready(7, 3_000))
        repeat(20) { retry.failed(7, 10_000) }
        assertFalse(retry.ready(7, 39_999))
        assertTrue(retry.ready(7, 40_000))
        assertTrue(retry.ready(8, 10_000)) // an unsupported player cannot delay another
        retry.forget(7)
        assertTrue(retry.ready(7, 10_000))
        retry.failed(7, 10_000)
        assertTrue(retry.ready(7, 11_000))
    }
}
