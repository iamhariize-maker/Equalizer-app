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
import app.svan.SessionRouter
import app.svan.SvanRepository
import app.svan.model.DitherChoice
import app.svan.model.EngineMode
import app.svan.model.QualityMode
import kotlinx.coroutines.delay

/** Svan processing controls and observed routing status. */
@Composable
fun AudiophileScreen(onStartCapture: () -> Unit, onStopCapture: () -> Unit) {
    val context = LocalContext.current
    val prefs = SessionRouter.appPreferences()
    val s by SvanRepository.settings.collectAsState()
    var stats by remember { mutableStateOf(CaptureService.stats) }
    var systemRunning by remember { mutableStateOf(SystemEqService.isRunning) }
    var knownApps by remember { mutableStateOf(emptySet<String>()) }
    var systemApps by remember { mutableStateOf(prefs.systemOnlyPackages()) }
    var verdicts by remember { mutableStateOf(SessionRouter.compat().all()) }
    var running by remember { mutableStateOf(CaptureService.isRunning) }
    var routes by remember { mutableStateOf(SessionRouter.snapshot.toList()) }
    LaunchedEffect(Unit) {
        while (true) {
            stats = CaptureService.stats
            systemRunning = SystemEqService.isRunning
            running = CaptureService.isRunning
            verdicts = SessionRouter.compat().all()
            systemApps = prefs.systemOnlyPackages()
            knownApps = verdicts.keys + systemApps + SessionRouter.snapshot.map { it.pkg }
            routes = SessionRouter.snapshot.toList()
            delay(700)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        ScreenTitle("Hi-Fi", "Choose processing, then check what each app actually uses.")

        SectionLabel("Background equalizer")
        SvanCard {
            Column {
                Text(if (systemRunning) "System equalizer active" else "System equalizer stopped", style = MaterialTheme.typography.titleMedium)
                Text("Keeps system effects active when you leave Svan. Android may still stop the app; phone testing is pending.",
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
                        Text(if (running) "Audiophile engine running" else "Audiophile engine off", style = MaterialTheme.typography.titleMedium)
                        val b = routes.filter { it.owner == SessionRouter.Owner.ENGINE_B_MUTED }.map { it.pkg }.distinct().size
                        val a = routes.filter { it.owner == SessionRouter.Owner.ENGINE_A }.map { it.pkg }.distinct().size
                        Text(
                            if (running) "$b app(s) on the full 64-bit chain · $a on system effects"
                            else "Apps use system effects (gain-per-band). Start to run the full chain.",
                            style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
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
        if (running) {
            SvanCard {
                Column {
                    if (routes.none { it.owner == SessionRouter.Owner.ENGINE_B_MUTED }) {
                        Text("No app is on the audiophile chain yet. Undetected or capture-blocked audio is not processed by it.",
                            style = MaterialTheme.typography.bodySmall, color = Svan.Ember)
                    }
                    stats?.let { st ->
                        Text("Output queue %.1f ms · buffer %.1f ms".format(st.queuedMs, st.bufferMs), style = MaterialTheme.typography.bodySmall)
                        Text("DSP %.1f ms · load %.1f%% · underruns %d".format(st.dspLatencyMs, st.dspPercent, st.underruns), style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
                        Text("Applied gain %.1f dB · protection %.1f dB".format(st.gainDb, st.protectionDb), style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
                    }
                    Text("These readings exclude capture, Android mixing and Bluetooth delay. If playback stutters, try Efficient; use system effects for the shortest path.",
                        style = MaterialTheme.typography.bodySmall, color = Svan.TextFaint)
                }
            }
        }
        EngineMode.entries.forEach { m ->
            ChoiceRow(m.title, m.detail, s.engineMode == m, onClick = { SvanRepository.updateSettings { it.copy(engineMode = m) } },
                badge = if (m == EngineMode.AUTO) "Recommended" else null)
        }

        SectionLabel("Apps & engines")
        Text("Play audio in an app to see it here. Auto uses the audiophile engine when capture is allowed. Changing a choice stops that engine; start it again to apply.",
            style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
        if (knownApps.isEmpty()) Text("No audio apps detected yet.", style = MaterialTheme.typography.bodySmall, color = Svan.TextFaint)
        knownApps.sorted().forEach { pkg ->
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
                        else -> "No active audio session"
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall, color = Svan.Gold)
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

        SectionLabel("Processing quality")
        QualityMode.entries.forEach { q ->
            ChoiceRow(q.title, q.detail, s.quality == q, onClick = { SvanRepository.updateSettings { it.copy(quality = q) } },
                badge = if (q == QualityMode.AUDIOPHILE) "4× precision" else null)
        }

        SectionLabel("Output word length & dither")
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
        SvanCard {
            Column {
                SettingSwitchRow("Auto headroom", "Lowers gain only as much as the EQ boost requires. Existing negative preamp counts toward headroom. Applies to both engines.",
                    s.autoHeadroom, { on -> SvanRepository.updateSettings { it.copy(autoHeadroom = on) } })
                SettingSwitchRow("Automatic gain protection", "Capture: reduces gain when output samples overload, until the next EQ edit. System effects: Android's limiter. Does not measure true inter-sample peaks.",
                    s.gainProtection, { on -> SvanRepository.updateSettings { it.copy(gainProtection = on) } })
            }
        }

        SectionLabel("System effects resolution")
        Text("Bands used when an app runs on Android's DynamicsProcessing. More bands follow your curve more closely; each update costs ~1 ms per band.",
            style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted, modifier = Modifier.padding(bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(64, 128, 256).forEach { n ->
                Pill("$n bands", s.systemBands == n, { SvanRepository.updateSettings { it.copy(systemBands = n) } })
            }
        }

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
