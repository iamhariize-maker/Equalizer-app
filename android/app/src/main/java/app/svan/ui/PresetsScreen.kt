package app.svan.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.svan.SvanRepository
import app.svan.model.Preset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

@Composable
fun PresetsScreen() {
    val context = LocalContext.current
    val eq by SvanRepository.eq.collectAsState()
    val user by SvanRepository.userPresets.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    var pasteOpen by remember { mutableStateOf(false) }
    var saveOpen by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')?.removeSuffix(" ParametricEQ")?.take(40) ?: "Imported"
        val n = text?.let { SvanRepository.importParametric(name, it) } ?: 0
        message = if (n > 0) "Imported \"$name\" · $n bands" else "No ParametricEQ filters found in that file"
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            Text("Presets", style = MaterialTheme.typography.headlineMedium)
            Text("Now: ${eq.presetName}", style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionTile("Import AutoEq", "ParametricEQ.txt", Icons.Outlined.FileOpen, Modifier.weight(1f)) {
                    picker.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
                }
                ActionTile("Paste", "APO / AutoEq text", Icons.Outlined.ContentPaste, Modifier.weight(1f)) { pasteOpen = true }
                ActionTile("Save", "current curve", Icons.Outlined.Save, Modifier.weight(1f)) { saveOpen = true }
            }
            message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Svan.Gold)
            }
            Text(
                "Headphone correction: find your model at autoeq.app, download “ParametricEQ.txt”, import it here.",
                style = MaterialTheme.typography.bodySmall, color = Svan.TextFaint, modifier = Modifier.padding(top = 10.dp),
            )
        }
        if (user.isNotEmpty()) {
            item { SectionLabel("Your presets") }
            items(user, key = { "u-" + it.name }) { p ->
                PresetRow(p, selected = p.name == eq.presetName, onApply = { SvanRepository.applyPreset(p) },
                    onDelete = { SvanRepository.deleteUserPreset(p.name) })
            }
        }
        item { SectionLabel("Built in") }
        items(Preset.BUILT_IN, key = { "b-" + it.name }) { p ->
            PresetRow(p, selected = p.name == eq.presetName, onApply = { SvanRepository.applyPreset(p) }, onDelete = null)
        }
    }

    if (pasteOpen) {
        TextInputDialog("Paste ParametricEQ text", "Preamp: -6.2 dB\nFilter 1: ON PK Fc 105 Hz Gain 4.5 dB Q 0.70", true,
            onDismiss = { pasteOpen = false }) { text ->
            val n = SvanRepository.importParametric("Pasted preset", text)
            message = if (n > 0) "Imported $n bands" else "No filters found — expected lines like “Filter 1: ON PK Fc …”"
            pasteOpen = false
        }
    }
    if (saveOpen) {
        TextInputDialog("Save preset as", "My headphones", false, onDismiss = { saveOpen = false }) { name ->
            val clean = name.trim().ifEmpty { "My preset" }
            SvanRepository.saveUserPreset(SvanRepository.currentAsPreset(clean))
            SvanRepository.update { it.copy(presetName = clean) }
            message = "Saved \"$clean\""
            saveOpen = false
        }
    }
}

@Composable
private fun ActionTile(title: String, sub: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Svan.Surface)
            .border(1.dp, Svan.Grid, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Svan.Gold)
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = Svan.TextFaint)
    }
}

@Composable
private fun PresetRow(p: Preset, selected: Boolean, onApply: () -> Unit, onDelete: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Svan.Gold.copy(alpha = 0.08f) else Svan.Surface)
            .border(1.dp, if (selected) Svan.Gold else Svan.Grid, RoundedCornerShape(16.dp))
            .clickable(onClick = onApply)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiniCurve(p, Modifier.width(72.dp).height(32.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.titleMedium)
            Text("${p.bands.size} bands" + if (p.preampDb != 0.0) " · preamp ${formatDb(p.preampDb)}" else "",
                style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete preset", tint = Svan.TextMuted) }
        }
    }
}

/**
 * Thumbnail curve. Approximate (sum of analog bell/shelf magnitudes) — cheap
 * enough to draw for every row; the EQ screen shows the exact response.
 */
@Composable
private fun MiniCurve(p: Preset, modifier: Modifier) {
    val pts = remember(p) {
        List(48) { i ->
            val f = 20.0 * 1000.0.pow(i / 47.0)
            p.bands.filter { it.enabled && it.hasGain }.sumOf { b -> approxDb(b.type, b.freqHz, b.gainDb, b.q, f) }
        }
    }
    val range = maxOf(6.0, pts.maxOf { abs(it) })
    Canvas(modifier) {
        val path = Path()
        pts.forEachIndexed { i, db ->
            val x = size.width * i / (pts.size - 1)
            val y = (size.height / 2 - db / range * size.height / 2).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawLine(Svan.Grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
        drawPath(path, Svan.AccentBrush, style = Stroke(width = 2.5f))
    }
}

private fun approxDb(type: app.svan.NativeEngine.FilterType, f0: Double, g: Double, q: Double, f: Double): Double {
    val x = log10(f / f0)
    return when (type) {
        app.svan.NativeEngine.FilterType.PEAK -> g / (1 + (x * 2 * q * PI / 2.5).pow(2))
        app.svan.NativeEngine.FilterType.LOW_SHELF -> g / (1 + 10.0.pow(x * 4 * sqrt(q)))
        app.svan.NativeEngine.FilterType.HIGH_SHELF -> g / (1 + 10.0.pow(-x * 4 * sqrt(q)))
        else -> 0.0
    }
}

@Composable
private fun TextInputDialog(title: String, placeholder: String, multiline: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Svan.SurfaceHigh,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text(placeholder, color = Svan.TextFaint) },
                singleLine = !multiline, minLines = if (multiline) 5 else 1,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
