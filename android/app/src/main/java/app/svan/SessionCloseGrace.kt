package app.svan

/** Worker-owned, bounded debounce for Engine A CLOSE/OPEN churn. Never supplies a new session ID. */
internal class SessionCloseGrace(private val graceMs: Long = 1_500, private val capacity: Int = 64) {
    data class Pending(val sid: Int, val pkg: String, val generation: Long, val dueMs: Long)
    private val pending = linkedMapOf<Int, Pending>()
    fun closing(sid: Int, pkg: String, generation: Long, nowMs: Long): Pending {
        // Duplicate CLOSE cannot indefinitely extend the lifetime of a released track.
        val existing = pending[sid]
        if (existing?.pkg == pkg && existing.generation == generation) return existing
        val next = Pending(sid, pkg, generation, nowMs + graceMs)
        pending[sid] = next
        return next
    }
    fun reopened(sid: Int) { pending.remove(sid) }
    fun expired(nowMs: Long): List<Pending> = pending.values
        .filter { nowMs >= it.dueMs }.also { expired -> expired.forEach { pending.remove(it.sid) } }
    fun overflow(): List<Pending> = pending.values.take((pending.size - capacity).coerceAtLeast(0))
        .also { removed -> removed.forEach { pending.remove(it.sid) } }
    fun contains(sid: Int): Boolean = pending.containsKey(sid)
    fun nextDelay(nowMs: Long): Long? = pending.values.minOfOrNull { (it.dueMs - nowMs).coerceAtLeast(0) }
    fun clear() { pending.clear() }
}
