package app.svan.ui

import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import app.svan.svaramanas.Svaramanas
import app.svan.svaramanas.SvaramanasActivity
import app.svan.model.EqState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.svan.CaptureService
import app.svan.DetectionSetup
import app.svan.EqController
import app.svan.NativeEngine.FilterType
import app.svan.SessionRouter
import app.svan.SvanRepository
import app.svan.model.Band
import app.svan.model.EqMode
import app.svan.model.GraphicLayout
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

private const val MAX_BANDS = 128

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun EqScreen(onOpenDetection: () -> Unit = {}) {
    val eq by SvanRepository.eq.collectAsStateWithLifecycle()
    val curveRevision by SvanRepository.curveRevision.collectAsStateWithLifecycle()
    val settings by SvanRepository.settings.collectAsStateWithLifecycle()
    val detection by DetectionSetup.state.collectAsStateWithLifecycle()
    val undo by SvanRepository.eqUndo.collectAsStateWithLifecycle()
    val request by Svaramanas.request.collectAsStateWithLifecycle()
    val heard by Svaramanas.heard.collectAsStateWithLifecycle()
    val listening by Svaramanas.listening.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var conversion by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableIntStateOf(0) }


    // The engine already holds this state (SvanRepository applies it synchronously).
    val curve = remember(curveRevision) { EqController.curveEngine.curveDb(CURVE_FREQS) }
    val displayBands = if (eq.smartEqControl) eq.smart?.bands ?: emptyList() else eq.manualBands()
    if (selected >= displayBands.size) selected = (displayBands.size - 1).coerceAtLeast(0)
    fun changeMode(mode: EqMode, count: Int = eq.workspaceGraphicCount) {
        val fit=SvanRepository.setEqMode(mode,count)
        conversion=fit?.let { "Curve fitted · %.2f dB RMS · %.2f dB maximum difference. Undo restores the original.".format(it.rmsErrorDb,it.maxErrorDb) }
    }
    val headroom = max(0.0, (curve.maxOrNull() ?: 0.0) + eq.effectivePreampDb())
    val appliedGain = remember(curveRevision) { EqController.curveEngine.appliedGainDb }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header(eq.enabled, if (eq.smartEqControl) "${request.mode.sanskritName} · ${request.mode.plainName}" else eq.presetName, settings.quality.title) { SvanRepository.update { it.copy(enabled = !it.enabled) } }
        if (detection.stage != DetectionSetup.Stage.READY) {
            TextButton(onClick = onOpenDetection, modifier = Modifier.fillMaxWidth()) { Text("Music not detected? Set up in Hi-Fi") }
        }

        FlowRow(Modifier.fillMaxWidth().padding(horizontal=12.dp), horizontalArrangement=Arrangement.spacedBy(8.dp), verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Pill(if (eq.smartEqControl && request.mode == app.svan.svaramanas.SmartMode.GUIDED) "Guide EQ" else "Svaresa EQ", eq.smartEqControl, { SvanRepository.setSmartEqControl(true); conversion=null })
            Pill("Your EQ", !eq.smartEqControl, { SvanRepository.setSmartEqControl(false); conversion=null })
            IconButton(onClick={SvanRepository.undoEq(); conversion=null}, enabled=undo.isNotEmpty() && !eq.smartEqControl) {
                Icon(Icons.AutoMirrored.Outlined.Undo,"Undo EQ edit",tint=if(undo.isNotEmpty() && !eq.smartEqControl) Svan.Gold else Svan.TextFaint)
            }
        }
        Text(if (eq.smartEqControl) {
            if (listening && heard?.valid == true) "Recommended · adapting to measured music and listening context"
            else if (request.mode == app.svan.svaramanas.SmartMode.SVARESA) "Recommended · output, volume & night control; live music analysis unavailable"
            else "Sound guide · your chosen focus; live music analysis unavailable"
        } else "Your manual curve · tap a value for precise entry · undo restores edits",
            style=MaterialTheme.typography.bodySmall, color=Svan.TextMuted,
            modifier=Modifier.padding(horizontal=16.dp,vertical=6.dp))

        // Graph scrolls with the controls so the editor remains usable on small phones.
        Box(
            Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .height(if (LocalConfiguration.current.screenHeightDp < 700) 208.dp else 248.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Brush.verticalGradient(listOf(Svan.Surface, Svan.Black)))
                .border(1.dp, Svan.Grid, RoundedCornerShape(22.dp)),
        ) {
            SoundLandscape(curve, Modifier.fillMaxSize(), live = eq.enabled)
            ResponseGraph(
                bands = displayBands,
                curveDb = curve,
                selected = if (eq.workspaceMode == EqMode.PARAMETRIC) selected else -1,
                enabled = eq.enabled,
                editable = eq.workspaceMode == EqMode.PARAMETRIC,
                allowAddDelete = !eq.smartEqControl,
                moveFrequency = !eq.smartEqControl,
                onSelect = { selected = it },
                onMove = { i, f, g ->
                    if (eq.smartEqControl) SvanRepository.adjustSmartEq(i,g)
                    else SvanRepository.editEq { s -> s.copy(bands=s.bands.replace(i) { it.copy(freqHz=f,gainDb=g) },presetName="Custom") }
                },
                onAdd = { f, g ->
                    if (!eq.smartEqControl && eq.bands.size < MAX_BANDS) {
                        SvanRepository.editEq { s -> s.copy(bands = s.bands + Band(FilterType.PEAK, f, g, 1.0), presetName = "Custom") }
                        selected = eq.bands.size
                    }
                },
                onDelete = { i ->
                    SvanRepository.editEq { s -> s.copy(bands = s.bands.filterIndexed { j, _ -> j != i }, presetName = "Custom") }
                    selected = (i - 1).coerceAtLeast(0)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        ) {
            if (!eq.smartEqControl && eq.workspaceMode == EqMode.PARAMETRIC && eq.bands.size <= 5 && eq.bands.all { it.gainDb == 0.0 }) {
                Text("Drag a band · tap empty space to add · hold to remove",
                    style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted,
                    modifier = Modifier.padding(top = 8.dp))
            }
            ThemePrint(Modifier.padding(top = 12.dp, bottom = 4.dp))
            Spacer(Modifier.height(12.dp))
            Text("Graph: combined EQ response · includes headphone and bass layers · excludes preamp",style=MaterialTheme.typography.bodySmall,color=Svan.TextFaint)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Pill("Parametric", eq.workspaceMode == EqMode.PARAMETRIC, { changeMode(EqMode.PARAMETRIC) })
                Pill("Graphic", eq.workspaceMode == EqMode.GRAPHIC, { changeMode(EqMode.GRAPHIC) })
            }

            if (eq.smartEqControl) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick={SvaramanasActivity.open(context)}) { Text("Master settings") }
                    TextButton(onClick={SvanRepository.resetSmartEqOffsets()}) { Text("Reset preferences") }
                }
                Text("${request.mode.sanskritName} chooses frequency, width and gain. Adjust gain for your preference (±3 dB); adaptation continues. Your manual curve stays saved.",
                    style=MaterialTheme.typography.bodySmall,color=Svan.TextMuted)
                if ((eq.smart?.overlapScale ?: 1.0) < 0.99) Text("Svaresa’s shared boosts reduced to keep its summed EQ emphasis within 6 dB.",style=MaterialTheme.typography.bodySmall,color=Svan.TextMuted)
                eq.smart?.graphicFitRmsDb?.let { rms ->
                    Text("Layout fit before preferences · %.2f dB RMS · %.2f dB maximum difference".format(rms,eq.smart?.graphicFitMaxDb ?: 0.0),
                        style=MaterialTheme.typography.bodySmall,color=Svan.TextFaint)
                }
            }
            conversion?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=Svan.TextMuted,modifier=Modifier.padding(vertical=8.dp)) }

            AnimatedContent(eq.workspaceMode, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "mode") { mode ->
                Column {
                    if (mode == EqMode.PARAMETRIC) {
                        if (eq.smartEqControl) AutoParametricControls(displayBands,selected) { selected=it }
                        else ParametricControls(eq.bands, selected, onSelect = { selected = it })
                    } else {
                        GraphicControls(eq.workspaceGraphicCount, if (eq.smartEqControl) displayBands.map { it.gainDb } else eq.graphicGains, eq.smartEqControl) { changeMode(EqMode.GRAPHIC,it) }
                    }
                }
            }

            SectionLabel("Gain")
            SvanCard {
                Column {
                    ValueSlider(
                        "Preamp", eq.preampDb, ::formatDb,
                        toSlider = { ((it + 24) / 30).toFloat() }, fromSlider = { Math.round((it * 30 - 24) * 10) / 10.0 },
                        onChange = { v -> SvanRepository.editEq { it.copy(preampDb = v) } },
                        entryRange = -24.0..6.0, entryUnit = "dB",
                    )
                    val hr = if (settings.effectiveFor(eq).autoHeadroom) "System preamp ${formatDb(appliedGain)} · includes required headroom"
                    else if (headroom > 0) "Peak boost ${formatDb(headroom)} — may clip without auto headroom" else "No predicted EQ overload"
                    Text(if(eq.smartProtection) "Svaresa keeps headroom and overload protection active. Choose Your EQ for manual protection settings." else "Boosts change tonal balance; headroom can lower overall volume to avoid overload. Toggle it in Hi-Fi; gain protection remains separate.", style = MaterialTheme.typography.bodySmall, color = Svan.TextFaint)
                    Text(hr, style = MaterialTheme.typography.bodySmall, color = if (!settings.effectiveFor(eq).autoHeadroom && headroom > 0) Svan.Ember else Svan.TextMuted)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Header(enabled: Boolean, preset: String, quality: String, onPower: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BrandLine()
            Spacer(Modifier.height(4.dp))
            Exten9edTitle()
            // Stacked, so the preset name keeps its full width on narrow phones instead of being cut to "Svaresa · A…".
            Text(preset, style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            EngineStatus(quality, enabled)
        }
        val ring = if (enabled) Svan.Gold else Svan.Outline
        Box(contentAlignment = Alignment.Center) {
            // Yantra: concentric hairline rings and eight radial marks around the power control.
            androidx.compose.foundation.Canvas(Modifier.size(64.dp)) {
                val c = if (enabled) Svan.Gold else Svan.Outline
                val r = size.minDimension / 2
                drawCircle(c.copy(alpha = 0.35f), radius = r - 1f, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
                drawCircle(c.copy(alpha = 0.18f), radius = r - 5.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
                for (k in 0 until 8) {
                    val a = Math.PI / 4 * k
                    val r0 = r - 4.dp.toPx()
                    drawLine(c.copy(alpha = 0.4f),
                        androidx.compose.ui.geometry.Offset(center.x + (r0 * kotlin.math.cos(a)).toFloat(), center.y + (r0 * kotlin.math.sin(a)).toFloat()),
                        androidx.compose.ui.geometry.Offset(center.x + ((r - 1f) * kotlin.math.cos(a)).toFloat(), center.y + ((r - 1f) * kotlin.math.sin(a)).toFloat()),
                        strokeWidth = 1f)
                }
            }
            IconButton(
                onClick = onPower,
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(if (enabled) Svan.Gold.copy(alpha = 0.12f) else Svan.SurfaceHigh)
                    .border(1.dp, ring, CircleShape),
            ) {
                Icon(Icons.Outlined.PowerSettingsNew, contentDescription = if (enabled) "Turn EQ off" else "Turn EQ on",
                    tint = if (enabled) Svan.Gold else Svan.TextMuted)
            }
        }
    }
}

/** Which engine is live and how many apps it covers (polled; routes aren't observable). */
@Composable
private fun EngineStatus(quality: String, enabled: Boolean) {
    var text by remember { mutableStateOf("") }
    var live by remember { mutableStateOf(false) }
    ObserveWhileVisible {
        val routes = SessionRouter.snapshot
        val b = routes.filter { it.owner == SessionRouter.Owner.ENGINE_B_MUTED }.map { it.pkg }.distinct().size
        val a = routes.filter { it.owner == SessionRouter.Owner.ENGINE_A && it.sessionId in EqController.globalEq.attachedSessions }.map { it.pkg }.distinct().size
        live = enabled && (a > 0 || b > 0)
        text = when {
            !enabled -> "EQ bypassed"
            CaptureService.isRunning && b > 0 -> "$quality · $b app${if (b == 1) "" else "s"}" + if (a > 0) " · +$a system" else ""
            a > 0 -> "System EQ · $a app${if (a == 1) "" else "s"}"
            else -> "No music connected"
        }
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Svan.SurfaceHigh).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (live) Svan.Glow else Svan.TextFaint)
            .border(if (live) 2.dp else 0.dp, Svan.Gold.copy(alpha = 0.25f), CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = Svan.TextMuted, maxLines = 1, softWrap = false)
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
                        SvanRepository.editEq { s -> s.copy(bands = s.bands + Band(FilterType.PEAK, f, 0.0, 1.0), presetName = "Custom") }
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
                    onCheckedChange = { on -> SvanRepository.editEq { s -> s.copy(bands = s.bands.replace(selected) { it.copy(enabled = on) }) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = Svan.Gold, checkedThumbColor = Svan.Black, uncheckedTrackColor = Svan.SurfaceHigher),
                )
                IconButton(onClick = {
                    SvanRepository.editEq { s -> s.copy(bands = s.bands.filterIndexed { j, _ -> j != selected }, presetName = "Custom") }
                    onSelect((selected - 1).coerceAtLeast(0))
                }) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete band", tint = Svan.TextMuted) }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterType.entries.forEach { t ->
                    Pill(Svan.typeShort(t), band.type == t, accent = Svan.typeColor(t), onClick = {
                        SvanRepository.editEq { s -> s.copy(bands = s.bands.replace(selected) { it.copy(type = t) }, presetName = "Custom") }
                    })
                }
            }
            Spacer(Modifier.height(10.dp))
            ValueSlider(
                "Frequency", band.freqHz, ::formatHz,
                toSlider = { (ln(it / 20.0) / ln(1000.0)).toFloat() },
                fromSlider = { Math.round(20.0 * 1000.0.pow(it.toDouble())).toDouble() },
                onChange = { f -> SvanRepository.editEq { s -> s.copy(bands = s.bands.replace(selected) { it.copy(freqHz = f) }, presetName = "Custom") } },
                accent = Svan.typeColor(band.type), entryRange = 10.0..22000.0, entryUnit = "Hz", step = 1.0,
            )
            ValueSlider(
                "Gain", band.gainDb, ::formatDb,
                toSlider = { ((it + 24) / 48).toFloat() }, fromSlider = { Math.round((it * 48 - 24) * 10) / 10.0 },
                onChange = { g -> SvanRepository.editEq { s -> s.copy(bands = s.bands.replace(selected) { it.copy(gainDb = g) }, presetName = "Custom") } },
                enabled = band.hasGain, accent = Svan.typeColor(band.type), entryRange = -24.0..24.0, entryUnit = "dB",
            )
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(onClick={SvanRepository.editEq { s -> s.copy(bands=s.bands.replace(selected) { it.copy(gainDb=(it.gainDb-0.1).coerceAtLeast(-24.0)) },presetName="Custom") }}, enabled=band.hasGain) { Text("−0.1 dB") }
                TextButton(onClick={SvanRepository.editEq { s -> s.copy(bands=s.bands.replace(selected) { it.copy(gainDb=0.0) },presetName="Custom") }}, enabled=band.hasGain) { Text("Zero") }
                TextButton(onClick={SvanRepository.editEq { s -> s.copy(bands=s.bands.replace(selected) { it.copy(gainDb=(it.gainDb+0.1).coerceAtMost(24.0)) },presetName="Custom") }}, enabled=band.hasGain) { Text("+0.1 dB") }
            }
            ValueSlider(
                "Q", band.q, { "%.2f".format(it) },
                toSlider = { (ln(it / 0.1) / ln(200.0)).toFloat() },
                fromSlider = { Math.round(0.1 * 200.0.pow(it.toDouble()) * 100) / 100.0 },
                onChange = { q -> SvanRepository.editEq { s -> s.copy(bands = s.bands.replace(selected) { it.copy(q = q) }, presetName = "Custom") } },
                accent = Svan.typeColor(band.type), entryRange = 0.1..20.0, step = 0.01,
            )
            Text(bandwidthHint(band), style = MaterialTheme.typography.bodySmall, color = Svan.TextFaint)
        }
    }
}

