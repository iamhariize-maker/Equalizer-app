package app.svan.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.svan.EqController
import app.svan.SvanRepository
import app.svan.model.BassTuner
import app.svan.tuning.AutoEqSource
import app.svan.tuning.Signature
import app.svan.tuning.TuningController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.pow

/** The friendly front door: pick your headphones and a sound, then shape the bass. */
@Composable
fun SoundScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val eq by SvanRepository.eq.collectAsState()
    val tuning = eq.tuning

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<AutoEqSource.Entry>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var entry by remember(tuning?.ref) { mutableStateOf(tuning?.ref?.let(TuningController::entryFromRef)) }
    var signature by remember(tuning?.signature) {
        mutableStateOf(Signature.entries.firstOrNull { it.title == tuning?.signature } ?: Signature.HARMAN)
    }
    var bassDb by remember(tuning?.ref) { mutableDoubleStateOf(tuning?.bassDb ?: 0.0) }
    var tilt by remember(tuning?.ref) { mutableDoubleStateOf(tuning?.tiltDbPerOct ?: 0.0) }
    var bandCount by remember { mutableIntStateOf(64) }
    var correctionAmount by remember(tuning?.ref) { mutableDoubleStateOf(tuning?.calibration?.amount ?: 1.0) }
    var customTarget by remember { mutableStateOf<String?>(null) }
    var customMeasurement by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var picking by remember(tuning?.ref) { mutableStateOf(tuning == null) }

    fun apply() {
        val e = entry ?: return
        busy = true
        message = null
        scope.launch {
            val r = TuningController.build(
                context,
                TuningController.Request(e, signature, bassDb, tilt, bandCount, customTarget, customMeasurement,correctionAmount),
            )
            busy = false
            r.onSuccess { t ->
                SvanRepository.update { it.copy(tuning = t, enabled = true) }
                message = "${t.bands.size} bands · RMS fit error %.2f dB".format(t.fitRmsDb)
                picking = false
            }.onFailure { message = it.message ?: "Couldn't build the tuning" }
        }
    }

    // Taste sliders re-tune live (debounced) once a tuning exists.
    LaunchedEffect(bassDb, tilt, bandCount,correctionAmount,tuning) {
        if (tuning == null || entry == null || busy) return@LaunchedEffect
        if (bassDb == tuning.bassDb && tilt == tuning.tiltDbPerOct && bandCount == tuning.bands.size && correctionAmount==(tuning.calibration?.amount ?: 1.0)) return@LaunchedEffect
        delay(350)
        apply()
    }
    LaunchedEffect(query) {
        if (query.length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(200)
        searching = true
        results = runCatching { withContext(Dispatchers.IO) { AutoEqSource.search(context, query) } }
            .onFailure { message = "Can't load the headphone list: ${it.message}" }
            .getOrDefault(emptyList())
        searching = false
    }

    val targetPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        customTarget = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        signature = Signature.CUSTOM
        apply()
    }
    val measurementPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        customMeasurement = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Imported headphone"
        entry = AutoEqSource.Entry(name, "Imported", if (entry?.form == "in-ear") "in-ear" else "over-ear", null, "")
        if (signature == Signature.PUBLISHED) signature = Signature.HARMAN
        picking = false
        apply()
    }

    val curve = remember(eq) { EqController.curveEngine.curveDb(CURVE_FREQS) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            ScreenTitle("Sound", "Make your headphones sound their best.")
            FirstRunWelcome()
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(20.dp)).background(Svan.Surface)
                    .border(1.dp, Svan.Grid, RoundedCornerShape(20.dp)),
            ) {
                ResponseGraph(emptyList(), curve, -1, eq.enabled, editable = false,
                    onSelect = {}, onMove = { _, _, _ -> }, onAdd = { _, _ -> }, onDelete = {}, modifier = Modifier.fillMaxSize())
            }
            SectionLabel("Your headphones")
        }

        // ---- headphones ----
        if (!picking && entry != null) {
            item {
                SvanCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Headphones, contentDescription = null, tint = Svan.Gold)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry!!.name, style = MaterialTheme.typography.titleMedium)
                            Text(entry!!.label, style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
                        }
                        if (tuning != null) {
                            Switch(
                                checked = tuning.enabled,
                                onCheckedChange = { on -> SvanRepository.update { s -> s.copy(tuning = s.tuning?.copy(enabled = on)) } },
                                colors = SwitchDefaults.colors(checkedTrackColor = Svan.Gold, checkedThumbColor = Svan.Black, uncheckedTrackColor = Svan.SurfaceHigher),
                            )
                        }
                    }
                }
                TextButton(onClick = { picking = true }) { Text("Change headphones") }
            }
        } else {
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    placeholder = { Text("Search ~9,000 headphones & IEMs", color = Svan.TextFaint) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = Svan.TextMuted) },
                    trailingIcon = { if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Svan.Gold) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Svan.Gold, unfocusedBorderColor = Svan.Outline),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Measurements from oratory1990, crinacle, Super Review, Rtings and more, via AutoEq.",
                    style = MaterialTheme.typography.bodySmall, color = Svan.TextFaint, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
                TextButton(onClick = { measurementPicker.launch(arrayOf("*/*")) }) { Text("Not listed? Import a measurement file") }
            }
            items(results, key = { it.resultPath }) { e ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(Svan.Surface)
                        .clickable {
                            entry = e
                            customMeasurement = null
                            signature = Signature.available(e).let { if (Signature.HARMAN in it) Signature.HARMAN else Signature.PUBLISHED }
                            picking = false
                            query = ""
                            apply()
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Column {
                        Text(e.name, style = MaterialTheme.typography.titleMedium)
                        Text(e.label, style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
                    }
                }
            }
        }

        // ---- signature & taste ----
        val current = entry
        if (current != null && !picking) {
            item {
                SectionLabel("Sound signature")
                val options = if (customMeasurement != null) Signature.entries.filter { it != Signature.PUBLISHED && (it == Signature.CUSTOM || it.targetFile(current) != null) }
                else Signature.available(current)
                options.forEach { sig ->
                    ChoiceRow(sig.title, sig.detail, signature == sig, badge = if (sig == Signature.HARMAN) "Most liked" else null, onClick = {
                        if (sig == Signature.CUSTOM && customTarget == null) targetPicker.launch(arrayOf("*/*"))
                        else { signature = sig; apply() }
                    })
                }
                if (!current.supportsCustomTargets && customMeasurement == null) {
                    Text("Only the reviewer's profile is available for this measurement (raw data isn't published, or the rig needs its own targets).",
                        style = MaterialTheme.typography.bodySmall, color = Svan.TextFaint, modifier = Modifier.padding(4.dp))
                }
                tuning?.calibration?.let { c ->
                    Text("${c.basis} · %.0f–%.0f Hz\nRMS fit error %.2f dB · maximum %.2f dB. This is frequency-response correction, not a personal hearing or SPL measurement. Use data and targets from compatible measurement rigs.".format(c.lowHz,c.highHz,tuning.fitRmsDb,c.maxErrorDb),
                        style=MaterialTheme.typography.bodySmall,color=Svan.TextMuted,modifier=Modifier.padding(4.dp))
                }
                SectionLabel("Your taste")
                SvanCard {
                    Column {
                        ValueSlider("Correction amount",correctionAmount,{"%.0f%%".format(it*100)},
                            toSlider={it.toFloat()},fromSlider={Math.round(it*100)/100.0},onChange={correctionAmount=it},step=.01,
                            entryRange=0.0..1.0,entryUnit="0–1")
                        ValueSlider("Bass", bassDb, { formatDb(it) },
                            toSlider = { ((it + 6) / 12).toFloat() }, fromSlider = { Math.round((it * 12 - 6) * 10) / 10.0 },
                            onChange = { bassDb = it }, entryRange = -6.0..6.0, entryUnit = "dB")
                        ValueSlider("Brightness", tilt, { v -> if (v == 0.0) "Neutral" else if (v > 0) "Brighter %.1f".format(v * 10) else "Warmer %.1f".format(-v * 10) },
                            toSlider = { ((it + 0.6) / 1.2).toFloat() }, fromSlider = { Math.round((it * 1.2 - 0.6) * 100) / 100.0 },
                            onChange = { tilt = it }, step = 0.01)
                        Text("Resolution (bands)", style = MaterialTheme.typography.bodyMedium, color = Svan.TextMuted)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(32, 64, 96).forEach { n -> Pill("$n", bandCount == n, { bandCount = n }) }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (busy) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Svan.Gold)
                                Spacer(Modifier.width(8.dp))
                                Text("Tuning…", style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
                            } else {
                                Text(message ?: tuning?.let { "${it.bands.size} bands · RMS fit error %.2f dB".format(it.fitRmsDb) } ?: "",
                                    style = MaterialTheme.typography.bodySmall, color = Svan.Gold)
                            }
                        }
                    }
                }
            }
        } else if (message != null) {
            item { Text(message!!, style = MaterialTheme.typography.bodySmall, color = Svan.Ember, modifier = Modifier.padding(4.dp)) }
        }

        // ---- tuners ----
        item { BassTunerCard(eq.bass) }
        item { VocalTunerCard(eq.vocal) }
        item { InstrumentTunerCard(eq.instrument) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun TunerHeader(title: String, subtitle: String) {
    SectionLabel(title)
    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
}

@Composable
private fun TuningGuidance(text: String) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(12.dp))
            .background(Svan.SurfaceHigh).border(1.dp, Svan.Outline, RoundedCornerShape(12.dp)).padding(10.dp),
    ) {
        Text("LISTENING TIP", style = MaterialTheme.typography.labelSmall, color = Svan.Gold)
        Spacer(Modifier.height(3.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
    }
}

@Composable
private fun <T> PresetRow(presets: List<Pair<String, T>>, current: T, onPick: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.forEach { (name, preset) -> Pill(name, current == preset, { onPick(preset) }) }
    }
    Spacer(Modifier.height(10.dp))
}

