package app.svan

/** Main-thread-only deadlines: watchdog and route-settling checks share one Handler runnable. */
internal class DiscoverySchedule(nowMs: Long) {
    private var intervalMs = 5_000L
    private var watchdogMs = nowMs
    private var recoveryMs = emptyList<Long>()

    fun interval(nowMs: Long, valueMs: Long) {
        require(valueMs > 0)
        if (valueMs == intervalMs) return
        intervalMs = valueMs
        watchdogMs = nowMs + valueMs
    }

    fun recover(nowMs: Long) {
        // Preserve the earliest pending check during callback storms, as well as the latest tail.
        // At most five deadlines, independent of the number of callbacks received.
        val next = RECOVERY.map { nowMs + it }
        recoveryMs = next.mapIndexed { i, due -> minOf(due, recoveryMs.getOrNull(i) ?: due) }
        recoveryMs = recoveryMs.dropLast(1) + maxOf(next.last(), recoveryMs.last())
    }

    fun nextMs(): Long = minOf(watchdogMs, recoveryMs.firstOrNull() ?: Long.MAX_VALUE)

    fun scanned(nowMs: Long) {
        recoveryMs = recoveryMs.dropWhile { it <= nowMs }
        watchdogMs = nowMs + intervalMs
    }

    /** One scan consumes all deadlines due now, never two scans for the same instant. */
    fun due(nowMs: Long): Boolean {
        if (nowMs < nextMs()) return false
        // A recovery scan also satisfies the watchdog; don't scan again immediately after it.
        scanned(nowMs)
        return true
    }

    companion object {
        private val RECOVERY = listOf(350L, 1_000L, 2_500L, 5_000L, 10_000L)

        fun cadence(basic: Boolean, publicActive: Int?, knownSessions: Boolean,
                    captureRunning: Boolean, callbackRegistered: Boolean): Long = when {
            basic && ((publicActive ?: 0) > 0 || knownSessions || captureRunning) -> 1_000L
            publicActive == 0 && !knownSessions && !captureRunning && callbackRegistered -> 30_000L
            else -> 5_000L // missing/unknown callback or public evidence is never proof of idle
        }
    }
}
