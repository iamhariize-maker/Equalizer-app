package app.svan.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.first
import app.svan.model.Band
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

private const val F_MIN = 20.0
private const val F_MAX = 20000.0
private val GRID_HZ = listOf(20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0)

/** Log-spaced frequencies the curve is evaluated at. */
val CURVE_FREQS: DoubleArray = DoubleArray(240) { i -> F_MIN * (F_MAX / F_MIN).pow(i / 239.0) }

/**
 * The EQ's frequency response with draggable band nodes.
 *  - drag a node: horizontal = frequency, vertical = gain (gain-type bands)
 *  - tap a node: select it; tap empty space: add a bell there
 *  - long-press a node: delete it
 * [curveDb] is the exact response from the native engine at [CURVE_FREQS].
 */
@Composable
fun ResponseGraph(
    bands: List<Band>,
    curveDb: DoubleArray,
    selected: Int,
    enabled: Boolean,
    editable: Boolean,
    allowAddDelete: Boolean = true,
    moveFrequency: Boolean = true,
    onSelect: (Int) -> Unit,
    onMove: (index: Int, freqHz: Double, gainDb: Double) -> Unit,
    onAdd: (freqHz: Double, gainDb: Double) -> Unit,
    onDelete: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableIntStateOf(-1) }
    var dragRange by remember { mutableFloatStateOf(12f) }
    val density = LocalDensity.current
    val nodeRadius = with(density) { 9f * this.density }
    val hitRadius = with(density) { 28f * this.density }

    // Vertical range follows the curve so big boosts never clip off the top.
    val peak = max(curveDb.maxOfOrNull { abs(it) } ?: 0.0, bands.filter { it.hasGain }.maxOfOrNull { abs(it.gainDb) } ?: 0.0)
    val targetRange = if(dragging >= 0) dragRange else when {
        peak > 18 -> 30f
        peak > 12 -> 18f
        else -> 12f
    }
    val range by animateFloatAsState(targetRange, spring(stiffness = Spring.StiffnessLow), label = "range")
    val dim by animateFloatAsState(if (enabled) 1f else 0.35f, label = "dim")

    // Reused every frame: allocating Paints while dragging causes GC stutter.
    val gridPaint = remember(density) {
        android.graphics.Paint().apply {
            color = android.graphics.Color.argb(255, 110, 101, 88) // Svan.TextFaint
            textSize = 10f * density.density
            isAntiAlias = true
        }
    }
    val labelPaint = remember {
        android.graphics.Paint().apply {
            isFakeBoldText = true
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    val currentBands by rememberUpdatedState(bands)
    val currentCurve by rememberUpdatedState(curveDb)
    val select by rememberUpdatedState(onSelect)
    val move by rememberUpdatedState(onMove)
    val add by rememberUpdatedState(onAdd)
    val delete by rememberUpdatedState(onDelete)
    var pendingMove by remember { mutableStateOf<Triple<Int,Double,Double>?>(null) }
    fun flushMove() {
        pendingMove?.let { move(it.first,it.second,it.third) }
        pendingMove=null
    }
    LaunchedEffect(Unit) {
        while(true) {
            snapshotFlow { pendingMove }.first { it != null }
            withFrameNanos { }
            flushMove()
        }
    }

    Canvas(
        modifier
            .pointerInput(editable, allowAddDelete, moveFrequency) {
                if (!editable) return@pointerInput
                detectTapGestures(
                    onTap = { pos ->
                        val hit = hitTest(pos, currentBands, currentCurve, size.width.toFloat(), size.height.toFloat(), range, hitRadius)
                        if (hit >= 0) select(hit) else if (allowAddDelete) {
                            add(xToFreq(pos.x, size.width.toFloat()), yToDb(pos.y, size.height.toFloat(), range).coerceIn(-24.0, 24.0))
                        }
                    },
                    onLongPress = { pos ->
                        val hit = hitTest(pos, currentBands, currentCurve, size.width.toFloat(), size.height.toFloat(), range, hitRadius)
                        if (hit >= 0 && allowAddDelete) {
                            delete(hit)
                        }
                    },
                )
            }
            .pointerInput(editable, allowAddDelete, moveFrequency) {
                if (!editable) return@pointerInput
                var gain = DetentAccumulator(0.0,-24.0,24.0,0.1)
                var logFrequency = DetentAccumulator(0.0,0.0,1.0,0.001)
                detectDragGestures(
                    onDragStart = { pos ->
                        dragging = hitTest(pos, currentBands, currentCurve, size.width.toFloat(), size.height.toFloat(), range, hitRadius)
                        if (dragging >= 0) {
                            val b=currentBands[dragging]
                            gain=DetentAccumulator(b.gainDb,-24.0,24.0,0.1)
                            logFrequency=DetentAccumulator(log10(b.freqHz/F_MIN)/3.0,0.0,1.0,0.001)
                            dragRange=range
                            select(dragging)
                        }
                    },
                    onDragEnd = { flushMove(); dragging = -1 },
                    onDragCancel = { flushMove(); dragging = -1 },
                    onDrag = { change, delta ->
                        val i = dragging
                        if (i < 0 || i >= currentBands.size) return@detectDragGestures
                        change.consume()
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val f = F_MIN*1000.0.pow(logFrequency.move((delta.x/(w*1.5f)).toDouble()))
                        val b = currentBands[i]
                        val g = if (b.hasGain) gain.move((-delta.y / (h*0.88f) * 2*dragRange).toDouble()) else b.gainDb
                        pendingMove=Triple(i,if(moveFrequency) f else b.freqHz,g)
                    },
                )
            },
    ) {
        drawGrid(range, gridPaint)
        drawCurve(curveDb, range, dim)
        // Nodes
        bands.forEachIndexed { i, b ->
            val x = freqToX(b.freqHz, size.width)
            val yDb = if (b.hasGain) b.gainDb else curveAt(curveDb, b.freqHz)
            val y = dbToY(yDb, size.height, range)
            val color = Svan.typeColor(b.type).copy(alpha = if (b.enabled) dim else 0.35f * dim)
            val isSel = i == selected
            if (isSel) {
                drawLine(color.copy(alpha = 0.25f * dim), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5f)
                drawCircle(color.copy(alpha = 0.18f), radius = nodeRadius * 2.4f, center = Offset(x, y))
            }
            drawCircle(Svan.Black, radius = nodeRadius + 2.5f, center = Offset(x, y))
            drawCircle(color, radius = if (isSel) nodeRadius * 1.15f else nodeRadius, center = Offset(x, y),
                style = if (b.enabled) androidx.compose.ui.graphics.drawscope.Fill else Stroke(3f))
            drawLabel("${i + 1}", Offset(x, y), if (b.enabled) Svan.Black else color, nodeRadius * 1.15f, labelPaint)
        }
    }
}

private fun DrawScope.drawGrid(range: Float, paint: android.graphics.Paint) {
    val w = size.width
    val h = size.height
    // dB lines every 6 dB (every 12 when zoomed out)
    val step = if (range > 18) 12 else 6
    var db = -range.toInt() / step * step
    while (db <= range) {
        val y = dbToY(db.toDouble(), h, range)
        drawLine(if (db == 0) Svan.Outline else Svan.Grid, Offset(0f, y), Offset(w, y), strokeWidth = if (db == 0) 2f else 1f)
        if (db != 0) drawContext.canvas.nativeCanvas.drawText("%+d".format(db), 6f * density, y - 3f * density, paint)
        db += step
    }
    GRID_HZ.forEach { f ->
        val x = freqToX(f, w)
        drawLine(Svan.Grid, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
        val label = if (f >= 1000) "${(f / 1000).toInt()}k" else f.toInt().toString()
        if (f > F_MIN && f < F_MAX) drawContext.canvas.nativeCanvas.drawText(label, x + 3f * density, h - 5f * density, paint)
    }
}

private fun DrawScope.drawCurve(curve: DoubleArray, range: Float, alpha: Float) {
    if (curve.isEmpty()) return
    val w = size.width
    val h = size.height
    val line = Path()
    curve.forEachIndexed { i, db ->
        val x = freqToX(CURVE_FREQS[i], w)
        val y = dbToY(db, h, range)
        if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
    }
    val zeroY = dbToY(0.0, h, range)
    val fill = Path().apply {
        addPath(line)
        lineTo(w, zeroY)
        lineTo(0f, zeroY)
        close()
    }
    // Warm metallic sheen between the curve and 0 dB.
    drawPath(fill, Brush.verticalGradient(
        0f to Svan.Molten.copy(alpha = 0.16f * alpha),
        0.5f to Svan.Bronze.copy(alpha = 0.04f * alpha),
        1f to Svan.Gold.copy(alpha = 0.12f * alpha),
    ))
    // glow + crisp line
    drawPath(line, Svan.CurveBrush, alpha = 0.22f * alpha,
        style = Stroke(width = 10f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(line, Svan.CurveBrush, alpha = alpha,
        style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawLabel(text: String, center: Offset, color: Color, radius: Float, paint: android.graphics.Paint) {
    paint.color = android.graphics.Color.argb(
        (color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt(),
    )
    paint.textSize = radius * (if (text.length > 1) 0.95f else 1.1f)
    drawContext.canvas.nativeCanvas.drawText(text, center.x, center.y + paint.textSize * 0.36f, paint)
}

private fun hitTest(pos: Offset, bands: List<Band>, curve: DoubleArray, w: Float, h: Float, range: Float, radius: Float): Int {
    var best = -1
    var bestD = radius
    bands.forEachIndexed { i, b ->
        val x = freqToX(b.freqHz, w)
        val y = dbToY(if (b.hasGain) b.gainDb else curveAt(curve, b.freqHz), h, range)
        val d = hypot(pos.x - x, pos.y - y)
        if (d < bestD) {
            bestD = d
            best = i
        }
    }
    return best
}

private fun curveAt(curve: DoubleArray, f: Double): Double {
    if (curve.isEmpty()) return 0.0
    val pos = (log10(f / F_MIN) / log10(F_MAX / F_MIN) * (curve.size - 1)).coerceIn(0.0, (curve.size - 1).toDouble())
    return curve[pos.toInt()]
}

private fun freqToX(f: Double, w: Float): Float = (log10(f / F_MIN) / log10(F_MAX / F_MIN) * w).toFloat()
private fun xToFreq(x: Float, w: Float): Double = (F_MIN * (F_MAX / F_MIN).pow((x / w).toDouble())).coerceIn(F_MIN, F_MAX)
private fun dbToY(db: Double, h: Float, range: Float): Float {
    val pad = h * 0.06f
    return (pad + (range - db.toFloat()) / (2 * range) * (h - 2 * pad))
}
private fun yToDb(y: Float, h: Float, range: Float): Double {
    val pad = h * 0.06f
    return (range - (y - pad) / (h - 2 * pad) * 2 * range).toDouble()
}
