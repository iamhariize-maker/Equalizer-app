package app.svan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val START_DEG = 135f
private const val SWEEP_DEG = 270f

/**
 * Rotary dial. Drag up/right to increase, down/left to decrease (a full turn is
 * ~220 dp of travel); double-tap resets to [default]. Bipolar knobs
 * ([min] < 0 < [max]) fill from the 12 o'clock centre outwards.
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
) {
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(value)
    val bipolar = min < 0 && max > 0
    val frac = ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f)
    val color = if (bipolar && value < 0) negativeAccent else accent

    Column(modifier.width(size + 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(
                Modifier
                    .size(size)
                    .pointerInput(min, max) {
                        detectTapGestures(onDoubleTap = {
                            onChange(default)
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        })
                    }
                    .pointerInput(min, max, enabled) {
                        if (!enabled) return@pointerInput
                        val travel = 220.dp.toPx()
                        var acc = current
                        detectDragGestures(
                            onDragStart = { acc = current },
                        ) { change, drag ->
                            change.consume()
                            val before = acc
                            acc = (acc + (drag.x - drag.y) / travel * (max - min)).coerceIn(min, max)
                            val stepped = (acc / step).roundToInt() * step
                            // Ticks at the ends and when crossing the centre of a bipolar knob.
                            val crossedCentre = bipolar && (before < 0) != (acc < 0)
                            if (crossedCentre || (acc == min && before != min) || (acc == max && before != max)) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            onChange(stepped.coerceIn(min, max))
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
        Text(display(value), style = MaterialTheme.typography.labelSmall, color = if (enabled) color else Svan.TextFaint, textAlign = TextAlign.Center, maxLines = 1)
    }
}
