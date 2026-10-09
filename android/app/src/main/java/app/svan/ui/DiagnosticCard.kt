package app.svan.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.svan.SessionRouter
import app.svan.diag.DiagnosticEngine
import app.svan.diag.KnownPlayers
import app.svan.diag.Severity

/**
 * One tap to measure why a player is not detected or not captured on this phone, in a form that can be sent
 * to the developer. Nothing leaves the device until the user shares the text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticCard() {
    val context = LocalContext.current
    val state by DiagnosticEngine.state.collectAsState()
    val candidates = remember { KnownPlayers.candidates(context, SessionRouter.snapshot.map { it.pkg }) }
    var pkg by remember { mutableStateOf(candidates.firstOrNull()?.first ?: "com.spotify.music") }
    var lab by remember { mutableStateOf(true) }
    var disruptive by remember { mutableStateOf(false) }
    var delay by remember { mutableIntStateOf(0) }
    val running = state.stage == DiagnosticEngine.Stage.RUNNING

    SvanCard {
        Column {
            Text("Full diagnostic", style = MaterialTheme.typography.titleMedium, color = Svan.Gold)
            Text("Measures why a player is not found or not captured on this phone: your Android build and its battery rules, " +
                "whether the player announces itself, what Android's audio server does with its stream, and a live capture test. " +
                "Keep the player playing while it runs. Nothing is uploaded; you choose whether to share the result.",
                style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
            Spacer(Modifier.height(8.dp))
            Text("Player to check", style = MaterialTheme.typography.labelMedium, color = Svan.TextMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                candidates.forEach { (id, label) -> Pill(label, pkg == id, { pkg = id }, enabled = !running) }
            }
            SettingSwitchRow("Run the capture test",
                "Tries several ways of capturing the player for a few seconds each, while it keeps playing. Needs the audiophile engine to be started in Hi-Fi, with only this player playing.",
                lab, { lab = it }, enabled = !running)
            SettingSwitchRow("Also test with Svan's effect detached or the player muted",
                "Adds two tests that briefly leave the player unprocessed or silent (a few seconds each) and then restore it. Only needed when the normal tests find nothing.",
                disruptive, { disruptive = it }, enabled = lab && !running)
            Text("When to start", style = MaterialTheme.typography.labelMedium, color = Svan.TextMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Right now", delay == 0, { delay = 0 }, enabled = !running && lab)
                Pill("After 10 s, so I can leave Svan", delay == 10, { delay = 10 }, enabled = !running && lab)
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(enabled = !running, onClick = {
                DiagnosticEngine.start(context, DiagnosticEngine.Request(pkg, lab, lab && disruptive, if (lab) delay else 0))
            }) { Text(if (running) "Running…" else "Run full diagnostic") }
            if (running) Text(state.progress, style = MaterialTheme.typography.bodySmall, color = Svan.Gold)
            state.error?.let { Text("The diagnostic could not finish: $it", style = MaterialTheme.typography.bodySmall, color = Svan.Ember) }
            state.result?.let { r ->
                val worst = r.findings.maxOfOrNull { it.severity } ?: Severity.OK
                Text(if (worst >= Severity.WARN) "Result" else "Result: nothing wrong was measured",
                    style = MaterialTheme.typography.titleSmall, color = if (worst == Severity.FAIL) Svan.Ember else Svan.Gold)
                SelectionContainer {
                    Text(r.summary, style = MaterialTheme.typography.labelSmall, color = Svan.Text)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { copy(context, "Svan diagnostic summary", r.summary) }) { Text("Copy summary") }
                    TextButton(onClick = { copy(context, "Svan full diagnostic", r.full) }) { Text("Copy full report") }
                    TextButton(onClick = { share(context, r.full) }) { Text("Share full report") }
                }
                Text("Send the summary first; the full report adds the raw Android audio-server lines.",
                    style = MaterialTheme.typography.labelSmall, color = Svan.TextFaint)
            }
        }
    }
}

private fun copy(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text.take(180_000)))
}

private fun share(context: Context, text: String) {
    context.startActivity(Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Svan full diagnostic")
            .putExtra(Intent.EXTRA_TEXT, text.take(180_000)), "Share diagnostic"))
}