private fun pct(v: Double) = "%d%%".format(Math.round(v * 100))

@Composable
private fun BassTunerCard(b: BassTuner) {
    TunerHeader("Bass tuner", "Level, depth and feel of the low end.")
    TuningGuidance("Start with one tick: Amount +0.1 dB, Depth +1 Hz, or Feel +1%. Presets apply a full profile. Compare the same passage at matched loudness; boosts use headroom, so back off if bass gets rough, boomy, or tiring.")
    PresetRow(BassTuner.PRESETS, b) { p -> SvanRepository.update { it.copy(bass = p) } }
    SvanCard {
        Column {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
            val dialSize = ((maxWidth / 3) - 16.dp).coerceIn(56.dp, 78.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Knob("Amount", b.amountDb, -6.0, 12.0, ::formatDb, step = 0.1, entryUnit = "dB", size = dialSize,
                    onChange = { v -> SvanRepository.update { it.copy(bass = it.bass.copy(amountDb = v)) } })
                Knob("Depth", b.focusHz, 40.0, 160.0, { f -> "%.0f Hz".format(f) }, default = 80.0, step = 1.0, entryUnit = "Hz", size = dialSize,
                    onChange = { v -> SvanRepository.update { it.copy(bass = it.bass.copy(focusHz = v)) } })
                Knob("Feel", b.character, -1.0, 1.0, { c ->
                    when {
                        c == 0.0 -> "Natural"
                        c > 0 -> "Punch ${pct(c)}"
                        else -> "Sustain ${pct(-c)}"
                    }
                }, step = 0.01, size = dialSize, onChange = { v -> SvanRepository.update { it.copy(bass = it.bass.copy(character = v)) } })
            }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Knob("Resolve", b.resolve, 0.0, 1.0, { r ->
                    when {
                        r == 0.0 && b.resolveAuto -> "Auto"
                        r == 0.0 -> "Off"
                        else -> pct(r)
                    }
                }, step = 0.01, onChange = { v -> SvanRepository.update { it.copy(bass = it.bass.copy(resolve = v)) } })
            }
            SettingSwitchRow("Svaresa manages Resolve",
                "Auto: while Svaresa is driving, Resolve is raised to ${pct(BassTuner.AUTO_RESOLVE)} (never below your own setting, which stays saved). Off: only your dial applies.",
                b.resolveAuto, { on -> SvanRepository.update { it.copy(bass = it.bass.copy(resolveAuto = on)) } })
            Text("Resolve protects each bass note's own shape: it limits how far Feel may bend an attack (to about 1.5 dB) or a sustained body (about 0.75 dB) and follows the note instead of single bass cycles, so low notes keep a defined, unhurried shape. Both channels share one gain, so bass never drifts between left and right. It does not add bass, sharpen notes or detect instruments. Native audiophile engine only.",
                style = MaterialTheme.typography.bodySmall, color = Svan.TextFaint)
            Spacer(Modifier.height(6.dp))
            Text("Depth: deep sub (40 Hz) ↔ mid-bass (160 Hz). Feel: sustain lets notes bloom; punch sharpens kicks and " +
                "tightens tails. Turn the rim or drag sideways; tap the value for exact entry. Double-tap to reset. A native transient shaper in the audiophile engine; an approximation on system effects. Tuned on synthetic signals; results vary with music.",
                style = MaterialTheme.typography.bodySmall, color = Svan.TextFaint)
        }
    }
}

