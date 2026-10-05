package app.svan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect

/**
 * Runtime receiver owned by the foreground service. Players may broadcast
 * OPEN/CLOSE explicitly or implicitly when they create/release a session.
 *
 * Players that don't broadcast are found through the audio-service dump instead
 * (PlaybackSessions; enabled by the phone-only detection setup).
 * Either way, SessionRouter decides which engine owns the session.
 */
class SessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!SystemEqService.isRunning) return
        SystemEqService.onSessionSignal()
        val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "?"
        when (intent.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                val uid = try {
                    context.packageManager.getApplicationInfo(pkg, 0).uid
                } catch (e: Exception) {
                    -1 // resolved from the dump by SessionRouter
                }
                EqController.log("OPEN  session=$session pkg=$pkg")
                SessionRouter.init(context)
                SessionRouter.sessionOpened(session, pkg, uid)
            }
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                EqController.log("CLOSE session=$session pkg=$pkg")
                SessionRouter.sessionClosed(session)
            }
        }
    }
}
