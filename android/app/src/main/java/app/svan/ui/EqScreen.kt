package app.svan.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.svan.CaptureService
import app.svan.EqController
import app.svan.NativeEngine.FilterType
import app.svan.SessionRouter
import app.svan.SvanRepository
import app.svan.model.Band
import app.svan.model.EqMode
import app.svan.model.GraphicLayout
import kotlinx.coroutines.delay
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

private const val MAX_BANDS = 128

@Composable
fun EqScreen() {
    val eq by SvanRepository.eq.collectAsState()
    val settings by SvanRepository.settings.collectAsState()
    var selected by remember { mutableIntStateOf(0) }
    if (selected >= eq.bands.size) selected = eq.bands.size - 1

    // The engine already holds this state (SvanRepository applies it synchronously).
    val curve = remember(eq) { EqController.curveEngine.curveDb(CURVE_FREQS) }
    val displayBands = if (eq.mode == EqMode.PARAMETRIC) eq.bands else GraphicLayout.bands(eq.graphicCount, eq.graphicGains)
    val headroom = max(0.0, curve.maxOrNull() ?: 0.0) - eq.preampDb

    Column(Modifier.fillMaxSize()) {
        Header(eq.enabled, eq.presetName) { SvanRepository.update { it.copy(enabled = !it.enabled) } }

        // Graph stays pinned while the controls scroll.
        Box(
            Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .height(250.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Brush.verticalGradient(listOf(Svan.Surface, Svan.Black)))
                .border(1.dp, Svan.Grid, RoundedCornerShape(22.dp)),
        ) {
            ResponseGraph(
                bands = displayBands,
                curveDb = curve,
                selected = if (eq.mode == EqMode.PARAMETRIC) selected else -1,
                enabled = eq.enabled,
                editable = eq.mode == EqMode.PARAMETRIC,
                onSelect = { selected = it },
                onMove = { i, f, g -> SvanRepository.update { s -> s.copy(bands = s.bands.replace(i) { it.copy(freqHz = f, gainDb = g) }, presetName = "Custom") } },
                onAdd = { f, g ->
                    if (eq.bands.size < MAX_BANDS) {
                        SvanRepository.update { s -> s.copy(bands = s.bands + Band(FilterType.PEAK, f, g, 1.0), presetName = "Custom") }
                        selected = eq.bands.size
                    }
                },
                onDelete = { i ->
                    SvanRepository.update { s -> s.copy(bands = s.bands.filterIndexed { j, _ -> j != i }, presetName = "Custom") }
                    selected = (i - 1).coerceAtLeast(0)
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (eq.mode == EqMode.PARAMETRIC && eq.bands.size <= 5 && eq.bands.all { it.gainDb == 0.0 }) {
                Text("Drag a node · tap empty space to add · long-press to remove",
                    style = MaterialTheme.typography.bodySmall, color = Svan.TextFaint,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill("Parametric", eq.mode == EqMode.PARAMETRIC, { SvanRepository.update { it.copy(mode = EqMode.PARAMETRIC) } })
                Spacer(Modifier.width(8.dp))
                Pill("Graphic", eq.mode == EqMode.GRAPHIC, { SvanRepository.update { it.copy(mode = EqMode.GRAPHIC) } })
                Spacer(Modifier.weight(1f))
                EngineStatus(settings.quality.title)
            }

            AnimatedContent(eq.mode, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "mode") { mode ->
                Column {
                    if (mode == EqMode.PARAMETRIC) {
                        ParametricControls(eq.bands, selected, onSelect = { selected = it })
                    } else {
                        GraphicControls(eq.graphicCount, eq.graphicGains)
                    }
                }
            }

            SectionLabel("Gain")
            SvanCard {
                Column {
                    ValueSlider(
                        "Preamp", eq.preampDb, ::formatDb,
                        toSlider = { ((it + 24) / 30).toFloat() }, fromSlider = { Math.round((it * 30 - 24) * 10) / 10.0 },
                        onChange = { v -> SvanRepository.update { it.copy(preampDb = v) } },
                        entryRange = -24.0..6.0, entryUnit = "dB",
                    )
                    val hr = if (settings.autoHeadroom) "Auto headroom: ${formatDb(-max(0.0, headroom))} applied so boosts never clip"
                    else if (headroom > 0) "Peak boost ${formatDb(headroom)} — may clip without auto headroom" else "No clipping risk"
                    Text(hr, style = MaterialTheme.typography.bodySmall, color = if (!settings.autoHeadroom && headroom > 0) Svan.Rose else Svan.TextMuted)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Header(enabled: Boolean, preset: String, onPower: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Svan", style = MaterialTheme.typography.headlineMedium.copy(brush = Svan.AccentBrush))
            Text(preset, style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
        }
        val ring = if (enabled) Svan.Saffron else Svan.Outline
        IconButton(
            onClick = onPower,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (enabled) Svan.Saffron.copy(alpha = 0.14f) else Svan.SurfaceHigh)
                .border(1.5.dp, ring, CircleShape),
        ) {
            Icon(Icons.Outlined.PowerSettingsNew, contentDescription = if (enabled) "Turn EQ off" else "Turn EQ on",
                tint = if (enabled) Svan.Saffron else Svan.TextMuted)
        }
    }
}

/** Which engine is live and how many apps it covers (polled; routes aren't observable). */
@Composable
private fun EngineStatus(quality: String) {
    var text by remember { mutableStateOf("") }
    LaunchedEffect(quality) {
        while (true) {
            val routes = SessionRouter.snapshot
            val b = routes.count { it.owner == SessionRouter.Owner.ENGINE_B_MUTED }
            val a = routes.count { it.owner == SessionRouter.Owner.ENGINE_A }
            text = when {
                CaptureService.isRunning -> "$quality · $b app${if (b == 1) "" else "s"}" + if (a > 0) " · +$a system" else ""
                a > 0 -> "System EQ · $a app${if (a == 1) "" else "s"}"
                else -> "Waiting for audio"
            }
            delay(1000)
        }
    }
    val live = CaptureService.isRunning
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Svan.SurfaceHigh).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (live) Svan.Green else Svan.TextFaint))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = Svan.TextMuted)
    }
}

@Composable
private fun ParametricControls(bands: List<Band>, selected: Int, onSelect: (Int) -> Unit) {
    SectionLabel("Bands · ${bands.size}")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        bands.forEachIndexed { i, b ->
            Pill(
                "${i + 1}  ${Svan.typeCode(b.type)} ${shortHz(b.freqHz)}" + if (b.hasGain) "  ${"%+.1f".format(b.gainDb)}" else "",
                selected = i == selected,
                onClick = { onSelect(i) },
                accent = Svan.typeColor(b.type),
            )
        }
        if (bands.size < MAX_BANDS) {
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(Svan.SurfaceHigh).border(1.dp, Svan.Outline, RoundedCornerShape(50))
                    .clickable {
                        // New band halfway (log) between the selected band and the next one up.
                        val base = bands.getOrNull(selected)?.freqHz ?: 1000.0
                        val f = (base * 1.6).coerceAtMost(18000.0)
                        SvanRepository.update { s -> s.copy(bands = s.bands + Band(FilterType.PEAK, f, 0.0, 1.0), presetName = "Custom") }
                        onSelect(bands.size)
                    }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) { Icon(Icons.Outlined.Add, contentDescription = "Add band", tint = Svan.Text, modifier = Modifier.size(20.dp)) }
        }
    }

    val band = bands.getOrNull(selected) ?: return
    SectionLabel("Band ${selected + 1}")
    SvanCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Svan.typeShort(band.type), style = MaterialTheme.typography.titleLarge, color = Svan.typeColor(band.type))
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = band.enabled,
                    onCheckedChange = { on -> SvanRepository.update { s -> s.copy(bands = s.bands.replace(selected) { it.copy(enabled = on) }) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = Svan.Saffron, checkedThumbColor = Svan.Black, uncheckedTrackColor = Svan.SurfaceHigher),
                )
                IconButton(onClick = {
                    SvanRepository.update { s -> s.copy(bands = s.bands.filterIndexed { j, _ -> j != selected }, presetName = "Custom") }
                    onSelect((selected - 1).coerceAtLeast(0))
                }) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete band", tint = Svan.TextMuted) }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterType.entries.forEach { t ->
                    Pill(Svan.typeShort(t), band.type == t, accent = Svan.typeColor(t), onClick = {
                        SvanRepository.update { s -> s.copy(bands = s.bands.replace(selected) { it.copy(type = t) }, presetName = "Custom") }
                    })
                }
            }
            Spacer(Modifier.height(10.dp))
            ValueSlider(
                "Frequency", band.freqHz, ::formatHz,
                toSlider = { (ln(it / 20.0) / ln(1000.0)).toFloat() },
                fromSlider = { roundHz(20.0 * 1000.0.pow(it.toDouble())) },
                onChange = { f -> SvanRepository.update { s -> s.copy(bands = s.bands.replace(selected) { it.copy(freqHz = f) }, presetName = "Custom") } },
                accent = Svan.typeColor(band.type), entryRange = 10.0..22000.0, entryUnit = "Hz",
            )
            ValueSlider(
                "Gain", band.gainDb, ::formatDb,
                toSlider = { ((it + 24) / 48).toFloat() }, fromSlider = { Math.round((it * 48 - 24) * 10) / 10.0 },
                onChange = { g -> SvanRepository.update { s -> s.copy(bands = s.bands.replace(selected) { it.copy(gainDb = g) }, presetName = "Custom") } },
                enabled = band.hasGain, accent = Svan.typeColor(band.type), entryRange = -24.0..24.0, entryUnit = "dB",
            )
            ValueSlider(
                "Q", band.q, { "%.2f".format(it) },
                toSlider = { (ln(it / 0.1) / ln(200.0)).toFloat() },
                fromSlider = { Math.round(0.1 * 200.0.pow(it.toDouble()) * 100) / 100.0 },
                onChange = { q -> SvanRepository.update { s -> s.copy(bands = s.bands.replace(selected) { it.copy(q = q) }, presetName = "Custom") } },
                accent = Svan.typeColor(band.type), entryRange = 0.1..20.0,
            )
            Text(bandwidthHint(band), style = MaterialTheme.typography.bodySmall, color = Svan.TextFaint)
        }
    }
}

