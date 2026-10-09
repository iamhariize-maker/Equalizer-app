package app.svan

/**
 * Why a detected player is on system effects (Engine A) instead of the audiophile engine, in words the
 * Hi-Fi screen can show. "Music detected" and "music on the full DSP chain" are different facts; this is the gap.
 */
internal enum class RouteReason(val text: String) {
    CHECKING("Checking whether this app allows capture. The full chain starts when it does; system effects play meanwhile."),
    WAITING_FOR_PLAYBACK("Waiting for it to play before checking capture."),
    SYSTEM_ONLY("You chose System effects for this app."),
    PLAYER_UNIDENTIFIED("Android has not said which app owns this audio yet, so it stays on system effects."),
    STREAM_NOT_CAPTURABLE("Android marks this stream as not capturable, so it stays on system effects."),
    NOT_MUSIC_USAGE("Android labels this stream as something other than music, so it stays on system effects."),
    SILENT_RECENTLY("Capture returned silence a moment ago. Svan keeps system effects on and will check again."),
    BLOCKED_THIS_VERSION("Capture returned silence on repeated checks while it played, so this app version stays on system effects. An app update, or Forget capture verdicts in Lab, checks again."),
    RECORDER_BUSY("Android would not open a second capture while the engine was running. Svan will try again."),
    INCONCLUSIVE("Svan could not confirm capture for this app, so it stays on system effects."),
    MUTE_UNAVAILABLE("Svan could not take over this app's volume (another effects app may hold it), so it stays on system effects."),
    UID_GROUP_UNSAFE("Another audio stream from this app is playing, so it cannot be captured alone yet. Svan will retry."),
}

/**
 * When to run a capture check on a player found after the audiophile engine started, per package.
 * Worker-thread only. A check never counts as a verdict by itself: see [SilentStrikes].
 */
internal class LateProbeSchedule(
    private val maxChecks: Int = 6,
    private val silentRetryMs: Long = 30_000L,
    private val tickEveryMs: Long = 15_000L,
) {
    private class State { var checks = 0; var refusals = 0; var nextAtMs = 0L; var inFlight = false; var gaveUp = false }
    private val states = HashMap<String, State>()
    private val lastTick = HashMap<Int, Long>()

    private fun state(pkg: String) = states.getOrPut(pkg) { State() }

    fun due(pkg: String, nowMs: Long): Boolean {
        val s = states[pkg] ?: return true
        return !s.gaveUp && !s.inFlight && nowMs >= s.nextAtMs
    }

    fun gaveUp(pkg: String): Boolean = states[pkg]?.gaveUp == true

    fun started(pkg: String) { state(pkg).also { it.inFlight = true; it.checks++ } }

    /** The check heard only zeros (or nothing it could judge); look again after a pause, up to [maxChecks] times. */
    fun silent(pkg: String, nowMs: Long) = state(pkg).let {
        it.inFlight = false
        it.nextAtMs = nowMs + silentRetryMs
        if (it.checks >= maxChecks) it.gaveUp = true
    }

    /** Android would not open the check recorder: wait 1 minute, then 5, then stop for this capture session. */
    fun refused(pkg: String, nowMs: Long) = state(pkg).let {
        it.inFlight = false
        it.refusals++
        it.nextAtMs = nowMs + if (it.refusals <= 1) 60_000L else 300_000L
        if (it.refusals >= 3) it.gaveUp = true
    }

    /** The check was stopped on purpose; it costs nothing and may run again right away. */
    fun cancelled(pkg: String) { states[pkg]?.inFlight = false }

    /** A verdict was stored; no further checks are needed. */
    fun settled(pkg: String) { states.remove(pkg) }

    /** At most one tick-driven re-route per session every [tickEveryMs], so the periodic scan cannot spam routing. */
    fun tickReady(sessionId: Int, nowMs: Long): Boolean {
        val last = lastTick[sessionId]
        if (last != null && nowMs - last < tickEveryMs) return false
        lastTick[sessionId] = nowMs
        return true
    }

    fun forgetSession(sessionId: Int) { lastTick.remove(sessionId) }

    fun clear() { states.clear(); lastTick.clear() }
}

/** The Hi-Fi header and note, derived from the real routes so they cannot call detected music "not connected". */
internal object HiFiStatus {
    data class Line(val title: String, val detail: String)

    fun engine(running: Boolean, systemRunning: Boolean, fullChainApps: Int, systemEffectsApps: Int): Line = when {
        !running -> Line("Audiophile engine off",
            if (systemRunning) "System effects available. Check app rows for actual processing."
            else "Processing is stopped. Start the system equalizer or audiophile engine.")
        fullChainApps > 0 -> Line("Audiophile engine connected",
            "$fullChainApps app(s) on the full 64-bit chain · $systemEffectsApps on system effects")
        systemEffectsApps > 0 -> Line("Music detected · using system effects",
            "$systemEffectsApps app(s) playing through system effects. The full 64-bit chain starts when one allows capture; each app below says why not yet.")
        else -> Line("Waiting for music", "Play a song. The full 64-bit chain is idle until a player is detected and allows capture.")
    }

    /** The note under the engine card while it runs; null when the full chain is carrying music. */
    fun idleNote(running: Boolean, fullChainApps: Int, systemEffectsApps: Int): String? = when {
        !running || fullChainApps > 0 -> null
        systemEffectsApps > 0 -> "Your music is detected and is being processed by system effects. The 64-bit DSP is idle until an app allows capture; see the reason on each app below."
        else -> "No music detected yet. Play a song; if it stays undetected, try the optional Music detection options above. The DSP is idle until a player connects."
    }
}
