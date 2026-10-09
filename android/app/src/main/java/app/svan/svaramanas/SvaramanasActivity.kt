package app.svan.svaramanas

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.svan.SvanRepository
import app.svan.ui.Svan
import app.svan.ui.SvanTheme
import app.svan.ui.SvaramanasPanel

/**
 * The Svaramanas dialog as its own translucent activity, so the bubble, the
 * Quick Settings tile and the notification can open it over any player.
 */
class SvaramanasActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.svan.ui.SvanAppearance.initialize(this)
        SvanRepository.init(this)
        setContent {
            SvanTheme {
                val light = app.svan.ui.SvanAppearance.current.isLight
                androidx.compose.runtime.DisposableEffect(light) {
                    val bars = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                    bars.isAppearanceLightStatusBars = light
                    bars.isAppearanceLightNavigationBars = light
                    onDispose { }
                }
                val bubble by Svaramanas.bubble.collectAsState()
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Svan.Black.copy(alpha = 0.55f))
                        .clickable(remember { MutableInteractionSource() }, indication = null) { finish() },
                ) {
                    SvaramanasPanel(
                        onDone = { finish() },
                        bubbleOn = bubble,
                        onBubbleChange = { on -> setBubble(on) },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .clickable(remember { MutableInteractionSource() }, indication = null) {}
                            .navigationBarsPadding(),
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        SvaramanasBubbleService.appScreenStarted()
    }

    override fun onStop() {
        SvaramanasBubbleService.appScreenStopped()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Returning from the overlay-permission screen: finish what the switch started.
        if (wantBubble && Settings.canDrawOverlays(this)) { wantBubble = false; Svaramanas.setBubble(this, true) }
    }

    private fun setBubble(on: Boolean) {
        if (on && !Settings.canDrawOverlays(this)) {
            wantBubble = true
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        Svaramanas.setBubble(this, on)
    }

    companion object {
        private var wantBubble = false

        fun open(context: Context) = context.startActivity(
            Intent(context, SvaramanasActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }
}
