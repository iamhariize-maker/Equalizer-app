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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.svan.EqController
import kotlinx.coroutines.delay

/** Diagnostics from the spike, kept for testing on new devices. */
@Composable
fun LabScreen(actions: List<Pair<String, () -> Unit>>) {
    var log by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            log = synchronized(EqController.log) { EqController.log.toString() }.takeLast(12000)
            delay(800)
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Lab", style = MaterialTheme.typography.headlineMedium)
        Text("Device probes and the engine log.", style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEach { (label, action) -> Pill(label, false, action) }
        }
        Spacer(Modifier.height(12.dp))
        SelectionContainer(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Svan.Surface)
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(log.ifEmpty { "No log yet." }, style = MaterialTheme.typography.labelSmall, color = Svan.TextMuted)
        }
    }
}
