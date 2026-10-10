package app.svan.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.svan.CaptureService
import app.svan.NativeEngine
import app.svan.SvanRepository
import app.svan.model.AudioSettings
import app.svan.svaramanas.SmartMode
import app.svan.svaramanas.Svaramanas
import java.util.Locale

/**
 * The Lab's working tools (owner decision D5): each is a real processor with a live readout from the engine, so the
 * listener can see it act before deciding to keep it. Settings are the same fields Hi-Fi used; nothing migrates.
 */
@Composable
fun LabToolsPage() {
    val s by SvanRepository.settings.collectAsStateWithLifecycle()
    val request by Svaramanas.request.collectAsStateWithLifecycle()
    var running by remember { mutableStateOf(CaptureService.isRunning) }
    var readouts by remember { mutableStateOf(CaptureService.processorReadouts()) }
    var unmask by remember { mutableStateOf(CaptureService.bassUnmaskDiagnostics()) }
    var stats by remember { mutableStateOf(CaptureService.stats) }
    var trim by remember { mutableStateOf(Svaramanas.appliedTrimDb) }
    ObserveWhileVisible(intervalMs = 250) {
        running = CaptureService.isRunning
        readouts = CaptureService.processorReadouts()
        unmask = CaptureService.bassUnmaskDiagnostics()
        stats = CaptureService.stats
        trim = Svaramanas.appliedTrimDb
    }
    fun update(change: (AudioSettings) -> AudioSettings) = SvanRepository.updateSettings(change)
    fun value(i: Int): Double? = readouts?.getOrNull(i)?.takeIf { it.isFinite() }
    val svaresa = request.enabled && request.mode == SmartMode.SVARESA

    Column {
        Text("Working tools for the audiophile engine. Each one is off until you switch it on, has not been listening-tested, " +
            "and shows what it is doing right now. Compare it on and off at the same volume before keeping it.",
            style = MaterialTheme.typography.bodyMedium, color = Svan.TextMuted)

        SectionLabel("Bass detail")
        Tool("Attack definition", "Lifts the pick and slap band (0.6–2.5 kHz) by up to 2 dB for about 10 ms when a bass note or hand-drum stroke starts. Nothing changes between notes.",
            s.bassAttack, running, { on -> update { it.copy(bassAttack = on) } }, value(NativeEngine.R_BASS_ATTACK)?.let { lift(it) })
        Tool("Sustain", "Holds the decaying tail of a bass note or tabla/dholak ring up by at most 3 dB, never above the note's own peak. Steady notes are left alone.",
            s.bassSustain, running, { on -> update { it.copy(bassSustain = on) } }, value(NativeEngine.R_BASS_SUSTAIN)?.let { lift(it) })
        Tool("Dimension", "Gives the bass harmonics above about 200 Hz a small phase difference between left and right, so the note has size. The fundamental and the mono sum are unchanged.",
            s.bassDimension, running, { on -> update { it.copy(bassDimension = on) } }, steady = true)
        Tool("Tube colour", "Adds a second harmonic to the bass (about −23 dB at a loud note) beside the odd-harmonic texture. Can thicken notes that already have strong partials.",
            s.bassTube, running, { on -> update { it.copy(bassTube = on) } }, steady = true)
        Tool("Bass unmasking", "May cut sustained peaks outside a detected bass note's harmonics, up to 2 dB combined. Validated on synthetic fixtures only; it can misclassify music. Auto master never enables it.",
            s.experimentalBassUnmask, running, { on -> update { it.copy(experimentalBassUnmask = on) } },
            unmask?.takeIf { it.size == 5 }?.let { d ->
                val cut = d.take(4).minOrNull() ?: 0.0
                if (cut < -0.05) "deepest cut %.1f dB%s".fmt(cut, if (d[4] > 0) " · note %.0f Hz".fmt(d[4]) else "") else null
            }, slow = true)

        SectionLabel("Highs")
        Tool("Analogue top", "Loud, sustained treble (6–12 kHz: crash cymbals, bright washes) eases by up to 2.5 dB, the way tape does. Quiet air and short clicks pass unchanged; nothing is lifted.",
            s.analogTop, running, { on -> update { it.copy(analogTop = on) } }, value(NativeEngine.R_ANALOG_TOP)?.let { cut(it) })
        Tool("Expression (winds and strings)", "Makes swells and decays in the 1–4 kHz singing range (sax, trumpet, violin) a little larger, up to 1.4 dB peak to peak, while the average level stays the same. Drum hits and picks are left alone.",
            s.expression, running, { on -> update { it.copy(expression = on) } }, value(NativeEngine.R_EXPRESSION)?.let { g -> if (kotlin.math.abs(g) >= 0.05) "%+.1f dB now".fmt(g) else null })

        SectionLabel("Svaresa processors")
        Tool("Selective dynamic EQ", "Reduces sustained local resonances at 120, 330, 3000 and 6500 Hz, up to 1.5 dB per band and 3 dB combined. No boost; short transients are left alone. Needs Svaresa in Sound.",
            request.selectiveEq, running && svaresa, { on -> Svaramanas.update { it.copy(selectiveEq = on) } },
            (NativeEngine.R_DYNAMIC_FIRST until NativeEngine.READOUTS).mapNotNull { value(it) }.minOrNull()?.let { cut(it) },
            waiting = if (!svaresa) "Saved. Works while Svaresa (Sound → Auto master) runs on the audiophile engine." else null)
        ReadOnlyTool("Shrill guard", "Svaresa eases sustained 4 kHz presence and 8 kHz sizzle when they stand out from the track's own balance. It is part of Svaresa, so it has no switch here.",
            when {
                !svaresa -> "Off · Svaresa is off"
                !running -> "Waiting for Hi-Fi"
                else -> listOfNotNull(value(NativeEngine.R_SHRILL_PRESENCE), value(NativeEngine.R_SHRILL_SIZZLE)).minOrNull()?.let { if (it < -0.05) "On · ${cut(it)}" else "On · idle now" } ?: "On"
            })

        SectionLabel("Level")
        Tool("Keep my level under Svaresa", "The loudness trim sets the level and the peak limiter guards the peaks, so Svaresa's boosts do not lower the whole track. Off: static headroom lowers the track by the largest boost, as before 0.5.14.",
            s.levelMatch, running && svaresa, { on -> update { it.copy(levelMatch = on) } },
            stats?.let { st -> "gain %+.1f dB · limiter %.1f dB".fmt(st.gainDb, st.protectionDb) },
            waiting = if (!svaresa) "Saved. Applies while Svaresa runs on the audiophile engine." else null, alwaysShow = true)
        Tool("Estimated level match on system effects", "System effects cannot measure your music, so the loudness trim there is an estimate from a reference spectrum and can be 1 to 2 dB off. Off by default.",
            s.estimatedTrimOnSystemEffects, svaresa && !running, { on -> update { it.copy(estimatedTrimOnSystemEffects = on) }; Svaramanas.refreshEq() },
            if (svaresa && !running) "trim %+.1f dB (estimated)".fmt(trim) else null,
            waiting = when { !svaresa -> "Saved. Applies while Svaresa runs on system effects."; running -> "Not used while the audiophile engine runs; it measures instead."; else -> null },
            alwaysShow = true)
    }
}

