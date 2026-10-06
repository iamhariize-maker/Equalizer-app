package app.svan.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.round

/** Relative travel preserves sub-tick motion and never jumps to the touch position. */
internal class DetentAccumulator(initial: Double, private val min: Double, private val max: Double, val step: Double) {
    init { require(min.isFinite() && max.isFinite() && min < max && step.isFinite() && step > 0) }
    private var raw = initial.coerceIn(min, max)
    var value = raw
        private set

    fun move(delta: Double): Double {
        if (!delta.isFinite() || delta == 0.0) return value
        // Discard excess travel at the stops so reversing responds immediately.
        raw = (raw + delta).coerceIn(min, max)
        if (raw == min || raw == max || abs(raw - value) >= step * 0.55) {
            value = snap(raw, min, max, step)
        }
        return value
    }
}

internal fun snap(value: Double, min: Double, max: Double, step: Double): Double =
    (min + round((value.coerceIn(min, max) - min) / step) * step).coerceIn(min, max)

internal fun angularDeltaDegrees(fromX: Float, fromY: Float, toX: Float, toY: Float): Double {
    val delta = Math.toDegrees(atan2(toY.toDouble(), toX.toDouble()) - atan2(fromY.toDouble(), fromX.toDouble()))
    return (delta + 540.0) % 360.0 - 180.0
}

internal enum class DialDrag { WAIT, SIDEWAYS, ROTARY, SCROLL }
internal fun dialDrag(x: Float, y: Float, dx: Float, dy: Float, radius: Float, slop: Float): DialDrag {
    if (hypot(dx, dy) < slop) return DialDrag.WAIT
    val distance = hypot(x, y)
    if (distance > radius * 0.64f && abs(x * dy - y * dx) > abs(x * dx + y * dy) * 1.3f) return DialDrag.ROTARY
    return if (abs(dx) > abs(dy) * 1.2f) DialDrag.SIDEWAYS else DialDrag.SCROLL
}

/** Immediate local preview, at most one expensive audio edit per display frame. */
@Stable
internal class PrecisionInteraction(private val read: () -> Double, private val commit: (Double) -> Unit) {
    var dragging by mutableStateOf(false)
        private set
    var preview by mutableDoubleStateOf(0.0)
        private set
    var pending by mutableStateOf<Double?>(null)
        private set
    private var lastSent = Double.NaN

    fun begin() { preview = read(); lastSent = preview; dragging = true }
    fun move(value: Double) {
        if (!value.isFinite() || value == preview) return
        preview = value
        pending = value
    }
    fun dispatch() {
        val next = pending ?: return
        pending = null
        if (next != lastSent) { lastSent = next; commit(next) }
    }
    fun finish() { dispatch(); dragging = false }
}

@Composable
internal fun rememberPrecisionInteraction(value: Double, onChange: (Double) -> Unit): PrecisionInteraction {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val interaction = remember { PrecisionInteraction({ current }, { change(it) }) }
    LaunchedEffect(interaction) {
        while (true) {
            snapshotFlow { interaction.pending }.first { it != null }
            withFrameNanos { }
            interaction.dispatch()
        }
    }
    return interaction
}
