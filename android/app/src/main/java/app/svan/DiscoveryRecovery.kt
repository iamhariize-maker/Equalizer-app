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

/**
 * Bounds re-attaching an effect the audio server report does not show. A successful attach clears
 * [AttachmentRetry], so without this a report that keeps disagreeing (an unrecognised chain layout,
 * an OEM effect proxy) would tear down and rebuild the effect on every scan: an audible glitch every
 * few seconds. Each session gets a few spaced attempts; only a PROCESSING verdict re-arms it.
 */
internal class MissingEffectRepair(private val maxAttempts: Int = 3, private val firstDelayMs: Long = 10_000L) {
    private data class State(val attempts: Int, val nextMs: Long)
    private val states = mutableMapOf<Int, State>()

    /** Feed every scan's verdict; true = re-attach now. */
    fun shouldRepair(sid: Int, verdict: Verification?, nowMs: Long): Boolean {
        if (verdict == Verification.PROCESSING) { states.remove(sid); return false }
        if (verdict != Verification.MISSING) return false
        val s = states[sid]
        if (s != null && (s.attempts >= maxAttempts || nowMs < s.nextMs)) return false
        val attempts = (s?.attempts ?: 0) + 1
        states[sid] = State(attempts, nowMs + (firstDelayMs shl (attempts - 1)))
        return true
    }

    /** True once the attempts for [sid] are used up (the report keeps disagreeing with a working attach). */
    fun exhausted(sid: Int): Boolean = (states[sid]?.attempts ?: 0) >= maxAttempts
    fun forget(sid: Int) { states.remove(sid) }
    fun clear() { states.clear() }
}
