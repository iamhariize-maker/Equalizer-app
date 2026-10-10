package app.svan.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.svan.EqController
import app.svan.SvanRepository
import app.svan.lab.IntegratedLab
import app.svan.lab.WavWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log10
import java.util.Locale

/** The Lab uses the normal repository, foreground service and routing table. */
@Composable
fun IntegratedLabPanel(onOpenEq: () -> Unit, onDiagnostics: () -> Unit) {
    val context = LocalContext.current
    val state by IntegratedLab.state.collectAsState()
    val revision by SvanRepository.engineARevision.collectAsState()
    var page by rememberSaveable { mutableIntStateOf(0) }
    var rate by rememberSaveable { mutableIntStateOf(48000) }
    var block by rememberSaveable { mutableIntStateOf(4096) }
    var blend by rememberSaveable { mutableStateOf(false) }
    var margin by rememberSaveable { mutableFloatStateOf(6f) }
    var exportMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var pendingReport by remember { mutableStateOf<String?>(null) }
    var pendingRate by remember { mutableIntStateOf(48000) }
    val saveReport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val report = pendingReport
        pendingReport = null
        if (uri != null && report != null) scope.launch {
            exportMessage = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(report.toByteArray()) }
                    ?: error("File could not be opened"); "Lab report saved." }.getOrElse { "Export failed: ${it.message}" }
            }
        }
    }
    val saveWav = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val selectedRate = pendingRate
        if (uri != null) scope.launch {
            exportMessage = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { WavWriter.write(it, selectedRate) }
                    ?: error("File could not be opened"); "Quiet 20-second measurement WAV saved." }.getOrElse { "Export failed: ${it.message}" }
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenTitle("Svan Lab", "Explore the controls. Measure the result.")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Shape", "Engine", "Measure").forEachIndexed { index, title -> Pill(title, page == index, onClick = { page = index }) }
        }
        Surface(shape = RoundedCornerShape(18.dp), color = Svan.Surface) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (state.applied) "EXPERIMENT SELECTED" else "REFERENCE MODEL", style = MaterialTheme.typography.labelMedium, color = Svan.Gold)
                Text(state.message, style = MaterialTheme.typography.bodyMedium)
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("AOSP prediction · output rate and vendor FFT remain assumptions. Input margin reduces level; it does not guarantee sample or true peaks.",
                    style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
            }
        }
        if (page == 0) {
            val curve = remember(revision) { EqController.curveEngine.responseDb(CURVE_FREQS) }
            Text("Your shared Svan curve", style = MaterialTheme.typography.titleMedium)
            LabResponseGraph(CURVE_FREQS, curve, null)
            Text("The fit uses the combined manual, headphone and automatic curve. Edit it in EQ; the native capture engine retains its existing processors.",
                style = MaterialTheme.typography.bodyMedium, color = Svan.TextMuted)
            Button(onClick = onOpenEq) { Text("Edit shape in EQ") }
            state.plan?.let { p ->
                Text("Requested / predicted shape", style = MaterialTheme.typography.titleMedium)
                LabResponseGraph(p.model.frequencies, p.target, p.curve)
                Text("Ash: requested · accent: predicted static EQ before input margin. Compression and limiting are not modeled here.", style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
            }
        }
        if (page == 1) {
            SectionLabel("Assumed output rate")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(44100, 48000).forEach { value -> Pill(if (value == 44100) "44.1 kHz" else "48 kHz", rate == value, onClick = { rate = value }) }
            }
            SectionLabel("Assumed FFT block")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2048, 4096, 8192).forEach { value -> Pill("$value", block == value, onClick = { block = value }) }
            }
            Text("A larger block narrows the frequency bins and adds buffering. Preferred duration: ${String.format(Locale.ROOT, "%.1f", block * 1000.0 / rate)} ms; this is not measured end-to-end latency.",
                style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Experimental Equalizer blend", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(blend, { blend = it })
            }
            Text("Uses the reference 60/230 Hz biquads only when the descriptor and controls match. The numerical guard selects a blend only if predicted bass error improves without worse modeled modulation.",
                style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
            Text("Operating margin: ${margin.toInt()} dB", style = MaterialTheme.typography.titleMedium)
            Slider(margin, { margin = it }, valueRange = 3f..18f, steps = 14)
            Button(onClick = { IntegratedLab.fit(context, rate, block, blend, margin.toDouble()) }, enabled = !state.busy) { Text("Fit current curve") }
            state.plan?.let { p ->
                Text("${p.gains.size} unique-bin controls · ${if (p.hybrid) "hybrid" else "DP only"}", style = MaterialTheme.typography.titleMedium)
                Text("Predicted bass RMS error: ${String.format(Locale.ROOT, "%.3f", p.rms)} dB\nPredicted modulation: ${String.format(Locale.ROOT, "%.1f", p.modulationDb)} dB\nInput gain: ${String.format(Locale.ROOT, "%.1f", p.attenuationDb)} dB",
                    style = MaterialTheme.typography.bodyMedium, color = Svan.TextMuted)
                Button(onClick = { IntegratedLab.apply() }, enabled = !state.busy && !state.applied) { Text("Apply fitted controls") }
            }
            OutlinedButton(onClick = { IntegratedLab.restore() }, enabled = !state.busy) { Text("Restore normal Svan") }
            Text("Fit settings affect Engine A only. A sound/settings edit restores normal Svan. Experiments are not saved as the default across app restarts.", style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
        }
        if (page == 2) {
            Text("Make the result measurable", style = MaterialTheme.typography.titleMedium)
            Text("Export the test WAV, play it through your music player, then capture the output with an external recorder. It contains silence, an impulse, a quiet sweep and a multitone tail. Start with low listening volume.",
                style = MaterialTheme.typography.bodyMedium, color = Svan.TextMuted)
            Button(onClick = { pendingRate = rate; saveWav.launch("Svan-measure-${rate}.wav") }) { Text("Export measurement WAV") }
            OutlinedButton(onClick = {
                runCatching { pendingReport = IntegratedLab.export(); saveReport.launch("Svan-lab-controls.json") }
                    .onFailure { exportMessage = it.message ?: "Fit a curve first" }
            }, enabled = state.plan != null) { Text("Export fitted controls and report") }
            OutlinedButton(onClick = onDiagnostics) { Text("Device probes and engine log") }
            Text("These exports do not record other apps or request additional permissions. Keep your current detection setup.", style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
            if (exportMessage.isNotEmpty()) Text(exportMessage, style = MaterialTheme.typography.bodyMedium, color = Svan.Gold)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun LabResponseGraph(frequencies: DoubleArray, requested: DoubleArray, predicted: DoubleArray?) {
    val grid = Svan.Grid
    val primary = Svan.Gold
    val neutral = Svan.Ash
    val range = maxOf(12.0, requested.maxOfOrNull { kotlin.math.abs(it) } ?: 0.0,
        predicted?.maxOfOrNull { kotlin.math.abs(it) } ?: 0.0)
    Canvas(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp)).background(Svan.Surface)
        .semantics { contentDescription = "Frequency response from 20 Hz to 20 kHz. Range plus or minus ${range.toInt()} dB. Reference prediction, not a measurement." }) {
        listOf(20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000).forEach { hz ->
            val x = (log10(hz / 20.0) / 3 * size.width).toFloat()
            drawLine(grid, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
        }
        for (i in 1..3) drawLine(grid, Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4), 1.dp.toPx())
        fun path(values: DoubleArray): Path = Path().apply {
            var started = false
            frequencies.forEachIndexed { i, f ->
                if (f in 20.0..20000.0 && i < values.size) {
                    val x = (log10(f / 20) / 3 * size.width).toFloat()
                    val y = (size.height * (.5 - values[i] / (2 * range))).toFloat()
                    if (!started) { moveTo(x, y); started = true } else lineTo(x, y)
                }
            }
        }
        drawPath(path(requested), if (predicted == null) primary else neutral, style = Stroke(2.dp.toPx()))
        predicted?.let { drawPath(path(it), primary, style = Stroke(2.5.dp.toPx())) }
    }
}
