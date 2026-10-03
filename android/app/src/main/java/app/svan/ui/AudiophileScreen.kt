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
import app.svan.CaptureService
import app.svan.SessionRouter
import app.svan.SvanRepository
import app.svan.model.DitherChoice
import app.svan.model.EngineMode
import app.svan.model.QualityMode
import kotlinx.coroutines.delay

/** Neutron-style audiophile settings. Every number shown here is a measured test result. */
@Composable
fun AudiophileScreen(onStartCapture: () -> Unit, onStopCapture: () -> Unit) {
    val s by SvanRepository.settings.collectAsState()
    var running by remember { mutableStateOf(CaptureService.isRunning) }
    var routes by remember { mutableStateOf(SessionRouter.snapshot.toList()) }
    LaunchedEffect(Unit) {
        while (true) {
            running = CaptureService.isRunning
            routes = SessionRouter.snapshot.toList()
            delay(700)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        ScreenTitle("Hi-Fi", "Audiophile processing. 64-bit is always on.")

        SectionLabel("Engine")
        SvanCard {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (running) Svan.Glow else Svan.TextFaint))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (running) "Audiophile engine running" else "Audiophile engine off", style = MaterialTheme.typography.titleMedium)
                        val b = routes.count { it.owner == SessionRouter.Owner.ENGINE_B_MUTED }
                        val a = routes.count { it.owner == SessionRouter.Owner.ENGINE_A }
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
        EngineMode.entries.forEach { m ->
            ChoiceRow(m.title, m.detail, s.engineMode == m, onClick = { SvanRepository.updateSettings { it.copy(engineMode = m) } },
                badge = if (m == EngineMode.AUTO) "Recommended" else null)
        }

        SectionLabel("Processing quality")
        QualityMode.entries.forEach { q ->
            ChoiceRow(q.title, q.detail, s.quality == q, onClick = { SvanRepository.updateSettings { it.copy(quality = q) } },
                badge = if (q == QualityMode.AUDIOPHILE) "Neutron-grade" else null)
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
                SettingSwitchRow("Auto headroom", "Pre-attenuates by the curve's largest boost, so EQ boosts never clip.",
                    s.autoHeadroom, { on -> SvanRepository.updateSettings { it.copy(autoHeadroom = on) } })
                SettingSwitchRow("Automatic gain protection", "Detects real overloads (hot masters, inter-sample peaks) and lowers gain to −0.1 dBFS without pumping.",
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
                "App audio → 64-bit float → preamp & auto headroom → ${s.quality.oversample}x oversampling → " +
                    "parametric EQ (up to 128 bands, double precision) → downsampling → gain protection → " +
                    if (s.dither == DitherChoice.OFF) "float output" else "${s.outputBits}-bit ${s.dither.title} dither → output",
                style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
