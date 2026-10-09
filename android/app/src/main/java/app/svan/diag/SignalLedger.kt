package app.svan.diag

/**
 * Bounded, in-memory history of the signals Svan uses to find players: session announcements (and what
 * became of each), playback-callback changes, output-device changes, service lifecycle and scan summaries.
 *
 * Detection problems are usually "the signal never arrived" or "it arrived and was dropped". Without this
 * record those two cases look identical. It holds package names, session ids and short reasons only: no audio,
 * no titles, no account data. It resets with the process, and [processStartMs] says when that was.
 */
object SignalLedger {
    enum class Kind { BROADCAST, PLAYBACK, DEVICE, SERVICE, DETECTION, SELFTEST }

    data class Event(val atMs: Long, val kind: Kind, val pkg: String?, val detail: String)

    /** Per-kind caps, so a chatty signal (playback callbacks) can never push out a rare one (announcements). */
    private fun cap(kind: Kind) = when (kind) {
        Kind.BROADCAST -> 300
        Kind.SELFTEST -> 20
        else -> 150
    }
    private val byKind = java.util.EnumMap<Kind, ArrayDeque<Event>>(Kind::class.java)
    @Volatile var processStartMs: Long = System.currentTimeMillis()
        private set
    @Volatile var serviceStartedMs: Long = 0L
        private set

    @Synchronized
    fun record(kind: Kind, detail: String, pkg: String? = null, nowMs: Long = System.currentTimeMillis()) {
        val q = byKind.getOrPut(kind) { ArrayDeque() }
        val text = detail.take(300)
        val last = q.lastOrNull()
        // The same observation repeated within two seconds adds nothing.
        if (last != null && last.detail == text && last.pkg == pkg && nowMs - last.atMs < 2_000) return
        q.addLast(Event(nowMs, kind, pkg, text))
        while (q.size > cap(kind)) q.removeFirst()
    }

    fun serviceStarted(nowMs: Long = System.currentTimeMillis()) {
        serviceStartedMs = nowMs
        record(Kind.SERVICE, "system service started", nowMs = nowMs)
    }

    @Synchronized fun snapshot(): List<Event> = byKind.values.flatten().sortedBy { it.atMs }

    @Synchronized
    fun clearForTest(nowMs: Long = System.currentTimeMillis()) {
        byKind.clear()
        processStartMs = nowMs
        serviceStartedMs = 0L
    }
}

/** What Svan has seen from one app's session announcements since the ledger started. */
data class AnnouncementSummary(
    val opens: Int,
    val closes: Int,
    val accepted: Int,
    val dropped: Int,
    val lastOpenMs: Long?,
    val lastAcceptedMs: Long?,
    /** Distinct drop reasons with counts, most frequent first. */
    val dropReasons: List<Pair<String, Int>>,
    val sessions: Set<Int>,
)

object AnnouncementAnalysis {
    private val sessionRe = Regex("""session=(-?\d+)""")

    /** Broadcast events are written as `<OPEN|CLOSE> session=<n> <accepted|dropped: reason>`. */
    fun summarize(events: List<SignalLedger.Event>, pkg: String): AnnouncementSummary {
        val mine = events.filter { it.kind == SignalLedger.Kind.BROADCAST && it.pkg == pkg }
        val opens = mine.filter { it.detail.startsWith("OPEN") }
        val closes = mine.count { it.detail.startsWith("CLOSE") }
        // Only OPEN announcements start detection, so only they count as accepted or dropped.
        val accepted = opens.filter { it.detail.contains(" accepted") }
        val dropped = opens.filter { it.detail.contains(" dropped: ") }
        val reasons = dropped.groupingBy { it.detail.substringAfter(" dropped: ").trim() }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key to it.value }
        val sessions = mine.mapNotNull { sessionRe.find(it.detail)?.groupValues?.get(1)?.toIntOrNull() }.filter { it > 0 }.toSet()
        return AnnouncementSummary(
            opens = opens.size, closes = closes, accepted = accepted.size, dropped = dropped.size,
            lastOpenMs = opens.maxOfOrNull { it.atMs }, lastAcceptedMs = accepted.maxOfOrNull { it.atMs },
            dropReasons = reasons, sessions = sessions,
        )
    }

    /** Announcements from every app, so a diagnostic can say who *did* announce when the target did not. */
    fun announcers(events: List<SignalLedger.Event>): Map<String, Int> =
        events.filter { it.kind == SignalLedger.Kind.BROADCAST && it.detail.startsWith("OPEN") && it.pkg != null }
            .groupingBy { it.pkg!! }.eachCount()
}
