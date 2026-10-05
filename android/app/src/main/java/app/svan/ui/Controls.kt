package app.svan.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.Stroke
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
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = Svan.TextMuted,
        modifier = modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp))
}

/** Selectable pill, used for filter types, band chips and segmented choices. */
@Composable
fun Pill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Svan.Gold,
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
    accent: Color = Svan.Gold,
    entryRange: ClosedFloatingPointRange<Double>? = null,
    entryUnit: String = "",
    step: Double = 0.1,
) {
    var editing by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(toSlider(value).toDouble().coerceIn(0.0, 1.0))
    val change by rememberUpdatedState(onChange)
    val decode by rememberUpdatedState(fromSlider)
    val encode by rememberUpdatedState(toSlider)
    val actual by rememberUpdatedState(value)
    val realRange = entryRange ?: fromSlider(0f)..fromSlider(1f)
    val interaction = rememberPrecisionInteraction(current) { change(decode(it.toFloat())) }
    val shown = if (interaction.dragging) decode(interaction.preview.toFloat()) else value
    val fraction by animateFloatAsState(encode(shown).coerceIn(0f, 1f), spring(dampingRatio = 1f, stiffness = 1800f), label = "sliderThumb")
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = if (enabled) Svan.TextMuted else Svan.TextFaint)
            Spacer(Modifier.weight(1f))
            Text(
                display(shown),
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) Svan.Text else Svan.TextFaint,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled && entryRange != null) { editing = true }
                    .background(if (enabled && entryRange != null) Svan.SurfaceHigh else Color.Transparent)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Canvas(Modifier.fillMaxWidth().height(56.dp)
            .semantics {
                contentDescription = label
                stateDescription = display(value)
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(),realRange.start.toFloat()..realRange.endInclusive.toFloat(),
                    ((realRange.endInclusive-realRange.start)/step).toInt()-1)
                if (!enabled) disabled()
                setProgress { if (enabled) { change(snap(it.toDouble(),realRange.start,realRange.endInclusive,step)); true } else false }
            }
            .pointerInput(enabled, step, realRange) {
                if (!enabled) return@pointerInput
                detectTapGestures(onTap = { position ->
                    // A tap nudges one small tick; touching away from the thumb never jumps.
                    val thumb = 14.dp.toPx() + current.toFloat() * (size.width - 28.dp.toPx())
                    if (kotlin.math.abs(position.x-thumb) > 12.dp.toPx())
                        change(snap(actual+if(position.x > thumb) step else -step,realRange.start,realRange.endInclusive,step))
                })
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var acc = DetentAccumulator(current,0.0,1.0,0.001)
                detectHorizontalDragGestures(
                    onDragStart = { interaction.begin(); acc = DetentAccumulator(current,0.0,1.0,0.001) },
                    onDragEnd = { interaction.finish() }, onDragCancel = { interaction.finish() },
                ) { pointer, delta ->
                    pointer.consume()
                    // Pull away from the track while dragging to slow the adjustment further.
                    val fine = (kotlin.math.abs(pointer.position.y-size.height/2f) / 48.dp.toPx()).coerceIn(1f,6f)
                    interaction.move(acc.move((delta / (size.width * 1.5f * fine).coerceAtLeast(1f)).toDouble()))
                }
            }) {
            val pad = 14.dp.toPx(); val span = size.width-2*pad
            val y = center.y; val x = pad + fraction * span
            val alpha = if(enabled) 1f else 0.35f
            for(tick in 0..30) {
                val tx = pad+span*tick/30
                drawLine(Svan.Outline,Offset(tx,y+10.dp.toPx()),Offset(tx,y+(if(tick%5==0) 15 else 12).dp.toPx()),1.dp.toPx())
            }
            drawLine(Svan.SurfaceHigher,Offset(pad,y),Offset(size.width-pad,y),6.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(accent.copy(alpha=alpha),Offset(pad,y),Offset(x,y),6.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
            if(interaction.dragging) drawCircle(accent.copy(alpha=0.10f),23.dp.toPx(),Offset(x,y))
            drawCircle(Svan.SurfaceHigh,12.dp.toPx(),Offset(x,y))
            drawCircle(accent.copy(alpha=alpha),12.dp.toPx(),Offset(x,y),style=Stroke(2.dp.toPx()))
            drawCircle(accent.copy(alpha=alpha),4.dp.toPx(),Offset(x,y))
        }
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
                checkedTrackColor = Svan.Gold,
                uncheckedThumbColor = Svan.TextMuted,
                uncheckedTrackColor = Svan.SurfaceHigher,
                uncheckedBorderColor = Svan.Outline,
            ),
        )
    }
}