@Composable
private fun AutoParametricControls(bands: List<Band>, selected: Int, onSelect: (Int) -> Unit) {
    SectionLabel("Live bands · ${bands.size}")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        bands.forEachIndexed { i,b -> Pill("${i+1} ${shortHz(b.freqHz)} ${formatDb(b.gainDb)}",i==selected,{onSelect(i)}) }
    }
    val band=bands.getOrNull(selected) ?: return
    SvanCard {
        Column {
            Text("${Svan.typeShort(band.type)} · ${formatHz(band.freqHz)} · Q %.2f".format(band.q),style=MaterialTheme.typography.titleMedium,color=Svan.Text)
            Text("Frequency and width follow Svaresa. Drag vertically or adjust gain below.",style=MaterialTheme.typography.bodySmall,color=Svan.TextMuted)
            ValueSlider("Applied gain",band.gainDb,::formatDb,
                toSlider={((it+12)/24).toFloat()},fromSlider={Math.round((it*24-12)*10)/10.0},
                onChange={SvanRepository.adjustSmartEq(selected,it)},entryRange=-12.0..12.0,entryUnit="dB")
            TextButton(onClick={SvanRepository.resetSmartEqOffset(selected)}) { Text("Restore automatic gain") }
        }
    }
}

@Composable
private fun GraphicControls(count: Int, gains: List<Double>, automatic: Boolean, onCount: (Int) -> Unit) {
    var entry by remember { mutableIntStateOf(-1) }
    SectionLabel("Graphic EQ")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        GraphicLayout.COUNTS.forEach { n ->
            Pill("$n", count == n, {
                onCount(n)
            })
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { if (automatic) SvanRepository.resetSmartEqOffsets() else SvanRepository.editEq { it.copy(graphicGains = List(it.graphicCount) { 0.0 }) } }) {
            Icon(Icons.Outlined.RestartAlt, contentDescription = "Reset all", tint = Svan.TextMuted)
        }
    }
    Spacer(Modifier.height(8.dp))
    SvanCard {
        val centers = GraphicLayout.centers(count)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            centers.forEachIndexed { i, f ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val gain=gains.getOrElse(i) { 0.0 }
                    Text("%+.1f".format(gain).replace("+0.0", "0"), style = MaterialTheme.typography.labelSmall,
                        color = if (gain == 0.0) Svan.TextFaint else Svan.Text, textAlign = TextAlign.Center, modifier=Modifier.clickable { entry=i }.padding(vertical=8.dp))
                    VerticalFader(
                        value = gain, range = 12.0,
                        label="${formatHz(f)} gain",
                        onReset={if (automatic) SvanRepository.resetSmartEqOffset(i) else SvanRepository.editEq { s -> s.copy(graphicGains=s.graphicGains.replace(i) { 0.0 },presetName="Custom") }},
                        onChange = { v -> if (automatic) SvanRepository.adjustSmartEq(i,v) else SvanRepository.editEq { s -> s.copy(graphicGains = s.graphicGains.replace(i) { v }, presetName = "Custom") } },
                        modifier = Modifier.height(200.dp),
                        width = 56.dp,
                    )
                    Text(GraphicLayout.label(f), style = MaterialTheme.typography.labelSmall, color = Svan.TextMuted)
                }
            }
        }
    }
    if (entry >= 0) NumberEntryDialog("${formatHz(GraphicLayout.centers(count)[entry])} gain", gains.getOrElse(entry) { 0.0 }, -12.0..12.0, "dB", { entry=-1 }) { v ->
        if (automatic) SvanRepository.adjustSmartEq(entry,v) else SvanRepository.editEq { s -> s.copy(graphicGains=s.graphicGains.replace(entry) { v },presetName="Custom") }
        entry=-1
    }
    Text(if (automatic) "Drag for 0.1 dB steps; swipe sideways for more bands · double-tap to restore Svaresa's gain." else "Drag for 0.1 dB steps; swipe sideways for more bands · tap gain to enter a value · double-tap to reset.",
        style = MaterialTheme.typography.bodySmall, color = Svan.TextFaint, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
}

private fun <T> List<T>.replace(i: Int, f: (T) -> T): List<T> = mapIndexed { j, v -> if (j == i) f(v) else v }

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
