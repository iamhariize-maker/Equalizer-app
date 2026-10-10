package app.svan

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock

/**
 * When to apply the EQ to the whole output mix (session 0) because a playing app hides its audio session.
 *
 * Android anonymises other apps' playback for normal apps (no package, no session), and Svan never
 * declares a notification listener (Play Protect treats one as a fraud risk). So an unannounced
 * player can only be processed on the output mix. That is only safe while nothing Svan routes is
 * playing; otherwise the routed player would be equalised twice. A short hold keeps the normal
 * moment before a player is routed from switching the mix effect on and off.
 */
class MixFallbackPolicy(private val holdMs: Long = 3_000L, private val pauseGraceMs: Long = 30_000L) {
    companion object {
        /** In a phone call or a voice/video chat the output mix carries the other person's voice: never equalise it. */
        fun callActive(mode: Int): Boolean = mode == android.media.AudioManager.MODE_IN_CALL ||
            mode == android.media.AudioManager.MODE_IN_COMMUNICATION || mode == android.media.AudioManager.MODE_RINGTONE
    }

    private var unroutedSinceMs = -1L
    private var on = false
    private var quietSinceMs = -1L

    /**
     * Off at once for a routed player, capture, EQ or setting changes (never equalise twice). Pauses
     * and gaps between songs keep it on for [pauseGraceMs], so each track does not switch the mix
     * effect off and on again.
     */
    fun next(nowMs: Long, allowed: Boolean, musicActive: Boolean, anonymousPlaying: Int?, routedPlaying: Int): Boolean {
        if (!allowed || routedPlaying > 0) { reset(); return false }
        val playing = musicActive && (anonymousPlaying ?: 0) > 0
        if (on) {
            if (playing) { quietSinceMs = -1L; return true }
            if (quietSinceMs < 0) quietSinceMs = nowMs
            if (nowMs - quietSinceMs < pauseGraceMs) return true
            reset(); return false
        }
        if (!playing) { unroutedSinceMs = -1L; return false }
        if (unroutedSinceMs < 0) unroutedSinceMs = nowMs
        on = nowMs - unroutedSinceMs >= holdMs
        return on
    }

    private fun reset() { on = false; unroutedSinceMs = -1L; quietSinceMs = -1L }
}

object MixFallback {
    private val policy = MixFallbackPolicy()

    /** Detection worker only (after each scan). */
    @Synchronized
    fun evaluate(context: Context) {
        val eq = SvanRepository.eq.value
        val allowed = SvanRepository.settings.value.wholeMixFallback && eq.enabled && SystemEqService.isRunning && !CaptureService.isRunning && !SharedOutput.status.value.requested
        val audio = context.getSystemService(AudioManager::class.java)
        // Never equalise the output mix during a call or a voice/video chat.
        val inCall = MixFallbackPolicy.callActive(runCatching { audio.mode }.getOrDefault(AudioManager.MODE_NORMAL))
        val routedPlaying = SessionRouter.snapshot.count {
            (it.owner == SessionRouter.Owner.ENGINE_A || it.owner == SessionRouter.Owner.ENGINE_B_MUTED) && it.playing != false
        }
        val want = policy.next(SystemClock.elapsedRealtime(), allowed && !inCall, runCatching { audio.isMusicActive }.getOrDefault(false),
            DetectionMonitor.publicActiveCount(context), routedPlaying)
        EqController.globalEq.setMixFallback(want)
    }
}
