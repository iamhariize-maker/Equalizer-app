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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.svan.DetectionSetup
import app.svan.PlaybackSessions
import app.svan.PlaybackScanReport
import app.svan.SystemEqService
import java.text.DateFormat
import java.util.Date

@Composable
fun DetectionCard() {
    val context = LocalContext.current
    val state by DetectionSetup.state.collectAsState()
    val report by PlaybackSessions.report.collectAsState()
    var showDetails by remember { mutableStateOf(false) }
    SectionLabel("Music detection")
    SvanCard {
        Column {
            if (state.stage == DetectionSetup.Stage.READY) {
                Text("Enhanced detection enabled", style = MaterialTheme.typography.titleMedium)
                Text("Svan checks again when playback starts, earbuds reconnect, or you unlock your phone. Brief reconnects and effect failures are retried automatically. Check Apps & engines below to see what is connected.",
                    style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
                Text("Your earbuds are the output route; the player app (Apple Music, Neutron, and others) creates the session Svan detects.",
                    style = MaterialTheme.typography.bodySmall, color = Svan.TextFaint)
                TextButton(onClick = { SystemEqService.refreshDetection(context) }) { Text("Refresh music detection") }
                ScanSummary(report, showDetails, { showDetails = !showDetails })
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
        }
    }
}

@Composable
private fun ScanSummary(report: PlaybackScanReport, showDetails: Boolean, onToggleDetails: () -> Unit) {
    if (report.scannedAtMs == 0L && report.error == null) return
    val context = LocalContext.current
    val scannedAt = if (report.scannedAtMs != 0L) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(report.scannedAtMs)) else null
    if (report.error != null) {
        Text(report.error, style = MaterialTheme.typography.bodySmall, color = Svan.Ember)
        return
    }

    val mediaCount = report.mediaSessions.size
    val activeCount = report.mediaSessions.count { it.state == "started" }
    Text(
        "Last scan${scannedAt?.let { " · $it" } ?: ""}: Android tracks ${report.playbackConfigCount}; " +
            "usable media sessions $mediaCount${if (mediaCount > 0) " · playing $activeCount" else ""}.",
        style = MaterialTheme.typography.bodySmall,
        color = Svan.Gold,
    )
    when {
        report.playbackConfigCount == 0 -> Text(
            "Android reported no player track in this scan. Svan keeps checking while active. A Bluetooth reconnect can briefly replace the player's audio session; keep the song playing while it settles.",
            style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted,
        )
        mediaCount == 0 && report.parsedSessionCount == 0 -> Text(
            "Android reported playback entries, but none had both a usable app UID and nonzero session ID. A direct/offload player path or OEM-specific audio report may hide those details; this does not identify the earbuds as the cause.",
            style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted,
        )
        mediaCount == 0 -> Text(
            "Android parsed ${report.parsedSessionCount} session(s), but none were marked as media, game, or unknown usage. Svan leaves other usages alone.",
            style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted,
        )
        else -> {
            val names = report.mediaSessions.map { it.packageName }.distinct().joinToString { packageName ->
                if (packageName.startsWith("uid:")) packageName else runCatching {
                    context.packageManager.getApplicationInfo(packageName, 0).let { app ->
                        context.packageManager.getApplicationLabel(app).toString()
                    }
                }.getOrDefault(packageName)
            }
            Text(
                "Android sees: $names. Check Apps & engines below to see which Svan engine is attached. A player using direct/bit-perfect output may still bypass session effects or capture.",
                style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted,
            )
        }
    }
    if (report.unparsedConfigCount > 0) Text(
        "${report.unparsedConfigCount} audio entry/entries did not expose a usable app and session ID.",
        style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted,
    )
    if (report.configPreview.isNotBlank()) {
        TextButton(onClick = onToggleDetails) { Text(if (showDetails) "Hide Android audio details" else "Show Android audio details") }
        if (showDetails) {
            Text("Local diagnostic only · not uploaded", style = MaterialTheme.typography.labelSmall, color = Svan.TextFaint)
            Text(report.configPreview, style = MaterialTheme.typography.labelSmall, color = Svan.TextFaint)
        }
    }
}
