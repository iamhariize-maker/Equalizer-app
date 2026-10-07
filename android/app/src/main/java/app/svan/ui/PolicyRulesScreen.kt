package app.svan.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.svan.NativeEngine
import app.svan.svaramanas.PolicyRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PolicyRulesScreen(onClose: () -> Unit) {
    var rules by remember { mutableStateOf(emptyList<PolicyRule>()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.Default) { runCatching { PolicyRule.parse(NativeEngine.nativePolicyRulesJson()) } }
        rules = result.getOrDefault(emptyList()); error = result.exceptionOrNull()?.message
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Svan.Black, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                TextButton(onClick = onClose) { Text("Back") }
                ScreenTitle("How Svaresa decides", "The rules compiled into this version of Svan.")
                Text("These describe bounds and evidence requirements. They are not a live audit of each decision; some evidence gates are still being integrated. Volume is an uncalibrated proxy, not measured sound pressure.", style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
                error?.let { Text("Rules unavailable: $it", color = Svan.Ember) }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                    items(rules, key = PolicyRule::id) { rule ->
                        SvanCard {
                            Column {
                                Text("${rule.id} · v${rule.version} · ${rule.owner}", style = MaterialTheme.typography.titleSmall, color = Svan.Gold)
                                Text(rule.reason, style = MaterialTheme.typography.bodyMedium)
                                Text("${rule.parameter}: ${rule.min} to ${rule.max} ${rule.units}", style = MaterialTheme.typography.bodySmall)
                                Text("Inputs: ${rule.inputs.joinToString().ifEmpty { "ownership/static" }}", style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
                                Text("Confidence ≥ ${rule.confidence}; maximum age ${rule.maxAgeSeconds} s${if (rule.sameEpoch) "; same epoch" else ""}${if (rule.nativeOnly) "; native capture only" else ""}", style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
                                Text("Coordination: ${rule.competes}", style = MaterialTheme.typography.bodySmall)
                                Text("Return: ${rule.rollback}", style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
                            }
                        }
                    }
                }
            }
        }
    }
}
