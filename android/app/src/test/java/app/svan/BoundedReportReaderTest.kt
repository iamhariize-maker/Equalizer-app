package app.svan

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class BoundedReportReaderTest {
    @Test fun stalledBinderTimesOutWithoutLaunchingADuplicateReadAndRecovers() {
        val reader = BoundedReportReader()
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        try {
            val timedOut = reader.read(50) {
                release.await(5, TimeUnit.SECONDS)
                finished.countDown()
                PlaybackSessions.ServiceRead("late report")
            }
            assertNull(timedOut.text)
            assertTrue(timedOut.error.orEmpty().contains("timed out"))
            val duplicate = reader.read(50) { fail("A stuck read must not spawn another IPC"); PlaybackSessions.ServiceRead("unexpected") }
            assertNull(duplicate.text)
            release.countDown()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            // Wait for the worker's finally block, without relying on a sleep duration.
            var next: PlaybackSessions.ServiceRead
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            do { next = reader.read(200) { PlaybackSessions.ServiceRead("fresh report") } } while (next.text == null && System.nanoTime() < deadline)
            assertEquals("fresh report", next.text)
        } finally { release.countDown() }
    }

    @Test fun reportFailureDoesNotLeaveReaderBusy() {
        val reader = BoundedReportReader()
        assertNull(reader.read(1000) { throw SecurityException("Denied") }.text)
        assertEquals("ok", reader.read(1000) { PlaybackSessions.ServiceRead("ok") }.text)
    }
}
