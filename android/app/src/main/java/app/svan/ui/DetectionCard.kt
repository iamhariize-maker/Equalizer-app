package app.svan.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import app.svan.DiagnosticReport
import app.svan.PlaybackSessions
import app.svan.PlaybackScanReport
import app.svan.SystemEqService
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DetectionCard(captureStats: app.svan.CaptureService.Stats? = null) {
    val context = LocalContext.current
    val report by PlaybackSessions.report.collectAsState()
    val shell by app.svan.ShizukuAudioReports.state.collectAsState()
    var showDetails by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(app.svan.OnboardingAndroid.working(context)) }
    var wholeMix by remember { mutableStateOf(app.svan.EqController.globalEq.mixFallbackOn) }
    val settings by app.svan.SvanRepository.settings.collectAsState()
    ObserveWhileVisible {
        working = app.svan.OnboardingAndroid.working(context)
        wholeMix = app.svan.EqController.globalEq.mixFallbackOn
    }
    SectionLabel("Music detection")
    Text(when {
        shell.stage == app.svan.ShizukuAudioReports.Stage.READY && app.svan.ShizukuAudioReports.ready -> "Enhanced detection · Shizuku is connected"
        PlaybackSessions.hasDumpPermission(context) -> "Enhanced detection · built into Svan (Shizuku not needed)"
        else -> "Basic detection · no extra setup needed for supported players"
    }, style = MaterialTheme.typography.bodySmall, color = Svan.Gold)
    Text("Detection finds player connections; it does not change EQ bandwidth. Full native DSP and Recording mode require a capturable music source.",
        style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
    KeepEnhancedCard()
    WorkingStatusCard(working, stats = captureStats)
    if (wholeMix) Text("A player is hiding its audio connection, so your EQ is on the whole phone output for now " +
        "(notification sounds included). It switches back as soon as a player connects.",
        style = MaterialTheme.typography.bodySmall, color = Svan.Gold)
    SettingSwitchRow("Whole-phone EQ for hidden players",
        "When a playing app doesn't announce its audio and none is connected, apply your EQ to the whole output mix. " +
            "No extra permission. Experimental: some phones allow it only on one output; attachment does not verify your music path.",
        settings.wholeMixFallback, { on -> app.svan.SvanRepository.updateSettings { it.copy(wholeMixFallback = on) }; SystemEqService.refreshDetection(context) })
    SvanCard {
        Column {
            SetupHelpLinks()
            TextButton(onClick = { SystemEqService.refreshDetection(context) }) { Text("Refresh music detection") }
            ScanSummary(report, showDetails, { showDetails = !showDetails })
            Text("Detailed reports stay local until you choose to share them in Android's share sheet.",
                style = MaterialTheme.typography.labelSmall, color = Svan.TextMuted)
            DetailedReportButton()
        }
    }
    SharedOutputCard()
}

@Composable
private fun SharedOutputCard() {
    val shared by app.svan.SharedOutput.status.collectAsState()
    SvanCard {
        Column {
            Text("Shared-output EQ · experimental", style = MaterialTheme.typography.titleMedium, color = Svan.Gold)
            Text(shared.message, style = MaterialTheme.typography.bodyMedium)
            Text("Can apply system EQ without a player's session announcement on supported outputs. May affect other sounds sharing that output. Player names, per-app isolation and direct/offload support are unavailable here.",
                style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
            Text("Test with your music and headphones. Output changes stop this option. Use per-player connections for the audiophile engine and Recording mode.",
                style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
            OutlinedButton(onClick = { app.svan.SessionRouter.setSharedOutput(!shared.requested) }) {
                Text(if (shared.requested) "Stop shared-output EQ" else "Try shared-output EQ")
            }
        }
    }
}

@Composable
private fun DetailedReportButton() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    TextButton(onClick = {
        scope.launch {
            val text = withContext(Dispatchers.Default) { DiagnosticReport.build(context).take(180_000) }
            context.startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Svan diagnostic report")
                    .putExtra(Intent.EXTRA_TEXT, text), "Share diagnostic report"))
        }
    }) { Text("Share detailed report") }
}

@Composable
private fun ScanSummary(report: PlaybackScanReport, showDetails: Boolean, onToggleDetails: () -> Unit) {
    if (report.scannedAtMs == 0L && report.error == null) return
    val context = LocalContext.current
    val scannedAt = if (report.scannedAtMs != 0L) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(report.scannedAtMs)) else null
    if (report.error != null) {
        Text("Android audio details are unavailable. Keep music playing and retry the scan.",
            style = MaterialTheme.typography.bodySmall, color = Svan.Ember)
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