@Composable
private fun GraphicControls(count: Int, gains: List<Double>) {
    SectionLabel("Graphic EQ")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        GraphicLayout.COUNTS.forEach { n ->
            Pill("$n", count == n, {
                SvanRepository.update { s -> s.copy(graphicCount = n, graphicGains = resample(s.graphicGains, s.graphicCount, n), presetName = "Custom") }
            })
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { SvanRepository.update { it.copy(graphicGains = List(it.graphicCount) { 0.0 }) } }) {
            Icon(Icons.Outlined.RestartAlt, contentDescription = "Reset all", tint = Svan.TextMuted)
        }
    }
    Spacer(Modifier.height(8.dp))
    SvanCard {
        val centers = GraphicLayout.centers(count)
        val wide = count > 15
        Row(
            if (wide) Modifier.horizontalScroll(rememberScrollState()) else Modifier.fillMaxWidth(),
            horizontalArrangement = if (wide) Arrangement.spacedBy(2.dp) else Arrangement.SpaceBetween,
        ) {
            centers.forEachIndexed { i, f ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("%+.1f".format(gains[i]).replace("+0.0", "0"), style = MaterialTheme.typography.labelSmall,
                        color = if (gains[i] == 0.0) Svan.TextFaint else Svan.Text, textAlign = TextAlign.Center)
                    VerticalFader(
                        value = gains[i], range = 12.0,
                        onChange = { v -> SvanRepository.update { s -> s.copy(graphicGains = s.graphicGains.replace(i) { v }, presetName = "Custom") } },
                        modifier = Modifier.height(200.dp),
                        width = if (wide) 30.dp else 28.dp,
                    )
                    Text(GraphicLayout.label(f), style = MaterialTheme.typography.labelSmall, color = Svan.TextMuted)
                }
            }
        }
    }
    Text("Double-tap a fader to reset it. Q = %.2f per band.".format(GraphicLayout.q(count)),
        style = MaterialTheme.typography.bodySmall, color = Svan.TextFaint, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
}