@Composable
private fun VocalTunerCard(v: app.svan.model.VocalTuner) {
    TunerHeader("Vocal tuner", "Centre tone and upper-mid dynamics.")
    TuningGuidance("Move one dial by one tick (1%) at a time. Presets change several controls together. Compare the same short passage at matched loudness; double-tap a dial to reset it.")
    PresetRow(app.svan.model.VocalTuner.PRESETS, v) { p -> SvanRepository.update { it.copy(vocal = p) } }
    SvanCard {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Knob("Intimacy", v.intimacy, 0.0, 1.0, ::pct, step = 0.01,
                    onChange = { x -> SvanRepository.update { it.copy(vocal = it.vocal.copy(intimacy = x)) } })
                Knob("Warmth", v.warmth, 0.0, 1.0, ::pct, step = 0.01,
                    onChange = { x -> SvanRepository.update { it.copy(vocal = it.vocal.copy(warmth = x)) } })
                Knob("Smooth", v.smoothness, 0.0, 1.0, ::pct, step = 0.01,
                    onChange = { x -> SvanRepository.update { it.copy(vocal = it.vocal.copy(smoothness = x)) } })
            }
            Spacer(Modifier.height(6.dp))
            Text("Shapes the centre of the stereo mix, which can include vocals and centred instruments. Smooth reacts to upper-mid energy, not voice recognition. System effects use a coarse approximation; music listening tests are pending.",
                style = MaterialTheme.typography.bodySmall, color = Svan.TextFaint)
        }
    }
}

