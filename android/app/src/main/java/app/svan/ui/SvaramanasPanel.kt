package app.svan.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.svan.SvanRepository
import app.svan.svaramanas.Category
import app.svan.svaramanas.Feel
import app.svan.svaramanas.Heard
import app.svan.svaramanas.SmartPlan
import app.svan.svaramanas.SmartMode
import app.svan.svaramanas.Svaramanas
import kotlinx.coroutines.delay

/** The Svaramanas dialog: what you want to hear, what it heard, what it did. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun SvaramanasPanel(
    onDone: () -> Unit,
    bubbleOn: Boolean,
    onBubbleChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val request by Svaramanas.request.collectAsState()
    val plan by Svaramanas.plan.collectAsState()
    val heard by Svaramanas.heard.collectAsState()
    val listening by Svaramanas.listening.collectAsState()
    val eq by SvanRepository.eq.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) { if (message != null) { delay(4000); message = null } }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(Svan.Surface)
            .border(1.dp, Svan.Bronze.copy(alpha = 0.6f), RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SvaramanasMark(44.dp, listening = listening && request.enabled, resting = !request.enabled)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("SVARAMANAS", style = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 3.sp), color = Svan.Gold)
                Text("What kind of sound do you want?", style = MaterialTheme.typography.titleLarge, color = Svan.Text)
            }
            Switch(
                checked = request.enabled,
                onCheckedChange = { on -> Svaramanas.update { it.copy(enabled = on) } },
                colors = SwitchDefaults.colors(checkedThumbColor = Svan.OnGold, checkedTrackColor = Svan.Gold, uncheckedTrackColor = Svan.SurfaceHigher),
                modifier = Modifier.semantics { contentDescription = "${request.mode.plainName} on" },
            )
        }

        Spacer(Modifier.height(14.dp))
        SectionLabel("Choose your sound style")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeCard(SmartMode.GUIDED, request.mode == SmartMode.GUIDED, listening && request.enabled, modifier = Modifier.fillMaxWidth()) {
                Svaramanas.update { it.copy(mode = SmartMode.GUIDED, enabled = true) }
            }
            ModeCard(SmartMode.SVARESA, request.mode == SmartMode.SVARESA, listening && request.enabled, modifier = Modifier.fillMaxWidth()) {
                Svaramanas.update { it.copy(mode = SmartMode.SVARESA, enabled = true) }
            }
        }

        AnimatedVisibility(request.mode == SmartMode.GUIDED) {
            Column {
                Spacer(Modifier.height(14.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Feel.entries.forEach { f ->
                        FeelChip(f, selected = request.feel == f) { Svaramanas.update { it.copy(feel = f, enabled = true) } }
                    }
                }

                SectionLabel("Sounds to bring forward")
                Text(
                    "Pick up to ${Category.MAX}. If two compete for the same range, I keep your earlier choice.",
                    style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted,
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Category.entries.forEach { c ->
                        val index = request.picks.indexOf(c)
                        Pill(
                            text = if (index >= 0) "${index + 1} · ${c.title}" else c.title,
                            selected = index >= 0,
                            onClick = {
                                if (index >= 0) {
                                    Svaramanas.update { it.copy(picks = it.picks - c) }
                                } else if (request.picks.size >= Category.MAX) {
                                    message = "You can pick four. A shorter list keeps the changes focused."
                                } else {
                                    val trial = request.copy(picks = request.picks + c, enabled = true)
                                    val check = SmartPlan.compute(trial, null, false)
                                    if (check.rejected and c.bit != 0) {
                                        message = "${c.title} competes with ${Category.fromMask(check.conflictWith).joinToString { it.title.lowercase() }}. Remove one first."
                                    } else {
                                        Svaramanas.update { trial }
                                    }
                                }
                            },
                        )
                    }
                }
                AnimatedVisibility(message != null) {
                    Text(message ?: "", style = MaterialTheme.typography.bodySmall, color = Svan.Ember, modifier = Modifier.padding(top = 8.dp))
                }

                Spacer(Modifier.height(8.dp))
                ValueSlider(
                    label = "Change amount",
                    value = request.strength,
                    display = { s -> when { s < 0.75 -> "Gentle"; s < 1.25 -> "Natural"; else -> "Bold" } },
                    toSlider = { ((it - 0.5) / 1.0).toFloat() },
                    fromSlider = { (0.5 + it * 1.0) },
                    onChange = { v -> Svaramanas.update { it.copy(strength = v) } },
                    enabled = request.enabled,
                )
            }
        }

        SectionLabel("What I heard")
        HeardBlock(heard, listening)

        SectionLabel("What I did")
        Svaramanas.explain(plan, heard, request, listening).forEach { line ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Box(Modifier.padding(top = 7.dp).size(5.dp).clip(CircleShape).background(Svan.Gold))
                Spacer(Modifier.width(10.dp))
                Text(line, style = MaterialTheme.typography.bodyMedium, color = Svan.Text)
            }
        }

        Spacer(Modifier.height(12.dp))
        SettingSwitchRow(
            "Show the sound bubble",
            "Keep a small Svan bubble on screen in your player. Tap to open the sound controls; hold to compare with the original.",
            bubbleOn, onBubbleChange,
        )

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val comparing = eq.smartBypass
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (comparing) Svan.Ash.copy(alpha = 0.2f) else Svan.SurfaceHigh)
                    .border(1.dp, if (comparing) Svan.Ash else Svan.Outline, RoundedCornerShape(50))
                    .pointerInput(request.enabled) {
                        detectTapGestures(onPress = {
                            if (!request.enabled) return@detectTapGestures
                            Svaramanas.setBypass(true)
                            tryAwaitRelease()
                            Svaramanas.setBypass(false)
                        })
                    }
                    .padding(vertical = 12.dp)
                    .semantics { contentDescription = "Hold to hear the original" },
                contentAlignment = Alignment.Center,
            ) { Text(if (comparing) "Original…" else "Hold to compare", style = MaterialTheme.typography.labelLarge, color = if (request.enabled) Svan.Text else Svan.TextFaint) }
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(Svan.Gold)
                    .clickable(onClick = onDone)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Done", style = MaterialTheme.typography.labelLarge, color = Svan.OnGold) }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun ModeCard(mode: SmartMode, selected: Boolean, listening: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val stroke = if (selected) Svan.Gold else Svan.Outline
    Row(
        modifier
            .heightIn(min = 86.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) Svan.Gold.copy(alpha = 0.10f) else Svan.SurfaceHigh)
            .border(if (selected) 1.5.dp else 1.dp, stroke, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = "${mode.plainName}, ${mode.sanskritName}. ${mode.promise}" }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mode == SmartMode.GUIDED) SvaramanasMark(38.dp, listening = listening, resting = !selected)
        else SvaresaMark(38.dp, listening = listening, resting = !selected)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(mode.plainName, style = MaterialTheme.typography.titleSmall, color = if (selected) Svan.Gold else Svan.Text)
            Text(
                mode.sanskritName,
                style = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 13.sp),
                color = Svan.Molten,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            Text(mode.promise, style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
        }
    }
}

@Composable
private fun FeelChip(f: Feel, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Svan.Gold.copy(alpha = 0.16f) else Svan.SurfaceHigh)
            .border(1.dp, if (selected) Svan.Gold else Svan.Outline, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(f.title, style = MaterialTheme.typography.labelLarge, color = if (selected) Svan.Gold else Svan.Text)
        Text(f.line, style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
    }
}

@Composable
private fun HeardBlock(h: Heard?, listening: Boolean) {
    if (!listening) {
        Text("Live listening needs Hi-Fi and a player that allows audio capture. Your chosen sound settings still work without it.",
            style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
        return
    }
    if (h == null || !h.valid) {
        Text("Listening… (%.0f s of music so far)".format(h?.seconds ?: 0.0), style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
        return
    }
    val rows = listOf(
        "Loudness" to "%.0f LUFS".format(h.loudnessLufs),
        "Dynamics" to "%.0f dB peak-to-loudness".format(h.plrDb) + if (h.plrDb < 8) " · heavily limited" else "",
        "Ceiling" to if (h.lossy) "%.1f kHz · lossy".format(h.cutoffHz / 1000) else "full range",
        "Stereo" to when { h.monoLike -> "mono"; h.correlation > 0.8 -> "narrow"; else -> "wide" },
        "Balance" to "mud %+.1f · harsh %+.1f · air %+.1f dB".format(h.mudDb, h.harshDb, h.airDb),
    )
    rows.forEach { (k, v) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(k, style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted, modifier = Modifier.width(84.dp))
            Text(v, style = MaterialTheme.typography.bodySmall, color = Svan.Text)
        }
    }
}
