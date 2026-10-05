package app.svan.ui

import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Svaramanas mark: a disc of molten gold carrying स्व, with three small
 * "manas" nodes on its rim joined by a fine arc — sound (svara) and mind
 * (manas). Drawn on a plain android Canvas so the in-app bubble and the
 * floating overlay bubble share one implementation.
 */
object SvaraMark {
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }
    private val node = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val brain = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val spark = Paint(Paint.ANTI_ALIAS_FLAG)
    private val brainPath = android.graphics.Path()
    private val soundPath = android.graphics.Path()

    /** [pulse] 0..1 brightens the nodes (Svaramanas is listening); [resting] dims it to ash. */
    fun draw(c: android.graphics.Canvas, cx: Float, cy: Float, r: Float, pulse: Float = 0f, resting: Boolean = false) {
        val hi = (if (resting) Svan.Ash else Svan.Molten).toArgb()
        val mid = (if (resting) Svan.TextFaint else Svan.Gold).toArgb()
        val lo = (if (resting) Svan.SurfaceHigher else Svan.Bronze).toArgb()
        // Disc: lit from the upper left like polished brass.
        disc.shader = RadialGradient(cx - 0.35f * r, cy - 0.4f * r, 1.5f * r, intArrayOf(hi, mid, lo), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 0.86f, disc)
        // Engraved inner ring and a fine outer ring.
        ring.strokeWidth = r * 0.04f
        ring.color = Svan.OnGold.copy(alpha = 0.35f).toArgb()
        c.drawCircle(cx, cy, r * 0.74f, ring)
        ring.strokeWidth = r * 0.03f
        ring.color = mid
        c.drawCircle(cx, cy, r * 0.97f, ring)
        // स्व, dark on gold.
        glyph.color = Svan.OnGold.toArgb()
        glyph.textSize = r * 0.82f
        val fm = glyph.fontMetrics
        c.drawText("स्व", cx, cy - (fm.ascent + fm.descent) / 2f + r * 0.02f, glyph)
        // Manas: three nodes on the rim (upper right) joined by an arc.
        val angles = floatArrayOf(-75f, -45f, -15f)
        arc.strokeWidth = r * 0.035f
        arc.color = (if (resting) Svan.Ash else Svan.Molten).copy(alpha = 0.5f + 0.4f * pulse).toArgb()
        val rr = r * 0.97f
        c.drawArc(cx - rr, cy - rr, cx + rr, cy + rr, angles.first(), angles.last() - angles.first(), false, arc)
        angles.forEachIndexed { i, a ->
            val rad = Math.toRadians(a.toDouble())
            val x = cx + rr * cos(rad).toFloat()
            val y = cy + rr * sin(rad).toFloat()
            val glow = if (resting) 0f else pulse * (if (i == 1) 1f else 0.6f)
            node.color = (if (resting) Svan.Ash else Svan.Glow).copy(alpha = 0.75f + 0.25f * glow).toArgb()
            c.drawCircle(x, y, r * (0.09f + 0.03f * glow), node)
        }
    }

    /** Svaresa seal: a calm two-lobe "sonic brain" with a sound wave at its centre. */
    fun drawSvaresa(c: android.graphics.Canvas, cx: Float, cy: Float, r: Float, pulse: Float = 0f, resting: Boolean = false) {
        val hi = (if (resting) Svan.Ash else Svan.Molten).toArgb()
        val mid = (if (resting) Svan.TextFaint else Svan.Gold).toArgb()
        val lo = (if (resting) Svan.SurfaceHigher else Svan.Bronze).toArgb()
        disc.shader = RadialGradient(cx - 0.35f * r, cy - 0.4f * r, 1.5f * r, intArrayOf(hi, mid, lo), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 0.86f, disc)
        ring.strokeWidth = r * 0.04f
        ring.color = Svan.OnGold.copy(alpha = 0.35f).toArgb()
        c.drawCircle(cx, cy, r * 0.74f, ring)
        ring.strokeWidth = r * 0.03f
        ring.color = mid
        c.drawCircle(cx, cy, r * 0.97f, ring)

        brainPath.rewind()
        brainPath.apply {
            moveTo(cx, cy + r * 0.37f)
            cubicTo(cx - r * 0.10f, cy + r * 0.47f, cx - r * 0.25f, cy + r * 0.35f, cx - r * 0.22f, cy + r * 0.17f)
            cubicTo(cx - r * 0.43f, cy + r * 0.19f, cx - r * 0.48f, cy - r * 0.02f, cx - r * 0.36f, cy - r * 0.14f)
            cubicTo(cx - r * 0.44f, cy - r * 0.34f, cx - r * 0.19f, cy - r * 0.44f, cx - r * 0.07f, cy - r * 0.27f)
            cubicTo(cx - r * 0.03f, cy - r * 0.42f, cx + r * 0.03f, cy - r * 0.42f, cx + r * 0.07f, cy - r * 0.27f)
            cubicTo(cx + r * 0.19f, cy - r * 0.44f, cx + r * 0.44f, cy - r * 0.34f, cx + r * 0.36f, cy - r * 0.14f)
            cubicTo(cx + r * 0.48f, cy - r * 0.02f, cx + r * 0.43f, cy + r * 0.19f, cx + r * 0.22f, cy + r * 0.17f)
            cubicTo(cx + r * 0.25f, cy + r * 0.35f, cx + r * 0.10f, cy + r * 0.47f, cx, cy + r * 0.37f)
            close()
        }
        brain.color = Svan.OnGold.toArgb()
        brain.strokeWidth = r * 0.045f
        c.drawPath(brainPath, brain)

        soundPath.rewind()
        soundPath.apply {
            moveTo(cx - r * 0.31f, cy + r * 0.02f)
            lineTo(cx - r * 0.19f, cy + r * 0.02f)
            lineTo(cx - r * 0.10f, cy - r * 0.10f)
            lineTo(cx - r * 0.02f, cy + r * 0.15f)
            lineTo(cx + r * 0.07f, cy - r * 0.16f)
            lineTo(cx + r * 0.16f, cy + r * 0.02f)
            lineTo(cx + r * 0.31f, cy + r * 0.02f)
        }
        wave.color = (if (resting) Svan.Ash else Svan.Bronze).copy(alpha = 0.75f + 0.25f * pulse).toArgb()
        wave.strokeWidth = r * 0.055f
        c.drawPath(soundPath, wave)
        if (!resting) {
            spark.color = (if (pulse > 0.5f) Svan.Glow else Svan.Molten).toArgb()
            c.drawCircle(cx - r * 0.39f, cy - r * 0.39f, r * 0.055f, spark)
            c.drawCircle(cx + r * 0.39f, cy - r * 0.39f, r * 0.055f, spark)
        }
    }
}

/** Compose wrapper. Pulses gently while [listening]. */
@Composable
fun SvaramanasMark(size: Dp, listening: Boolean, resting: Boolean, modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "manas").animateFloat(
        initialValue = 0f, targetValue = if (listening) 1f else 0f,
        animationSpec = infiniteRepeatable(tween(1369, easing = LinearEasing), RepeatMode.Reverse), label = "pulse",
    )
    Canvas(modifier.size(size)) {
        drawIntoCanvas { SvaraMark.draw(it.nativeCanvas, center.x, center.y, this.size.minDimension / 2f, pulse, resting) }
    }
}

/** Svaresa's automatic-master mark. The wave brightens only while it can listen. */
@Composable
fun SvaresaMark(size: Dp, listening: Boolean, resting: Boolean, modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "svaresa").animateFloat(
        initialValue = 0f, targetValue = if (listening) 1f else 0f,
        animationSpec = infiniteRepeatable(tween(1618, easing = LinearEasing), RepeatMode.Reverse), label = "pulse",
    )
    Canvas(modifier.size(size)) {
        drawIntoCanvas { SvaraMark.drawSvaresa(it.nativeCanvas, center.x, center.y, this.size.minDimension / 2f, pulse, resting) }
    }
}