@Composable
private fun InstrumentTunerCard(i: app.svan.model.InstrumentTuner) {
    TunerHeader("Orchestral amplifier", "Width, vocal layers and the space within the recording.")
    TuningGuidance("Move one dial by one tick (1%) at a time. Presets change several controls together. Compare the same short passage at matched loudness; double-tap a dial to reset it.")
    PresetRow(app.svan.model.InstrumentTuner.PRESETS, i) { p -> SvanRepository.update { it.copy(instrument = p) } }
    SvanCard {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Knob("Backing vocals", i.backingVocals, 0.0, 1.0, ::pct, step = 0.01, modifier = Modifier.weight(1f),
                    onChange = { x -> SvanRepository.update { it.copy(instrument = it.instrument.copy(backingVocals = x)) } })
                Knob("Binaural", i.spatialDetail, 0.0, 1.0, ::pct, step = 0.01, modifier = Modifier.weight(1f),
                    onChange = { x -> SvanRepository.update { it.copy(instrument = it.instrument.copy(spatialDetail = x)) } })
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Knob("Space", i.space, -1.0, 1.0, { s ->
                    when {
                        s == 0.0 -> "Natural"
                        s > 0 -> "Spacious ${pct(s)}"
                        else -> "Intimate ${pct(-s)}"
                    }
                }, step = 0.01, modifier = Modifier.weight(1f), onChange = { x -> SvanRepository.update { it.copy(instrument = it.instrument.copy(space = x)) } })
                Knob("Instruments", i.instruments, 0.0, 1.0, ::pct, step = 0.01, modifier = Modifier.weight(1f),
                    onChange = { x -> SvanRepository.update { it.copy(instrument = it.instrument.copy(instruments = x)) } })
            }
            Spacer(Modifier.height(6.dp))
            Text("Backing vocals and Binaural enhance eligible detail already present in the stereo recording. Fast uses frequency and motion cues and keeps the static response on a signal with no centre. Detailed uses decorrelated residual detail and adds about 21–23 ms of latency. Detailed holds on coherent panned or already-wide material. Both preserve the spatial stage's mono sum and share an automatic-detail energy budget. They cannot isolate backing vocals or identify instruments. They add no reverb or binaural-beat tones. Space and Instruments are separate manual widening controls. Choose the mode in Hi-Fi; start low and compare in Lab at matched loudness. Listening qualification is pending.",
                style = MaterialTheme.typography.bodySmall, color = Svan.TextFaint)
            Spacer(Modifier.height(6.dp))
            Text("Shapes stereo side energy above the bass range. It cannot identify individual instruments; a centred instrument will not be boosted. No added reverb. Requires an app actively using the audiophile engine; has no effect on system effects.",
                style = MaterialTheme.typography.bodySmall, color = Svan.TextFaint)
        }
    }
}
