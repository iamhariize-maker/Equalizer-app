package app.svan.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.svan.DetectionSetup
import app.svan.DumpGrant
import app.svan.PlaybackSessions
import rikka.shizuku.Shizuku

/**
 * One tap while Shizuku is connected moves enhanced detection into Svan itself, so Shizuku can be
 * uninstalled (banking and payment apps often refuse to run while it or Developer options is on).
 */
@Composable
fun KeepEnhancedCard() {
    val context = LocalContext.current
    val grant by DumpGrant.state.collectAsState()
    var hasDump by remember { mutableStateOf(PlaybackSessions.hasDumpPermission(context)) }
    var shizukuReady by remember { mutableStateOf(false) }
    var shizukuInstalled by remember { mutableStateOf(false) }
    ObserveWhileVisible {
        hasDump = PlaybackSessions.hasDumpPermission(context)
        shizukuReady = runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        shizukuInstalled = runCatching { context.packageManager.getApplicationInfo(DetectionSetup.SHIZUKU_PACKAGE, 0) }.isSuccess
    }
    fun open(intent: Intent) { runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

    when {
        hasDump && (shizukuInstalled || grant.stage == DumpGrant.Stage.GRANTED) -> SvanCard {
            Column {
                Text("Enhanced detection is built into Svan ✓", style = MaterialTheme.typography.titleMedium, color = Svan.Gold)
                Text("Svan no longer needs Shizuku. You can uninstall Shizuku and turn off Developer options, " +
                    "and your banking and payment apps will work again. Enhanced detection stays on, also after a restart, " +
                    "until you uninstall Svan.", style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
                if (shizukuInstalled) Button(onClick = {
                    open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${DetectionSetup.SHIZUKU_PACKAGE}")))
                }, modifier = Modifier.fillMaxWidth()) { Text("1. Uninstall Shizuku") }
                OutlinedButton(onClick = { open(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) },
                    modifier = Modifier.fillMaxWidth()) { Text(if (shizukuInstalled) "2. Turn off Developer options" else "Turn off Developer options") }
                Text("On the Shizuku page tap Uninstall. In Developer options, switch off the switch at the top.",
                    style = MaterialTheme.typography.labelSmall, color = Svan.TextMuted)
            }
        }
        !hasDump && shizukuReady -> SvanCard {
            Column {
                Text("Want to remove Shizuku?", style = MaterialTheme.typography.titleMedium, color = Svan.Gold)
                Text("Tap once and Svan keeps enhanced detection by itself. After that you can uninstall Shizuku and turn off " +
                    "Developer options, for example so payment apps work, and players that hide their audio are still found and get your full sound settings.",
                    style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
                val working = grant.stage == DumpGrant.Stage.WORKING
                Button(onClick = { DumpGrant.grant(context) }, enabled = !working, modifier = Modifier.fillMaxWidth()) {
                    Text(if (working) "Setting up…" else "Keep enhanced detection without Shizuku")
                }
                if (grant.stage == DumpGrant.Stage.FAILED) Text(grant.message, style = MaterialTheme.typography.bodySmall, color = Svan.Ember)
                Text("This gives Svan Android's system-report permission (DUMP). Svan uses it only to see which apps are playing audio; " +
                    "nothing else on your phone changes. Uninstalling Svan removes it.", style = MaterialTheme.typography.labelSmall, color = Svan.TextMuted)
            }
        }
    }
}
