package app.svan.testsource

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * adb shell am start -n <pkg>/app.svan.testsource.ToneActivity \
 *     --ef freq 1000 --ef amp 0.25 --ez broadcast true [--ez explicit false]
 * adb shell am start -n <pkg>/app.svan.testsource.ToneActivity --ez stop true
 * Forwards to TonePlayerService (a real foreground media service) and closes.
 */
class ToneActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forward(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        forward(intent)
        finish()
    }

    private fun forward(i: Intent) {
        val svc = Intent(this, TonePlayerService::class.java).putExtras(i)
        if (i.getBooleanExtra("stop", false)) {
            // stop goes through the service so it can close the AudioTrack and send CLOSE
            startService(svc)
        } else {
            startForegroundService(svc)
        }
    }
}
