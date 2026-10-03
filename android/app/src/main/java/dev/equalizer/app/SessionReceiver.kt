package dev.equalizer.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect

/**
 * Standard session hand-off: well-behaved players (Spotify, YouTube Music, most
 * local players) broadcast OPEN/CLOSE when they start/stop an audio session.
 *
 * Players that don't broadcast need "enhanced session detection" (Wavelet's
 * approach, requires `adb shell pm grant dev.equalizer.app android.permission.DUMP`).
 * That is a TODO for the spike: see docs/SPIKE.md.
 */
class SessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "?"
        when (intent.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                val ok = EqController.globalEq.attach(session)
                EqController.log("OPEN  session=$session pkg=$pkg attached=$ok")
            }
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                EqController.globalEq.detach(session)
                EqController.log("CLOSE session=$session pkg=$pkg")
            }
        }
    }
}
