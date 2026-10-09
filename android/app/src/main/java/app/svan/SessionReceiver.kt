package app.svan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Process
import app.svan.diag.SignalLedger

/**
 * Runtime receiver owned by the foreground service. Players may broadcast
 * OPEN/CLOSE explicitly or implicitly when they create/release a session.
 *
 * Players that don't broadcast are found through the audio-service dump instead
 * (PlaybackSessions; enabled by the phone-only detection setup).
 * Either way, SessionRouter decides which engine owns the session.
 *
 * Every announcement is written to the [SignalLedger] with what became of it, because "never arrived" and
 * "arrived and was dropped" need different fixes. Behaviour is unchanged by that bookkeeping.
 */
class SessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Svan's own delivery self-test (see diag.DiagnosticEngine): proves broadcasts reach this receiver.
        intent.getStringExtra(EXTRA_SELF_TEST)?.let { token ->
            SignalLedger.record(SignalLedger.Kind.SELFTEST, "token=$token")
            return
        }
        val action = when (intent.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> "OPEN"
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> "CLOSE"
            else -> intent.action ?: "?"
        }
        val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
        fun drop(reason: String) { SignalLedger.record(SignalLedger.Kind.BROADCAST, "$action session=$session dropped: $reason", pkg) }
        if (!SystemEqService.isRunning) { drop("Svan's service was not running"); return }
        if (pkg == null) { drop("no package name in the announcement"); return }
        val uid = runCatching { context.packageManager.getApplicationInfo(pkg, 0).uid }.getOrDefault(-1)
        if (!SessionAnnouncement.valid(session, pkg, uid, Process.myUid())) {
            drop(when {
                session <= 0 -> "invalid session id"
                uid < 0 -> "Svan cannot see that package (uid unknown)"
                uid == Process.myUid() -> "announced by Svan itself"
                else -> "invalid announcement"
            })
            return
        }
        if (intent.getIntExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC) == AudioEffect.CONTENT_TYPE_VOICE) {
            drop("voice content, not music"); return
        }
        SignalLedger.record(SignalLedger.Kind.BROADCAST, "$action session=$session accepted", pkg)
        SystemEqService.onSessionSignal()
        when (intent.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                EqController.log("OPEN  session=$session pkg=$pkg")
                SessionRouter.init(context)
                SessionRouter.sessionOpened(session, pkg, uid)
            }
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                EqController.log("CLOSE session=$session pkg=$pkg")
                SessionRouter.sessionClosed(session, pkg)
            }
        }
    }

    companion object {
        const val EXTRA_SELF_TEST = "app.svan.SELF_TEST_TOKEN"
    }
}
