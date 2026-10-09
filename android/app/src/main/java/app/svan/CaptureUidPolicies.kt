package app.svan

import android.content.Context
import android.os.SystemClock

/** The audio server combines this UID-wide policy with each player's attributes. */
internal data class CaptureUidPolicyReport(val policies: Map<Int, Int>) {
    fun blocks(uid: Int): Boolean = policies[uid]?.let {
        it and (PlaybackSession.FLAG_NO_MEDIA_PROJECTION or PlaybackSession.FLAG_NO_SYSTEM_CAPTURE) != 0
    } == true

    companion object {
        private val entry = Regex("""^\s*-\s+uid=(\d+)\s+flag_mask=(0[xX][0-9a-fA-F]+|\d+)\s*$""")

        /** Read only the current AOSP policy table, never event history or an unrelated UID field. */
        fun parse(dump: String): CaptureUidPolicyReport? {
            var inTable = false
            val policies = linkedMapOf<Int, Int>()
            for (line in dump.lineSequence()) {
                if (!inTable) {
                    if (line.trim() == "AllowedCapturePolicies:") inTable = true
                    continue
                }
                if (line.isBlank()) continue
                val fields = entry.matchEntire(line)?.groupValues ?: break
                val uid = fields[1].toIntOrNull() ?: return null
                val mask = if (fields[2].startsWith("0x", true)) fields[2].drop(2).toLongOrNull(16)
                    else fields[2].toLongOrNull()
                if (mask == null || mask !in 0..0xffffffffL || uid < 0) return null
                policies[uid] = (policies[uid] ?: 0) or mask.toInt()
            }
            return if (inTable) CaptureUidPolicyReport(policies) else null
        }
    }
}

/** Fixed, bounded, read-only policy report through the existing DUMP/Shizuku route. No new grant. */
internal object CaptureUidPolicies {
    private data class Sample(val atMs: Long, val report: CaptureUidPolicyReport?, val detail: String)
    @Volatile private var sample: Sample? = null

    @Synchronized fun read(context: Context, uid: Int): Int? {
        if (!PlaybackSessions.hasReportAccess(context)) return null
        val now = SystemClock.elapsedRealtime()
        val previous = sample
        // P1 and P2 use the same recent table so a slow OEM dump cannot prolong the muted check.
        val current = if (previous != null && now - previous.atMs < 5_000) previous else {
            val result = PlaybackSessions.readService("media.audio_policy", 1_500L, 2 * 1024 * 1024)
            val parsed = result.text?.let(CaptureUidPolicyReport::parse)
            Sample(SystemClock.elapsedRealtime(), parsed,
                if (parsed != null) "current UID policy table" else result.error ?: "policy table not exposed by Android")
                .also { sample = it }
        }
        return current.report?.policies?.get(uid)
    }

    fun forget() { sample = null }

    fun describe(uid: Int): String {
        val current = sample ?: return "UID capture policy: not read (enhanced report access may be unavailable)"
        val age = (SystemClock.elapsedRealtime() - current.atMs).coerceAtLeast(0)
        val mask = current.report?.policies?.get(uid)
        return "UID capture policy: ${if (mask != null) "flag_mask=0x${mask.toUInt().toString(16)}" else if (current.report != null) "no UID override reported" else current.detail}; ageMs=$age"
    }
}

internal class ProbePolicyDenied(val mask: Int) : IllegalStateException("Android UID policy disables media projection capture")
