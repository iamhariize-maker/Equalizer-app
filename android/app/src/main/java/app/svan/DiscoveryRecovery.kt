package app.svan

/** Retain a request arriving during a scan, including the completion boundary. */
internal class ScanRequestGate {
    private var running = false
    private var again = false
    @Synchronized fun request(): Boolean {
        if (running) { again = true; return false }
        running = true
        return true
    }
    @Synchronized fun complete(): Boolean {
        if (again) { again = false; return true }
        running = false
        return false
    }
}

/** Only successful snapshots count; an unavailable report must never mean “no players”. */
internal class SessionAbsenceTracker {
    private data class Missing(val sinceMs: Long, var scans: Int)
    private val missing = mutableMapOf<Int, Missing>()
    fun observe(known: Set<Int>, seen: Set<Int>, nowMs: Long): Set<Int> {
        missing.keys.retainAll(known - seen)
        return (known - seen).filterTo(mutableSetOf()) { sid ->
            val entry = missing[sid]?.also { it.scans++ }
                ?: Missing(nowMs, 1).also { missing[sid] = it }
            entry.scans >= 3 && nowMs - entry.sinceMs >= 10_000L
        }
    }
    fun forget(sid: Int) { missing.remove(sid) }
    fun clear() { missing.clear() }
}

/** Retry transient effect failures, without repeatedly rebuilding effects on every callback. */
internal class AttachmentRetry {
    private data class Failure(val attempts: Int, val nextMs: Long)
    private val failures = mutableMapOf<Int, Failure>()
    fun ready(sid: Int, nowMs: Long): Boolean = nowMs >= (failures[sid]?.nextMs ?: Long.MIN_VALUE)
    fun failed(sid: Int, nowMs: Long) {
        val attempts = ((failures[sid]?.attempts ?: 0) + 1).coerceAtMost(6)
        failures[sid] = Failure(attempts, nowMs + (1_000L shl (attempts - 1)).coerceAtMost(30_000L))
    }
    fun forget(sid: Int) { failures.remove(sid) }
    fun clear() { failures.clear() }
}
