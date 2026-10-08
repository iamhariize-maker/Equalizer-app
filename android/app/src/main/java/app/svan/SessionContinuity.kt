package app.svan

/** Worker-owned connection history. Closed entries are hints, never attach/capture authority. */
internal class SessionConnectionHistory(private val maxClosed: Int = 32, private val retentionMs: Long = 120_000) {
    data class Entry(val sessionId: Int, val pkg: String, val uid: Int, val generation: Long,
                     val openedMs: Long, val closedMs: Long? = null)
    private val entries = linkedMapOf<Int, Entry>()
    private var generation = 0L
    fun active(sid: Int): Entry? = entries[sid]?.takeIf { it.closedMs == null }
    fun opened(sid: Int, pkg: String, uid: Int, nowMs: Long): Long {
        prune(nowMs)
        val current = active(sid)
        if (current?.pkg == pkg && current.uid == uid) return current.generation
        entries.remove(sid)
        entries[sid] = Entry(sid, pkg, uid, ++generation, nowMs)
        return generation
    }
    fun closed(sid: Int, pkg: String, nowMs: Long): Boolean {
        val current = active(sid) ?: return false
        if (current.pkg != pkg) return false
        entries[sid] = current.copy(closedMs = nowMs)
        prune(nowMs)
        return true
    }
    fun recent(nowMs: Long): List<Entry> { prune(nowMs); return entries.values.filter { it.closedMs != null } }
    fun clear() { entries.clear() }
    private fun prune(nowMs: Long) {
        entries.entries.removeAll { it.value.closedMs?.let { closed -> nowMs - closed >= retentionMs } == true }
        val closed = entries.values.filter { it.closedMs != null }.sortedBy { it.closedMs }
        closed.take((closed.size - maxClosed).coerceAtLeast(0)).forEach { entries.remove(it.sessionId) }
    }
}

internal object SessionAnnouncement {
    fun valid(sid: Int, pkg: String, uid: Int, ownUid: Int): Boolean =
        sid > 0 && pkg.isNotBlank() && uid >= 0 && uid != ownUid

    /**
     * A claimed owner is inconsistent only when the audio service attributes the session to a different
     * *app* uid. Unknown reports and system owners (media server, below the first app uid) are accepted,
     * because some players render through platform services.
     */
    fun consistent(claimedUid: Int, observedUid: Int?): Boolean =
        claimedUid < 0 || observedUid == null || observedUid < FIRST_APP_UID || observedUid == claimedUid

    private const val FIRST_APP_UID = 10_000
}

internal object SharedOutputPolicy {
    fun allowed(requested: Boolean, service: Boolean, capture: Boolean) = requested && service && !capture
}

/**
 * How long a player stays on Engine A after its capture proved silent while it played. The first time
 * may be transient (an ad, one stream), so retry after 3 minutes; a second time suggests this phone
 * routes that player where capture cannot hear it, so wait 15 minutes; after a third, stay on Engine A
 * for the rest of the capture session. Nothing is saved: a new session tries again.
 */
internal object FailOpenBackoff {
    fun blockMs(count: Int): Long = when {
        count <= 1 -> 3 * 60_000L
        count == 2 -> 15 * 60_000L
        else -> Long.MAX_VALUE
    }
    fun describe(count: Int): String = when {
        count <= 1 -> "for 3 minutes"
        count == 2 -> "for 15 minutes (second silent capture)"
        else -> "until capture restarts (repeated silent capture)"
    }
}