/**
 * One tool: its switch and a status line that never claims activity the engine did not report.
 * [steady] tools act on every bass note and have no level readout; [slow] readouts refresh every two seconds.
 */
@Composable
private fun Tool(
    title: String, detail: String, on: Boolean, active: Boolean, onChange: (Boolean) -> Unit, reading: String? = null,
    steady: Boolean = false, slow: Boolean = false, waiting: String? = null, alwaysShow: Boolean = false,
) {
    SettingSwitchRow(title, detail, on, onChange)
    val status = when {
        !on -> "Off"
        waiting != null -> waiting
        !active -> "Waiting for Hi-Fi. Saved; it works when the audiophile engine runs."
        steady -> "On · acts on every bass note; no level readout"
        reading != null -> "On · $reading"
        alwaysShow -> "On"
        else -> if (slow) "On · idle (updates every 2 s)" else "On · idle now"
    }
    Text(status, style = MaterialTheme.typography.bodySmall, color = if (on && active && waiting == null) Svan.Gold else Svan.TextFaint,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
}

@Composable
private fun ReadOnlyTool(title: String, detail: String, status: String) {
    Text(title, style = MaterialTheme.typography.bodyLarge)
    Text(detail, style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
    Text(status, style = MaterialTheme.typography.bodySmall, color = Svan.Gold, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
}

private fun String.fmt(vararg args: Any): String = String.format(Locale.ROOT, this, *args)
private fun lift(db: Double): String? = if (db >= 0.05) "lift %.1f dB now".fmt(db) else null
private fun cut(db: Double): String? = if (db <= -0.05) "%.1f dB now".fmt(db) else null