/** Processing choice row: title, explanation, selection dot. */
@Composable
fun ChoiceRow(title: String, detail: String, selected: Boolean, onClick: () -> Unit, badge: String? = null) {
    val border by animateColorAsState(if (selected) Svan.Gold else Svan.Grid, label = "choice")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Svan.Gold.copy(alpha = 0.07f) else Svan.SurfaceHigh)
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
                    Text(badge, style = MaterialTheme.typography.labelMedium, color = Svan.OnGold,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Svan.Gold).padding(horizontal = 6.dp, vertical = 1.dp))
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.size22().clip(RoundedCornerShape(50)).border(2.dp, if (selected) Svan.Gold else Svan.Outline, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size10().clip(RoundedCornerShape(50)).background(Svan.Gold))
        }
    }
}

private fun Modifier.size22() = this.then(Modifier.width(22.dp).height(22.dp))
private fun Modifier.size10() = this.then(Modifier.width(10.dp).height(10.dp))

/**
 * Relative graphic-EQ fader: 0.1 dB detents, no jump on grabbing, double-tap reset.
 */
@Composable
fun VerticalFader(
    value: Double,
    range: Double,
    onChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 56.dp,
    label: String = "EQ gain",
    onReset: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val current by rememberUpdatedState(value)
    val changeGain by rememberUpdatedState(onChange)
    val reset by rememberUpdatedState(onReset)
    val interaction = rememberPrecisionInteraction(value, onChange)
    val shown = if(interaction.dragging) interaction.preview else value
    val displayed by animateFloatAsState(shown.toFloat(), spring(dampingRatio=1f,stiffness=1800f),label="faderThumb")
    Canvas(
        modifier
            .width(width)
            .semantics {
                contentDescription = label
                stateDescription = formatDb(value)
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), -range.toFloat()..range.toFloat(), (range * 20).toInt() - 1)
                if (!enabled) disabled()
                setProgress { if (enabled) { changeGain(snap(it.toDouble(),-range,range,0.1)); true } else false }
            }
            .pointerInput(range, enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onTap = { pos ->
                    val pad = 14.dp.toPx()
                    val thumbY = pad+((range-current)/(2*range)*(size.height-2*pad)).toFloat()
                    if(kotlin.math.abs(pos.y-thumbY)>14.dp.toPx())
                        changeGain(snap(current+if(pos.y<thumbY) 0.1 else -0.1,-range,range,0.1))
                }, onDoubleTap = {
                    reset?.invoke() ?: changeGain(0.0)
                })
            }
            .pointerInput(range, enabled) {
                if (!enabled) return@pointerInput
                var acc = DetentAccumulator(current,-range,range,0.1)
                detectVerticalDragGestures(
                    onDragStart={ interaction.begin(); acc=DetentAccumulator(current,-range,range,0.1) },
                    onDragEnd={ interaction.finish() },onDragCancel={ interaction.finish() },
                ) { change, delta ->
                    change.consume()
                    val fine=(kotlin.math.abs(change.position.x-size.width/2f)/56.dp.toPx()).coerceIn(1f,6f)
                    interaction.move(acc.move(-delta/480.dp.toPx()/fine*(2*range)))
                }
            },
    ) {
        val pad = 14.dp.toPx()
        val h = size.height - 2 * pad
        val cx = size.width / 2
        val trackW = 4.dp.toPx()
        val zeroY = pad + h / 2
        val y = pad + ((range - displayed) / (2 * range) * h).toFloat()
        for(tick in 0..(range*4).toInt()) {
            val ty=pad+tick/(range*4).toFloat()*h
            val major=tick%6==0
            val length=(if(major) 8 else 4).dp.toPx()
            drawLine(Svan.Outline.copy(alpha=if(major) 0.8f else 0.4f),Offset(cx-length,ty),Offset(cx+length,ty),1.dp.toPx())
        }
        drawRoundRect(Svan.SurfaceHigher, Offset(cx - trackW / 2, pad), Size(trackW, h), CornerRadius(trackW))
        val accent = if (displayed >= 0) Svan.Gold else Svan.Ash
        val top = minOf(y, zeroY)
        drawRoundRect(accent.copy(alpha = if (enabled) 1f else 0.4f), Offset(cx - trackW / 2, top), Size(trackW, kotlin.math.abs(y - zeroY)), CornerRadius(trackW))
        drawLine(Svan.Outline, Offset(cx - 8.dp.toPx(), zeroY), Offset(cx + 8.dp.toPx(), zeroY), strokeWidth = 1.5f)
        if(interaction.dragging) drawCircle(accent.copy(alpha=0.1f),radius=23.dp.toPx(),center=Offset(cx,y))
        drawCircle(Svan.SurfaceHigh, radius = 12.dp.toPx(), center = Offset(cx, y))
        drawCircle(accent.copy(alpha = if (enabled) 1f else 0.4f),radius=12.dp.toPx(),center=Offset(cx,y),style=Stroke(2.dp.toPx()))
        drawLine(accent,Offset(cx-5.dp.toPx(),y),Offset(cx+5.dp.toPx(),y),2.dp.toPx())
    }
}
