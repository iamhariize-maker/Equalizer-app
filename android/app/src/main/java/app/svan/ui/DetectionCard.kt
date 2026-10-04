package app.svan.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.svan.DetectionSetup
import app.svan.PlaybackSessions
import app.svan.SystemEqService

@Composable
fun DetectionCard() {
    val context = LocalContext.current
    val state by DetectionSetup.state.collectAsState()
    SectionLabel("Music detection")
    SvanCard {
        Column {
            if (state.stage == DetectionSetup.Stage.READY) {
                Text("Enhanced detection enabled", style = MaterialTheme.typography.titleMedium)
                Text("Play YouTube Music, then check Apps & engines below. If it is missing, refresh detection while the song is playing.",
                    style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
                TextButton(onClick = { SystemEqService.refreshDetection(context) }) { Text("Refresh music detection") }
            } else {
                Text("Connect your music", style = MaterialTheme.typography.titleMedium, color = Svan.Gold)
                Text("EQ cannot affect an undetected player. Some players announce themselves; others need enhanced detection.",
                    style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
                Spacer(Modifier.height(8.dp))
                Text("Phone-only setup · Android 11+ · Wi-Fi required", style = MaterialTheme.typography.labelLarge)
                Text("1. Install and open Shizuku.\n2. Follow its Wireless debugging pairing and Start steps.\n3. Return here and enable music detection.",
                    style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
                Text("This grants Android's audio-session discovery permission to Svan. Once enabled, you can stop Shizuku and turn off wireless debugging. No PC or root is needed. Reinstalling Svan resets this permission.",
                    style = MaterialTheme.typography.bodySmall, color = Svan.TextFaint)
                if (state.message.isNotEmpty()) Text(state.message, style = MaterialTheme.typography.bodySmall,
                    color = if (state.stage == DetectionSetup.Stage.ERROR) Svan.Ember else Svan.TextMuted)
                Button(onClick = {
                    when (state.stage) {
                        DetectionSetup.Stage.INSTALL -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                        DetectionSetup.Stage.START -> {
                            val launch = context.packageManager.getLaunchIntentForPackage(DetectionSetup.SHIZUKU_PACKAGE)
                            if (launch != null) context.startActivity(launch) else DetectionSetup.refresh()
                        }
                        else -> DetectionSetup.enable()
                    }
                }, enabled = state.stage != DetectionSetup.Stage.WORKING, modifier = Modifier.fillMaxWidth()) {
                    Text(when (state.stage) {
                        DetectionSetup.Stage.INSTALL -> "Get Shizuku"
                        DetectionSetup.Stage.START -> "Open Shizuku"
                        DetectionSetup.Stage.WORKING -> "Enabling detection…"
                        else -> "Enable music detection"
                    })
                }
                OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://shizuku.rikka.app/guide/setup/#start-via-wireless-debugging"))) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Phone setup guide")
                }
                TextButton(onClick = { DetectionSetup.refresh(); SystemEqService.refreshDetection(context) }) { Text("Check setup again") }
            }
            if (PlaybackSessions.hasDumpPermission(context)) {
                PlaybackSessions.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Svan.Ember) }
            }
        }
    }
}
