package app.svan

/**
 * When a source that Engine B handed back to Engine A may return. Worker-owned and pure (the clock is passed in),
 * so the schedule is unit-tested without Android.
 *
 * A package whose capture was proven in this capture session is never blocked for the rest of it. Silence after
 * that proof is most often a stall (buffering, an ad gap, a new track that has not started), so each hand-over
 * waits a little longer: [ladderMs] (20 s, 60 s, 3 min, then every 10 minutes). While the engine carries no other
 * source, a listen-only check every [listenEveryMs] after [minDwellMs] can bring it back as soon as its audio
 * reaches capture again. Checks that keep hearing silence (a paused track still listed by Android) slow down: after
 * ten, every 10 s; after forty more, every 30 s. A new stream or a return to Engine B restores the fast pace. Ten seconds of captured audio ([healthy]) forgets the hand-overs. A hand-over while
 * Android reports the capture client as silenced starts two rungs higher, but is still never permanent.
 *
 * A package never proven in this capture session keeps [FailOpenBackoff]'s strikes (3 min, 15 min, then the rest of
 * the capture session). None of this is saved: silence is not an application capture policy.
 *
 * Works without enhanced detection: nothing here needs to know whether the player is playing.
 */
internal class ParkPolicy(
    private val ladderMs: LongArray = longArrayOf(20_000L, 60_000L, 180_000L, 600_000L),
    private val minDwellMs: Long = 5_000L,
    val listenEveryMs: Long = 3_000L,
) {
    data class Decision(val untilMs: Long, val step: Int, val strike: Boolean, val sinceLastMs: Long?, val description: String)

    private class State {
        var handOvers = 0
        var strikes = 0
        var parkedAtMs = 0L
        var untilMs = 0L
        var nextListenMs = 0L
        var lastHandOverMs: Long? = null
        var silentListens = 0
    }

    private val states = HashMap<String, State>()
    private val provenPackages = HashSet<String>()

    private fun state(pkg: String) = states.getOrPut(pkg) { State() }

    /** Capture of [pkg] was heard after its source was muted, in this capture session. */
    fun markProven(pkg: String) { provenPackages.add(pkg) }

    fun proven(pkg: String): Boolean = pkg in provenPackages

    /** Engine B handed [pkg] back to Engine A at [nowMs]. */
    fun handOver(pkg: String, nowMs: Long, silenced: Boolean?): Decision {
        val s = state(pkg)
        val sinceLast = s.lastHandOverMs?.let { nowMs - it }
        s.lastHandOverMs = nowMs
        s.parkedAtMs = nowMs
        if (pkg in provenPackages) {
            s.handOvers++
            val step = ((s.handOvers - 1) + if (silenced == true) 2 else 0).coerceAtMost(ladderMs.size - 1)
            s.untilMs = nowMs + ladderMs[step]
            s.nextListenMs = nowMs + minDwellMs
            val wait = ladderMs[step] / 1000
            return Decision(s.untilMs, step, strike = false, sinceLastMs = sinceLast,
                description = "listen-only checks every ${listenEveryMs / 1000} s after ${minDwellMs / 1000} s, or return after $wait s" +
                    if (silenced == true) " (capture client silenced by Android)" else "")
        }
        s.strikes++
        val blockMs = FailOpenBackoff.blockMs(s.strikes)
        s.untilMs = if (blockMs == Long.MAX_VALUE) Long.MAX_VALUE else nowMs + blockMs
        s.nextListenMs = Long.MAX_VALUE
        return Decision(s.untilMs, s.strikes, strike = true, sinceLastMs = sinceLast, description = FailOpenBackoff.describe(s.strikes))
    }

    /** True while [pkg] is inside a park window and must not be routed to Engine B by an ordinary re-route. */
    fun blocked(pkg: String, nowMs: Long): Boolean {
        val s = states[pkg] ?: return false
        return nowMs < s.untilMs
    }

    /** The park timer ran out: a proven package may return directly, even while another source is captured. */
    fun timerDue(pkg: String, nowMs: Long): Boolean {
        val s = states[pkg] ?: return false
        return s.untilMs != 0L && nowMs >= s.untilMs
    }

    /** A listen-only check of a parked, proven package is due (only while the engine carries no other source). */
    fun listenDue(pkg: String, nowMs: Long): Boolean {
        val s = states[pkg] ?: return false
        return pkg in provenPackages && s.untilMs != 0L && nowMs >= s.parkedAtMs + minDwellMs && nowMs >= s.nextListenMs
    }

    /** A listen-only check started; the next one waits the current listen interval. */
    fun listened(pkg: String, nowMs: Long) { states[pkg]?.let { it.nextListenMs = nowMs + interval(it.silentListens) } }

    /** A check of a proven [pkg] heard only silence: how long until the next one (3 s, then 10 s, then 30 s). */
    fun silentListen(pkg: String): Long {
        val s = state(pkg)
        s.silentListens++
        return interval(s.silentListens)
    }

    private fun interval(silentListens: Int): Long = when {
        silentListens <= FAST_LISTENS -> listenEveryMs
        silentListens <= FAST_LISTENS + MEDIUM_LISTENS -> 10_000L
        else -> 30_000L
    }

    /** [pkg] left the park window (returned to Engine B, or a new stream replaced it). Hand-over counts are kept. */
    fun released(pkg: String) {
        states[pkg]?.let { it.untilMs = 0L; it.nextListenMs = 0L; it.silentListens = 0 }
    }

    /** True when [pkg] is parked after a hand-over (window not yet released). */
    fun parked(pkg: String): Boolean = (states[pkg]?.untilMs ?: 0L) != 0L

    /** Ten seconds of captured audio: the earlier silences were transient. Returns true when something was reset. */
    fun healthy(pkg: String): Boolean {
        val s = states[pkg] ?: return false
        if (s.handOvers == 0 && s.strikes == 0) return false
        s.handOvers = 0
        s.strikes = 0
        s.silentListens = 0
        return true
    }

    fun handOvers(pkg: String): Int = states[pkg]?.handOvers ?: 0

    private companion object {
        const val FAST_LISTENS = 10
        const val MEDIUM_LISTENS = 40
    }

    fun forget(pkg: String) { states.remove(pkg); provenPackages.remove(pkg) }

    fun clear() { states.clear(); provenPackages.clear() }
}
