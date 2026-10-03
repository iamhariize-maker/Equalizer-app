package dev.equalizer.app

import android.media.audiofx.DynamicsProcessing
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Silences an app's own output so only Engine B's processed copy is heard.
 *
 * Playback capture taps a player's audio *before* its session effects (the
 * technique RootlessJamesDSP relies on, and consistent with our Visualizer
 * probe seeing pre-effect audio). So a top-priority DynamicsProcessing with its
 * input gain at -200 dB mutes the original while the capture stays clean.
 *
 * DynamicsProcessing instances on one session share a single engine, so a muted
 * session must never also get Engine A's EQ. [SessionRouter] guarantees that.
 */
class SourceMuter(private val onLost: (sessionId: Int) -> Unit) {

    private val muted = ConcurrentHashMap<Int, DynamicsProcessing>()

    val sessions: Set<Int> get() = muted.keys

    fun mute(sessionId: Int): Boolean {
        if (muted.containsKey(sessionId)) return true
        return try {
            val dp = DynamicsProcessing(Int.MAX_VALUE, sessionId, null)
            silence(dp)
            // Another effect app (or the player) can disable us or take control:
            // re-assert, or report the loss so the router can react.
            dp.setEnableStatusListener { fx, enabled -> if (!enabled) reassert(sessionId, fx as DynamicsProcessing) }
            dp.setControlStatusListener { fx, granted ->
                if (granted) reassert(sessionId, fx as DynamicsProcessing) else onLost(sessionId)
            }
            muted[sessionId] = dp
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot mute session $sessionId", e)
            false
        }
    }

    fun unmute(sessionId: Int) {
        muted.remove(sessionId)?.let {
            try {
                it.enabled = false
            } finally {
                it.release()
            }
        }
    }

    fun releaseAll() = muted.keys.toList().forEach(::unmute)

    private fun reassert(sessionId: Int, dp: DynamicsProcessing) {
        try {
            silence(dp)
        } catch (e: RuntimeException) {
            Log.w(TAG, "lost control of session $sessionId", e)
            onLost(sessionId)
        }
    }

    private fun silence(dp: DynamicsProcessing) {
        dp.setInputGainAllChannelsTo(MUTE_DB)
        dp.enabled = true
    }

    private companion object {
        const val TAG = "SourceMuter"
        const val MUTE_DB = -200f
    }
}
