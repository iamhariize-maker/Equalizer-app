package app.svan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.svan.EqController
import app.svan.SystemEqService
import app.svan.CaptureService
import androidx.compose.ui.platform.LocalContext
import app.svan.DetectionMonitor
import app.svan.SessionRouter
import app.svan.Verification
import app.svan.SvanRepository
import app.svan.model.DitherChoice
import app.svan.model.EngineMode
import app.svan.model.QualityMode
import app.svan.listening.ProofCapture
import app.svan.listening.ProofRecorder
import app.svan.model.SpatialMode
import app.svan.RatePolicy
import kotlinx.coroutines.delay

/** Svan processing controls and observed routing status. */
@Composable
fun AudiophileScreen(onStartCapture: () -> Unit, onStopCapture: () -> Unit) {
    val context = LocalContext.current
    val prefs = SessionRouter.appPreferences()
    val s by SvanRepository.settings.collectAsState()
    val eq by SvanRepository.eq.collectAsState()
    var stats by remember { mutableStateOf(CaptureService.stats) }
    var systemRunning by remember { mutableStateOf(SystemEqService.isRunning) }
    var knownApps by remember { mutableStateOf(emptySet<String>()) }
    var systemApps by remember { mutableStateOf(prefs.systemOnlyPackages()) }
    var verdicts by remember { mutableStateOf(SessionRouter.compat().all()) }
    var running by remember { mutableStateOf(CaptureService.isRunning) }
    var routes by remember { mutableStateOf(SessionRouter.snapshot.toList()) }
    var showRules by remember { mutableStateOf(false) }
    if (showRules) PolicyRulesScreen { showRules = false }
    val detection by DetectionMonitor.status.collectAsState()
    val captureStartup by CaptureService.startupMessage.collectAsState()
    val captureRecovery by CaptureService.recoveryMessage.collectAsState()
    LaunchedEffect(Unit) {
        while (true) {
            stats = CaptureService.stats
            systemRunning = SystemEqService.isRunning
            running = CaptureService.isRunning
            verdicts = SessionRouter.compat().all()
            systemApps = prefs.systemOnlyPackages()
            knownApps = verdicts.keys + systemApps + SessionRouter.snapshot.map { it.pkg } +
                DetectionMonitor.status.value.sessions.filter { source ->
                    app.svan.MusicSourcePolicy.immediate(source.session) || SessionRouter.snapshot.any { it.sessionId == source.session.sessionId }
                }.map { it.session.packageName }
            knownApps = knownApps.filterNot { app.svan.MusicSourcePolicy.excludedPackage(it) }.toSet()
            routes = SessionRouter.snapshot.toList()
            delay(700)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        ScreenTitle("Hi-Fi", "Choose processing, then check what each app actually uses.")
        OutlinedButton(onClick = { showRules = true }, modifier = Modifier.fillMaxWidth()) { Text("How Svaresa decides") }

        DetectionCard(captureStats = stats)

        SectionLabel("Background equalizer")
        SvanCard {
            Column {
                Text(if (systemRunning) "Background service running" else "System equalizer stopped", style = MaterialTheme.typography.titleMedium)
                Text("Keeps effects available when you leave Svan. Check Is it working? for an actual player connection; Android may stop background audio.",
                    style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
                OutlinedButton(onClick = { if (systemRunning) SystemEqService.stop(context) else SystemEqService.start(context) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (systemRunning) "Stop all processing" else "Start system equalizer")
                }
            }
        }

        SectionLabel("Engine")
        SvanCard {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (running) Svan.Glow else Svan.TextFaint))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        val b = routes.filter { it.owner == SessionRouter.Owner.ENGINE_B_MUTED }.map { it.pkg }.distinct().size
                        val a = routes.filter { it.owner == SessionRouter.Owner.ENGINE_A }.map { it.pkg }.distinct().size
                        Text(if (running && b > 0) "Audiophile engine connected" else if (running) "Waiting for a music connection" else "Audiophile engine off", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (running) "$b app(s) on the full 64-bit chain · $a on system effects"
                            else if (systemRunning) "System effects available. Check app rows for actual processing."
                            else "Processing is stopped. Start the system equalizer or audiophile engine.",
                            style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (captureStartup.isNotBlank() && !running) Text(captureStartup,
                    style = MaterialTheme.typography.bodySmall, color = Svan.Ember)
                if (s.engineMode == EngineMode.SYSTEM_ONLY) {
                    Text("Engine mode is “System effects only”.", style = MaterialTheme.typography.bodySmall, color = Svan.TextFaint)
                } else if (running) {
                    OutlinedButton(onClick = onStopCapture, modifier = Modifier.fillMaxWidth()) { Text("Stop audiophile engine") }
                } else {
                    Button(
                        onClick = onStartCapture, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Svan.Gold, contentColor = Svan.OnGold),
                    ) { Text("Start audiophile engine") }
                }
            }
        }
        if (captureRecovery.isNotBlank()) Text(captureRecovery, style = MaterialTheme.typography.bodySmall, color = Svan.Ember)
        if (running) {
            SvanCard {
                Column {
                    if (routes.none { it.owner == SessionRouter.Owner.ENGINE_B_MUTED }) {
                        Text("No music connected. Play a song; if it stays disconnected, try the optional Music detection options above. The DSP is idle until a player connects.",
                            style = MaterialTheme.typography.bodySmall, color = Svan.Ember)
                    }
                    stats?.let { st ->
                        Text("Signal peak · in %.1f dBFS · out %.1f dBFS".format(st.inputPeakDb, st.outputPeakDb), style = MaterialTheme.typography.bodySmall, color = Svan.Gold)
                        Text("Output queue %.1f ms · buffer %.1f ms".format(st.queuedMs, st.bufferMs), style = MaterialTheme.typography.bodySmall)
                        Text("DSP %.1f ms · load %.1f%% · underruns %d".format(st.dspLatencyMs, st.dspPercent, st.underruns), style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
                        Text("Spatial blend: %.0f%% Detailed".format(st.detailedMix * 100), style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
                        Text("Spatial mode applied: ${CaptureService.epoch?.appliedSettings?.spatialMode?.title ?: "—"}", style = MaterialTheme.typography.bodySmall)
                        CaptureService.rateFacts?.let { Text(it.summary(), style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted) }
                        Text("Applied gain %.1f dB · protection %.1f dB".format(st.gainDb, st.protectionDb), style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
                    }
                    Text("These readings exclude capture, Android mixing and Bluetooth delay. Svan increases buffering after underruns and returns to system effects if capture repeatedly cannot keep up.",
                        style = MaterialTheme.typography.bodySmall, color = Svan.TextFaint)
                }
            }
        }
        ProofRecorderCard(running)
        EngineMode.entries.forEach { m ->
            ChoiceRow(m.title, m.detail, s.engineMode == m, onClick = { SvanRepository.updateSettings { it.copy(engineMode = m) } },
                badge = if (m == EngineMode.SYSTEM_ONLY) "Recommended" else null)
        }

        SectionLabel("Apps & engines")
        Text("Notifications, interface effects and known utility sounds are left outside Svan processing. Unknown players must show sustained playback. Their alerts remain audible normally. Try Spotify, Amazon Music, YouTube Music, Apple Music, or another player. Svan lists it when Android exposes a playback session, then shows the engine available on this phone. Engine B needs capture permission; direct/bit-perfect modes may bypass system effects and capture. Restart capture after changing an app's engine.",
            style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
        if (knownApps.isEmpty()) Text("No audio apps detected yet.", style = MaterialTheme.typography.bodySmall, color = Svan.TextFaint)
        val livePkgs = routes.map { it.pkg }.toSet() + detection.sessions.map { it.session.packageName }
        // Apps with a live session first; everything else is only remembered from earlier.
        knownApps.sortedWith(compareBy({ it !in livePkgs }, { it })).forEach { pkg ->
            val label = remember(pkg) { runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg) }
            val appRoutes = routes.filter { it.pkg == pkg }
            SvanCard {
                Column {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    Text(pkg, style = MaterialTheme.typography.bodySmall, color = Svan.TextFaint)
                    val status = when {
                        appRoutes.any { it.owner == SessionRouter.Owner.PROBING } -> "Checking capture…"
                        appRoutes.any { it.owner == SessionRouter.Owner.ENGINE_B_MUTED } -> "Audiophile engine · full DSP"
                        appRoutes.any { it.owner == SessionRouter.Owner.ENGINE_A && it.sessionId in EqController.globalEq.attachedSessions } -> "System effects · gain per band"
                        appRoutes.isNotEmpty() -> "Unprocessed · system effect unavailable"
                        pkg in livePkgs -> "Detected · attaching…"
                        else -> "Not playing right now (remembered from earlier)"
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall, color = Svan.Gold)
                    Text(when {
                        !eq.enabled -> "Processing off. Saved tuner values are retained."
                        appRoutes.any { it.owner == SessionRouter.Owner.ENGINE_B_MUTED } -> "Orchestral controls and Bass Resolve applied through native capture. ${if (CaptureService.epoch?.detailed == true) "Detailed" else "Fast"} spatial mode."
                        appRoutes.any { it.owner == SessionRouter.Owner.ENGINE_A } -> "System-effects approximation for EQ, bass Feel and vocal tone. Orchestral controls and Resolve unavailable on this route."
                        else -> "Tuner capability unavailable until a player is connected."
                    }, style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
                    appRoutes.firstOrNull { it.owner == SessionRouter.Owner.ENGINE_A }?.let { r ->
                        val v = SessionRouter.verification[r.sessionId]
                        val path = SessionRouter.evidence[r.sessionId]?.pathLabel.orEmpty()
                        val line = listOfNotNull(path.ifEmpty { null }?.let { "$it output" }, v?.takeIf { it != Verification.UNKNOWN }?.summary).joinToString(" · ")
                        if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall,
                            color = if (v == Verification.PROCESSING) Svan.Gold else Svan.Ember)
                    }
                    Text(when (verdicts[pkg]) {
                        "BLOCKED" -> "Capture blocked by this app; system effects remain available."
                        "CAPTURABLE" -> "Capture supported on the last check."
                        else -> "Capture compatibility not checked yet."
                    }, style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Auto", pkg !in systemApps, { prefs.setSystemOnly(pkg, false); systemApps = prefs.systemOnlyPackages() })
                        Pill("System effects", pkg in systemApps, { prefs.setSystemOnly(pkg, true); systemApps = prefs.systemOnlyPackages() })
                    }
                }
            }
        }

        SectionLabel("Spatial processing")
        SpatialMode.entries.forEach { mode ->
            ChoiceRow(mode.title, mode.detail, s.spatialMode == mode, onClick = { SvanRepository.updateSettings { it.copy(spatialMode = mode) } })
        }
        Text("Starting capture in Auto or Detailed allows live, smooth spatial changes. Its added delay stays fixed during switches. Capture started in Fast needs a restart to enable Auto or Detailed; rate changes also need a restart. Short load spikes grow buffering and fade spatial work toward Fast while capture keeps running. Persistent failure can still return to system effects. Phone qualification pending.", style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
        SectionLabel("Capture rate")
        RatePolicy.Mode.entries.forEach { mode ->
            val label = when (mode) { RatePolicy.Mode.SAFE -> "Safe 48 kHz"; RatePolicy.Mode.EVIDENCE_HIGH_RATE -> "High-rate (device evidence)"; RatePolicy.Mode.EXPERIMENTAL_192K -> "Experimental 192 kHz" }
            val detail = when (mode) { RatePolicy.Mode.SAFE -> "Default and fallback. Stereo float transport."; RatePolicy.Mode.EVIDENCE_HIGH_RATE -> "Try 88.2/96 kHz only when reported by the routed output; fall back serially to 48 kHz."; RatePolicy.Mode.EXPERIMENTAL_192K -> "Also try reported 176.4/192 kHz. Phone performance and listening checks are pending." }
            ChoiceRow(label, detail, s.captureRateMode == mode, onClick = { SvanRepository.updateSettings { it.copy(captureRateMode = mode) } })
        }
        Text("Client rates describe Svan's transport. Original streaming-file rate and physical DAC rate remain unknown.", style = MaterialTheme.typography.bodySmall, color = Svan.TextFaint)

        SectionLabel("Experimental bass unmasking")
        SettingSwitchRow("Experimental bass unmasking", "Off by default. May cut sustained peaks outside a detected bass note's harmonics, up to 2 dB combined. Validated on synthetic fixtures only; it can misclassify music. Auto master never enables this.",
            s.experimentalBassUnmask, { on -> SvanRepository.updateSettings { it.copy(experimentalBassUnmask = on) } })
        if (s.experimentalBassUnmask && running) CaptureService.bassUnmaskDiagnostics()?.let { d ->
            if (d.size == 5) Text("70/110/180/280 Hz cuts: ${d.take(4).joinToString { "%.2f dB".format(it) }} · estimated note ${if (d[4] > 0) "%.1f Hz".format(d[4]) else "unknown"}", style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
        }

        SectionLabel("Capture processing quality")
        Text("Quality and dither changes take effect when capture restarts. This keeps filter latency stable during a song. System effects use Android’s own processing.",
            style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
        QualityMode.entries.forEach { q ->
            ChoiceRow(q.title, q.detail, s.quality == q, onClick = { SvanRepository.updateSettings { it.copy(quality = q) } },
                badge = if (q == QualityMode.AUDIOPHILE) "4× precision" else null)
        }

        SectionLabel("Float output & optional dither")
        Text("Float output is recommended. Selecting 16 or 24 bits quantizes Svan’s signal; it does not change the Bluetooth codec or DAC format.",
            style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(16, 24).forEach { bits ->
                Pill("$bits-bit", s.outputBits == bits, { SvanRepository.updateSettings { it.copy(outputBits = bits) } })
            }
        }
        Spacer(Modifier.height(8.dp))
        DitherChoice.entries.forEach { d ->
            ChoiceRow(d.title, d.detail, s.dither == d, onClick = { SvanRepository.updateSettings { it.copy(dither = d) } })
        }

        SectionLabel("Gain staging")
        val guarded=s.effectiveFor(eq)
        if(eq.smartProtection) Text("Svaresa keeps both protections active. Your manual choices return when Auto master is off.",
            style=MaterialTheme.typography.bodySmall,color=Svan.TextMuted)
        SvanCard {
            Column {
                SettingSwitchRow("Auto headroom", "Lowers gain only as much as the EQ boost requires. Existing negative preamp counts toward headroom. Applies to both engines.",
                    guarded.autoHeadroom, { on -> SvanRepository.updateSettings { it.copy(autoHeadroom = on) } }, enabled=!eq.smartProtection)
                SettingSwitchRow("Automatic gain protection", "Capture: 8× reconstructed-peak detection, −1.3 dB detector target, 3 ms lookahead plus 64 detector frames; stereo-linked gain with 250 ms recovery. System effects: Android's sample limiter.",
                    guarded.gainProtection, { on -> SvanRepository.updateSettings { it.copy(gainProtection = on) } }, enabled=!eq.smartProtection)
            }
        }

        SectionLabel("Selective dynamic EQ")
        Text("Svaresa in the capture engine reduces sustained local resonances at 120, 330, 3000 and 6500 Hz. No automatic boost; up to 1.5 dB per band and 3 dB combined. Short transients are left alone. System effects cannot run this processor.",
            style=MaterialTheme.typography.bodySmall,color=Svan.TextMuted,modifier=Modifier.padding(4.dp))
        SectionLabel("System effects resolution")
        Text("Curve points sent to Android. Effective resolution depends on its processing window and the device; accepted settings do not guarantee independent bands.",
            style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted, modifier = Modifier.padding(bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(64, 128, 256).forEach { n ->
                Pill("$n bands", s.systemBands == n, { SvanRepository.updateSettings { it.copy(systemBands = n) } })
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("System processing window", style = MaterialTheme.typography.titleSmall)
        Text("Detailed uses a longer window for bass resolution, with more delay. Fast reduces delay. Actual output depends on your phone.",
            style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Fast · 10 ms", s.systemFrameMs == 10, { SvanRepository.updateSettings { it.copy(systemFrameMs = 10) } })
            Pill("Balanced · 40 ms", s.systemFrameMs == 40, { SvanRepository.updateSettings { it.copy(systemFrameMs = 40) } })
        }
        Spacer(Modifier.height(8.dp))
        Pill("Detailed · 80 ms", s.systemFrameMs == 80, { SvanRepository.updateSettings { it.copy(systemFrameMs = 80) } })

        SectionLabel("Signal path")
        SvanCard {
            Text(
                "Capture engine: app audio → Android float capture → double precision DSP → preamp & auto headroom → ${s.quality.oversample}x oversampling → " +
                    "parametric EQ (128 manual bands plus tuning, double precision) → downsampling → gain protection → " +
                    if (s.dither == DitherChoice.OFF) "float output" else "${s.outputBits}-bit ${s.dither.title} dither → output",
                style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Recording mode: saves what the audiophile engine received and what it sent out, plus a measured report. */
@Composable
private fun ProofRecorderCard(engineRunning: Boolean) {
    val context = LocalContext.current
    val state by ProofRecorder.state.collectAsState()
    var error by remember { mutableStateOf("") }
    var compatibility by remember { mutableStateOf(true) }
    var matchLevel by remember { mutableStateOf(false) }
    val countdown by ProofRecordingUi.countdown.collectAsState()
    SectionLabel("Recording mode")
    SvanCard {
        Column {
            Text("Film this phone with another phone. Svan saves aligned dry and processed audio, charts and a report. The clock above every tab is the file time. Sync adds a flash and speaker clicks for your camera.",
                style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
            Spacer(Modifier.height(8.dp))
            Text("Svan's digital output, excluding the DAC, Bluetooth and headphones. Louder often sounds better; compare at matched RMS level.",
                style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
            Spacer(Modifier.height(12.dp))
            when (val st = state) {
                is ProofRecorder.State.Recording -> {
                    Text("Recording. Use Sync, Mark now and Stop above; change settings on any tab.", style = MaterialTheme.typography.bodyMedium, color = Svan.Gold)
                }
                is ProofRecorder.State.Finishing -> Text("Analysing and saving…", style = MaterialTheme.typography.bodyMedium, color = Svan.Gold)
                is ProofRecorder.State.Done -> {
                    val r = st.result
                    Text("Saved · %.0f s".format(r.seconds), style = MaterialTheme.typography.titleMedium, color = Svan.Gold)
                    Text("Peak %.1f → %.1f dBFS · average %.1f → %.1f dBFS".format(r.dry.peakDbfs, r.processed.peakDbfs, r.dry.rmsDbfs, r.processed.rmsDbfs),
                        style = MaterialTheme.typography.bodySmall)
                    if (r.bands.isEmpty()) Text("No signal was captured. Play a song through the audiophile engine, then record again.",
                        style = MaterialTheme.typography.bodySmall, color = Svan.Ember)
                    if (r.processed.overs > 0) Text("${r.processed.overs} processed samples reached full scale.", style = MaterialTheme.typography.bodySmall, color = Svan.Ember)
                    if (r.droppedFrames > 0) Text("${r.droppedFrames} frames dropped because storage fell behind.", style = MaterialTheme.typography.bodySmall, color = Svan.Ember)
                    if (r.segments.size > 1) Text("${r.segments.size} stretches measured, one per setting change.", style = MaterialTheme.typography.bodySmall)
                    ProofCapture.lastExportNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Svan.Ember) }
                    ProofCapture.lastLocation?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { ProofCapture.dismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                }
                is ProofRecorder.State.Failed -> {
                    Text(st.message, style = MaterialTheme.typography.bodySmall, color = Svan.Ember)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { ProofCapture.dismiss() }, modifier = Modifier.fillMaxWidth()) { Text("OK") }
                }
                is ProofRecorder.State.Idle -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = compatibility, onCheckedChange = { compatibility = it })
                        Text("16-bit WAV for editors", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(if (compatibility) "16-bit PCM with TPDF dither; DSP stays unchanged. Also saves M4A." else "24-bit PCM WAV. Also saves M4A. Check VN import on your phone.",
                        style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = matchLevel, onCheckedChange = { matchLevel = it })
                        Text("Also save RMS-matched audio", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodySmall, color = Svan.Ember)
                    Button(
                        onClick = {
                            error = ""
                            runCatching { ProofCapture.start(context, if (compatibility) 16 else 24, matchLevel) }
                                .onFailure { error = it.message ?: "Could not start recording" }
                        },
                        enabled = engineRunning && countdown == null, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Svan.Gold, contentColor = Svan.OnGold),
                    ) { Text(if (engineRunning) "Start recording" else "Start the audiophile engine first") }
                    OutlinedButton(onClick = {
                        ProofRecordingUi.wavBits = if (compatibility) 16 else 24
                        ProofRecordingUi.matchLevel = matchLevel
                        ProofRecordingUi.countdown.value = 3
                    }, enabled = engineRunning && countdown == null, modifier = Modifier.fillMaxWidth()) { Text("Start with countdown") }
                }
            }
        }
    }
}
