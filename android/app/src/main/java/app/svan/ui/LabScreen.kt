package app.svan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.svan.EqController

/** Diagnostics from the spike, kept for testing on new devices. */
@Composable
fun LabScreen(actions: List<Pair<String, () -> Unit>>, onOpenEq: () -> Unit = {}) {
    var diagnostics by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (!diagnostics) {
        IntegratedLabPanel(onOpenEq, onDiagnostics = { diagnostics = true })
        return
    }
    var log by remember { mutableStateOf("") }
    ObserveWhileVisible(intervalMs = 800) {
        log = synchronized(EqController.log) { EqController.log.toString() }.takeLast(12000)
    }
    Column(Modifier.fillMaxSize().padding(if (SvanAppearance.current == AppearanceTheme.ORIGINAL) 16.dp else 24.dp)) {
        ScreenTitle("Device probes", "Device probes and the engine log.")
        androidx.compose.material3.TextButton(onClick = { diagnostics = false }) { Text("Back to Svan Lab") }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEach { (label, action) -> Pill(label, false, action) }
        }
        Spacer(Modifier.height(12.dp))
        SectionLabel("Observed on this device")
        SelectionContainer(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Svan.Surface)
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(log.ifEmpty { "No observations yet. Run a probe above to inspect this device." },
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), color = Svan.TextMuted)
        }
    }
}