private fun <T> List<T>.replace(i: Int, f: (T) -> T): List<T> = mapIndexed { j, v -> if (j == i) f(v) else v }

/** Keeps the shape of a graphic curve when switching band counts. */
private fun resample(gains: List<Double>, from: Int, to: Int): List<Double> {
    if (from == to) return gains
    val src = GraphicLayout.centers(from)
    return GraphicLayout.centers(to).map { f ->
        val j = src.indices.minBy { kotlin.math.abs(ln(src[it] / f)) }
        gains.getOrElse(j) { 0.0 }
    }
}

private fun roundHz(f: Double): Double = when {
    f < 100 -> Math.round(f).toDouble()
    f < 1000 -> Math.round(f / 5) * 5.0
    f < 10000 -> Math.round(f / 10) * 10.0
    else -> Math.round(f / 100) * 100.0
}

private fun shortHz(f: Double) = if (f >= 1000) "%.1fk".format(f / 1000).replace(".0k", "k") else "%.0f".format(f)

private fun bandwidthHint(b: Band): String {
    // Octave bandwidth for a bell of this Q (RBJ definition).
    val bw = 2.0 / ln(2.0) * kotlin.math.asinh(1.0 / (2.0 * b.q))
    return when (b.type) {
        FilterType.PEAK, FilterType.NOTCH, FilterType.BAND_PASS -> "Bandwidth ≈ %.2f octaves".format(bw)
        FilterType.LOW_SHELF, FilterType.HIGH_SHELF -> "Q 0.71 = smooth shelf; higher Q adds overshoot"
        FilterType.LOW_PASS, FilterType.HIGH_PASS -> "12 dB/octave · Q 0.71 = Butterworth (flat)"
        FilterType.ALL_PASS -> "Phase only — no change in level"
    }
}
