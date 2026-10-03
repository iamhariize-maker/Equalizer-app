package app.svan.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun SvanCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Svan.Surface)
            .border(1.dp, Svan.Grid, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) { content() }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = Svan.TextFaint,
        modifier = modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp))
}

/** Selectable pill, used for filter types, band chips and segmented choices. */
@Composable
fun Pill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Svan.Saffron,
    enabled: Boolean = true,
) {
    val bg by animateColorAsState(if (selected) accent.copy(alpha = 0.16f) else Svan.SurfaceHigh, label = "pillBg")
    val border by animateColorAsState(if (selected) accent else Svan.Outline, label = "pillBorder")
    val fg by animateColorAsState(if (selected) accent else if (enabled) Svan.Text else Svan.TextFaint, label = "pillFg")
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = fg) }
}

/**
 * Labelled slider whose value text can be tapped for exact numeric entry.
 * [toSlider]/[fromSlider] map between the real value and 0..1 (e.g. log scale).
 */
@Composable
fun ValueSlider(
    label: String,
    value: Double,
    display: (Double) -> String,
    toSlider: (Double) -> Float,
    fromSlider: (Float) -> Double,
    onChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = Svan.Saffron,
    entryRange: ClosedFloatingPointRange<Double>? = null,
    entryUnit: String = "",
) {
    var editing by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = if (enabled) Svan.TextMuted else Svan.TextFaint)
            Spacer(Modifier.weight(1f))
            Text(
                display(value),
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) Svan.Text else Svan.TextFaint,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled && entryRange != null) { editing = true }
                    .background(if (enabled && entryRange != null) Svan.SurfaceHigh else Color.Transparent)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Slider(
            value = toSlider(value).coerceIn(0f, 1f),
            onValueChange = { onChange(fromSlider(it)) },
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = Svan.SurfaceHigher,
                disabledThumbColor = Svan.TextFaint,
                disabledActiveTrackColor = Svan.Outline,
                disabledInactiveTrackColor = Svan.SurfaceHigh,
            ),
        )
    }
    if (editing && entryRange != null) {
        NumberEntryDialog(label, value, entryRange, entryUnit, onDismiss = { editing = false }) {
            onChange(it)
            editing = false
        }
    }
}

@Composable
fun NumberEntryDialog(
    title: String,
    initial: Double,
    range: ClosedFloatingPointRange<Double>,
    unit: String,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
) {
    var text by remember { mutableStateOf("%.2f".format(initial).trimEnd('0').trimEnd('.')) }
    val parsed = text.replace(',', '.').toDoubleOrNull()
    val valid = parsed != null && parsed in range
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Svan.SurfaceHigh,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                suffix = { Text(unit) },
                isError = !valid,
                supportingText = { Text("%s – %s %s".format(fmt(range.start), fmt(range.endInclusive), unit)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        confirmButton = { TextButton(onClick = { parsed?.let(onConfirm) }, enabled = valid) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun fmt(v: Double) = "%.2f".format(v).trimEnd('0').trimEnd('.')

@Composable
fun SettingSwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) Svan.Text else Svan.TextFaint)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Svan.Black,
                checkedTrackColor = Svan.Saffron,
                uncheckedThumbColor = Svan.TextMuted,
                uncheckedTrackColor = Svan.SurfaceHigher,
                uncheckedBorderColor = Svan.Outline,
            ),
        )
    }
}

/** Neutron-style radio row: title, explanation, selection dot. */
@Composable
fun ChoiceRow(title: String, detail: String, selected: Boolean, onClick: () -> Unit, badge: String? = null) {
    val border by animateColorAsState(if (selected) Svan.Saffron else Svan.Grid, label = "choice")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Svan.Saffron.copy(alpha = 0.07f) else Svan.SurfaceHigh)
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(badge, style = MaterialTheme.typography.labelMedium, color = Svan.Black,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Svan.Saffron).padding(horizontal = 6.dp, vertical = 1.dp))
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.size22().clip(RoundedCornerShape(50)).border(2.dp, if (selected) Svan.Saffron else Svan.Outline, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size10().clip(RoundedCornerShape(50)).background(Svan.Saffron))
        }
    }
}

private fun Modifier.size22() = this.then(Modifier.width(22.dp).height(22.dp))
private fun Modifier.size10() = this.then(Modifier.width(10.dp).height(10.dp))

/**
 * Vertical gain fader for the graphic EQ. Drag to set, double-tap to reset to 0.
 */
@Composable
fun VerticalFader(
    value: Double,
    range: Double,
    onChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 28.dp,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(value)
    Canvas(
        modifier
            .width(width)
            .pointerInput(range) {
                detectTapGestures(onDoubleTap = {
                    onChange(0.0)
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                })
            }
            .pointerInput(range) {
                var lastNotch = current.toInt()
                detectVerticalDragGestures { change, _ ->
                    change.consume()
                    val pad = 10.dp.toPx()
                    val h = size.height - 2 * pad
                    val frac = ((change.position.y - pad) / h).coerceIn(0f, 1f)
                    val v = Math.round((range - frac * 2 * range) * 2) / 2.0 // 0.5 dB steps
                    if (v.toInt() != lastNotch) {
                        lastNotch = v.toInt()
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    onChange(v)
                }
            },
    ) {
        val pad = 10.dp.toPx()
        val h = size.height - 2 * pad
        val cx = size.width / 2
        val trackW = 4.dp.toPx()
        val zeroY = pad + h / 2
        val y = pad + ((range - value) / (2 * range) * h).toFloat()
        drawRoundRect(Svan.SurfaceHigher, Offset(cx - trackW / 2, pad), Size(trackW, h), CornerRadius(trackW))
        val accent = if (value >= 0) Svan.Saffron else Svan.Cyan
        val top = minOf(y, zeroY)
        drawRoundRect(accent.copy(alpha = if (enabled) 1f else 0.4f), Offset(cx - trackW / 2, top), Size(trackW, kotlin.math.abs(y - zeroY)), CornerRadius(trackW))
        drawLine(Svan.Outline, Offset(cx - 8.dp.toPx(), zeroY), Offset(cx + 8.dp.toPx(), zeroY), strokeWidth = 1.5f)
        drawCircle(Svan.Black, radius = 9.dp.toPx(), center = Offset(cx, y))
        drawCircle(accent.copy(alpha = if (enabled) 1f else 0.4f), radius = 7.dp.toPx(), center = Offset(cx, y))
    }
}
