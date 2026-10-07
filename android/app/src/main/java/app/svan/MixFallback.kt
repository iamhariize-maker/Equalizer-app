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
class MixFallbackPolicy(private val holdMs: Long = 3_000L) {
    private var unroutedSinceMs = -1L

    fun next(nowMs: Long, allowed: Boolean, musicActive: Boolean, anonymousPlaying: Int?, routedPlaying: Int): Boolean {
        val unrouted = allowed && musicActive && routedPlaying == 0 && (anonymousPlaying ?: 0) > 0
        if (!unrouted) { unroutedSinceMs = -1L; return false }
        if (unroutedSinceMs < 0) unroutedSinceMs = nowMs
        return nowMs - unroutedSinceMs >= holdMs
    }
}

object MixFallback {
    private val policy = MixFallbackPolicy()

    /** Detection worker only (after each scan). */
    @Synchronized
    fun evaluate(context: Context) {
        val eq = SvanRepository.eq.value
        val allowed = SvanRepository.settings.value.wholeMixFallback && eq.enabled && SystemEqService.isRunning && !CaptureService.isRunning
        val audio = context.getSystemService(AudioManager::class.java)
        val routedPlaying = SessionRouter.snapshot.count {
            (it.owner == SessionRouter.Owner.ENGINE_A || it.owner == SessionRouter.Owner.ENGINE_B_MUTED) && it.playing != false
        }
        val want = policy.next(SystemClock.elapsedRealtime(), allowed, runCatching { audio.isMusicActive }.getOrDefault(false),
            DetectionMonitor.publicActiveCount(context), routedPlaying)
        EqController.globalEq.setMixFallback(want)
    }
}
