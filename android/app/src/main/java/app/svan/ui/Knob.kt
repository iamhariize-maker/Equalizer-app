package app.svan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

private const val START_DEG = 135f
private const val SWEEP_DEG = 270f

/**
 * Turn the rim, or drag sideways for precision (320 dp for the full range).
 * Double-tap resets to [default]; tap the readout for numeric entry. Bipolar knobs
 * ([min] < 0 < [max]) fill from the 12 o'clock centre outwards. Vertical
 * gestures starting in the centre belong to the parent scroll container.
 */
@Composable
fun Knob(
    label: String,
    value: Double,
    min: Double,
    max: Double,
    display: (Double) -> String,
    onChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    default: Double = 0.0,
    step: Double = 0.01,
    size: Dp = 78.dp,
    accent: Color = Svan.Gold,
    negativeAccent: Color = Svan.Ash,
    enabled: Boolean = true,
    entryUnit: String = "",
) {
    val current by rememberUpdatedState(value)
    val changeValue by rememberUpdatedState(onChange)
    val resetValue by rememberUpdatedState(default)
    val interaction = rememberPrecisionInteraction(value, onChange)
    var editing by remember { mutableStateOf(false) }
    val shown = if (interaction.dragging) interaction.preview else value
    val bipolar = min < 0 && max > 0
    val frac by animateFloatAsState(((shown - min) / (max - min)).toFloat().coerceIn(0f, 1f),
        spring(dampingRatio = 1f, stiffness = 1800f), label = "dialPointer")
    val color = if (bipolar && shown < 0) negativeAccent else accent

    Column(modifier.width(size + 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(
                Modifier
                    .size(size)
                    .semantics {
                        contentDescription = label
                        stateDescription = display(value)
                        progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), min.toFloat()..max.toFloat(), ((max-min)/step).toInt()-1)
                        if (!enabled) disabled()
                        setProgress { requested ->
                            if (enabled) {
                                changeValue(snap(requested.toDouble(), min, max, step))
                                true
                            } else false
                        }
                    }
                    .pointerInput(min, max, step, enabled) {
                        if (!enabled) return@pointerInput
                        detectTapGestures(onDoubleTap = {
                            changeValue(resetValue.coerceIn(min, max))
                        })
                    }
                    .pointerInput(min, max, step, enabled) {
                        if (!enabled) return@pointerInput
                        val travel = 320.dp.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val centre = Offset(this.size.width / 2f, this.size.height / 2f)
                            val start = down.position - centre
                            var previous = down.position
                            var kind = DialDrag.WAIT
                            var acc = DetentAccumulator(current, min, max, step)
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (pointer.isConsumed || !pointer.pressed) break
                                    if (kind == DialDrag.WAIT) {
                                        val delta = pointer.position - down.position
                                        kind = dialDrag(start.x, start.y, delta.x, delta.y, minOf(this.size.width, this.size.height) / 2f, viewConfiguration.touchSlop)
                                        if (kind == DialDrag.WAIT) continue
                                        if (kind == DialDrag.SCROLL) break
                                        interaction.begin()
                                        acc = DetentAccumulator(current, min, max, step)
                                        // Crossing touch slop starts the gesture without advancing
                                        // several ticks at once. Subsequent travel moves the dial.
                                        previous = pointer.position
                                        pointer.consume()
                                        continue
                                    }
                                    val p = pointer.position - centre
                                    val old = previous - centre
                                    val delta = if (kind == DialDrag.ROTARY) {
                                        // Ignore the unstable angle close to the spindle.
                                        if (p.getDistance() < minOf(this.size.width, this.size.height) * 0.18f || old.getDistance() < minOf(this.size.width, this.size.height) * 0.18f) 0.0
                                        else angularDeltaDegrees(old.x, old.y, p.x, p.y) / 540.0 * (max - min)
                                    } else (pointer.position.x - previous.x) / travel * (max - min)
                                    interaction.move(acc.move(delta))
                                    previous = pointer.position
                                    pointer.consume()
                                }
                            } finally {
                                if (interaction.dragging) interaction.finish()
                            }
                        }
                    },
            ) {
                val stroke = 6.dp.toPx()
                val inset = stroke / 2 + 7.dp.toPx()
                // Engraved dial ticks, like a brass instrument scale.
                val outerR = this.size.minDimension / 2 - 1.dp.toPx()
                for (k in 0..27) {
                    val a = Math.toRadians((START_DEG + SWEEP_DEG * k / 27f).toDouble())
                    val major = k % 9 == 0
                    val r0 = outerR - (if (major) 4.5f else 2.5f) * density
                    drawLine(
                        if (major) Svan.Bronze else Svan.Outline,
                        Offset(center.x + r0 * cos(a).toFloat(), center.y + r0 * sin(a).toFloat()),
                        Offset(center.x + outerR * cos(a).toFloat(), center.y + outerR * sin(a).toFloat()),
                        strokeWidth = (if (major) 1.4f else 1f) * density,
                    )
                }
                val arcSize = Size(this.size.width - 2 * inset, this.size.height - 2 * inset)
                val topLeft = Offset(inset, inset)
                drawArc(Svan.SurfaceHigher, START_DEG, SWEEP_DEG, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                val (from, sweep) = if (bipolar) {
                    val zeroFrac = ((0 - min) / (max - min)).toFloat()
                    val a = START_DEG + SWEEP_DEG * zeroFrac
                    a to SWEEP_DEG * (frac - zeroFrac)
                } else START_DEG to SWEEP_DEG * frac
                val alpha = if (enabled) 1f else 0.35f
                if (kotlin.math.abs(sweep) > 0.5f) {
                    drawArc(
                        Brush.sweepGradient(listOf(color.copy(alpha = 0.6f * alpha), color.copy(alpha = alpha), color.copy(alpha = 0.6f * alpha))),
                        from, sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
                // Inner cap and pointer
                val r = this.size.minDimension / 2 - inset - stroke
                // Brushed-brass cap: a soft radial sheen, hairline rim.
                drawCircle(
                    Brush.radialGradient(listOf(Svan.SurfaceHigher, Svan.SurfaceHigh), center = Offset(center.x - r * 0.3f, center.y - r * 0.35f), radius = r * 1.4f),
                    radius = r, center = center,
                )
                drawCircle(Svan.Outline, radius = r, center = center, style = Stroke(1.dp.toPx()))
                val ang = Math.toRadians((START_DEG + SWEEP_DEG * frac).toDouble())
                val p1 = Offset(center.x + (r * 0.45f) * cos(ang).toFloat(), center.y + (r * 0.45f) * sin(ang).toFloat())
                val p2 = Offset(center.x + (r * 0.85f) * cos(ang).toFloat(), center.y + (r * 0.85f) * sin(ang).toFloat())
                drawLine(color.copy(alpha = alpha), p1, p2, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (enabled) Svan.Text else Svan.TextFaint, textAlign = TextAlign.Center)
        Text(display(shown), style = MaterialTheme.typography.labelSmall, color = if (enabled) color else Svan.TextFaint,
            textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.clickable(enabled = enabled) { editing = true })
    }
    if (editing) {
        val percent = min >= -1.0 && max <= 1.0
        val scale = if (percent) 100.0 else 1.0
        NumberEntryDialog(label, value * scale, min * scale..max * scale, if (percent) "%" else entryUnit,
            onDismiss = { editing = false }) { changeValue(it / scale); editing = false }
    }
}
