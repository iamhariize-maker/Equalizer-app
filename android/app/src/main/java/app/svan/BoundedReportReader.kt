package app.svan

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.Semaphore

/** One in-flight read, including Binder IPC. A stalled OEM cannot accumulate helper threads. */
internal class BoundedReportReader {
    private val slot = Semaphore(1)
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "svan-shell-report").apply { isDaemon = true } }

    fun read(timeoutMs: Long, task: () -> PlaybackSessions.ServiceRead): PlaybackSessions.ServiceRead {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        // Ordinary discovery and route reads share the helper; wait within the same deadline
        // rather than falsely reporting unavailable access whenever a scan is already in flight.
        val acquired = try { slot.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        if (!acquired) return PlaybackSessions.ServiceRead(null, error = "Previous shell report is still waiting for Android")
        val result = worker.submit<PlaybackSessions.ServiceRead> {
            try { task() } finally { slot.release() }
        }
        return try { result.get((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS) }
        catch (_: TimeoutException) {
            // Do not interrupt/cancel: a late descriptor is still closed by the read task's use block.
            PlaybackSessions.ServiceRead(null, error = "Shell report timed out; basic detection remains active")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            PlaybackSessions.ServiceRead(null, error = "Audio scan cancelled")
        } catch (e: Exception) {
            PlaybackSessions.ServiceRead(null, error = "Shell report unavailable: ${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName}")
        }
    }
}
