package app.svan.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import app.svan.svaramanas.Feel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/*
 * Svan's illustrations are drawn in code, never bitmaps, so they stay sharp at any density and can
 * respond to the sound. Each one means something: the landscape is the EQ curve seen as earth and sky,
 * the lotus is Svaramanas, the style glyphs say what each sound style does.
 */

private fun polar(c: Offset, r: Float, a: Double) = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())

/**
 * The EQ curve as a night landscape, drawn behind the Sound screen's response graph. The near hills rise
 * and fall with the bass end of the curve, a golden river runs through the mids, and the lapis sky with
 * slowly breathing stars holds the air. Low contrast by design: the graph on top stays the subject.
 */
@Composable
fun SoundLandscape(curve: DoubleArray, modifier: Modifier = Modifier, live: Boolean = true) {
    val stars = remember { starField(46) }
    val breath = rememberInfiniteTransition(label = "stars")
    val phase by breath.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "phase")
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        // Sky: night indigo at the top, warming to the card's surface at the horizon.
        drawRect(Brush.verticalGradient(0f to Svan.Indigo, 0.58f to Color(0xFF1E1C24), 1f to Svan.Surface))
        // Stars, breathing at their own pace; a few carry the cool lapis.
        stars.forEach { s ->
            val twinkle = if (live) 0.5f + 0.5f * sin(phase * s.speed + s.offset) else 0.6f
            val a = (0.18f + 0.5f * twinkle) * s.bright
            drawCircle((if (s.cool) Svan.Lapis else Svan.Molten).copy(alpha = a), s.r * density, Offset(s.x * w, s.y * h * 0.55f))
        }
        // Crescent moon with a soft halo.
        val mc = Offset(w * 0.9f, h * 0.2f)
        val mr = h * 0.085f
        drawCircle(Svan.Molten.copy(alpha = 0.05f), mr * 2.6f, mc)
        val crescent = Path().apply {
            op(Path().apply { addOval(Rect(mc, mr)) },
                Path().apply { addOval(Rect(Offset(mc.x + mr * 0.42f, mc.y - mr * 0.22f), mr * 0.9f)) },
                PathOperation.Difference)
        }
        drawPath(crescent, Svan.Molten.copy(alpha = 0.5f))
        // Mist over the far hills: atmosphere, in peacock.
        drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.5f to Svan.Peacock.copy(alpha = 0.07f), 1f to Color.Transparent),
            topLeft = Offset(0f, h * 0.5f), size = androidx.compose.ui.geometry.Size(w, h * 0.22f))
        // Three ridges; the nearest one follows the bass end of the curve.
        ridge(w, h, base = 0.64f, amp = 0.05f, freq = 2.3f, seed = 0.8f, color = Svan.Bronze.copy(alpha = 0.16f))
        ridge(w, h, base = 0.74f, amp = 0.06f, freq = 3.1f, seed = 2.1f, color = Svan.Bronze.copy(alpha = 0.24f))
        ridge(w, h, base = 0.86f, amp = 0.04f, freq = 1.7f, seed = 4.0f, color = Color(0xFF120F0B).copy(alpha = 0.85f), curve = curve)
        // The river of gold through the mids.
        val river = Path()
        val steps = 48
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val x = w * (0.18f + 0.64f * t)
            val y = h * (0.905f + 0.018f * sin(t * 9f + 0.6f) - 0.012f * t)
            if (i == 0) river.moveTo(x, y) else river.lineTo(x, y)
        }
        drawPath(river, Brush.horizontalGradient(0f to Color.Transparent, 0.3f to Svan.Gold.copy(alpha = 0.32f),
            0.7f to Svan.Molten.copy(alpha = 0.28f), 1f to Color.Transparent), style = Stroke(1.6f * density, cap = StrokeCap.Round))
    }
}

private fun DrawScope.ridge(w: Float, h: Float, base: Float, amp: Float, freq: Float, seed: Float, color: Color, curve: DoubleArray? = null) {
    val p = Path()
    val steps = 64
    p.moveTo(0f, h)
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        var y = base - amp * (0.55f * sin(t * freq * PI.toFloat() + seed) + 0.45f * sin(t * freq * 2.7f + seed * 1.9f))
        if (curve != null && curve.isNotEmpty()) {
            // Bass lifts the near hills (left third of the spectrum), fading out by the low mids.
            val db = curve[(t * (curve.size - 1)).toInt().coerceIn(0, curve.size - 1)].coerceIn(-12.0, 12.0).toFloat()
            val weight = ((0.5f - t) / 0.25f).coerceIn(0f, 1f)
            y -= db * 0.011f * weight
        }
        p.lineTo(w * t, h * y)
    }
    p.lineTo(w, h)
    p.close()
    drawPath(p, color)
}

private data class Star(val x: Float, val y: Float, val r: Float, val bright: Float, val speed: Float, val offset: Float, val cool: Boolean)

private fun starField(n: Int): List<Star> {
    val rnd = Random(108) // fixed sky: the same constellation every time
    return List(n) {
        Star(rnd.nextFloat(), rnd.nextFloat(), 0.5f + rnd.nextFloat() * 1.1f, 0.4f + rnd.nextFloat() * 0.6f,
            0.6f + rnd.nextFloat() * 1.6f, rnd.nextFloat() * 6.28f, rnd.nextFloat() < 0.3f)
    }
}

/**
 * A sixteen-petal lotus mandala (two rings of petals, a bead border and a central bindu). It turns very
 * slowly while Svaramanas is listening. Draw it as a watermark: it sits behind content, never in the way.
 */
fun DrawScope.drawLotusMandala(c: Offset, radius: Float, turn: Float = 0f, tint: Color = Svan.Lotus, strength: Float = 1f) {
    rotate(turn, c) {
        for (i in 0 until 36) drawCircle(Svan.Gold.copy(alpha = 0.30f * strength), radius * 0.016f, polar(c, radius * 0.97f, i * 2 * PI / 36))
        drawCircle(Svan.Gold.copy(alpha = 0.22f * strength), radius * 0.90f, c, style = Stroke(radius * 0.008f))
        petalRing(c, 16, tip = radius * 0.86f, base = radius * 0.50f, width = 0.48, offset = 0.0,
            fill = tint.copy(alpha = 0.12f * strength), edge = Svan.Gold.copy(alpha = 0.42f * strength), stroke = radius * 0.010f)
        petalRing(c, 8, tip = radius * 0.62f, base = radius * 0.27f, width = 0.46, offset = PI / 8,
            fill = Svan.Molten.copy(alpha = 0.12f * strength), edge = Svan.Molten.copy(alpha = 0.5f * strength), stroke = radius * 0.010f)
        drawCircle(Svan.Bronze.copy(alpha = 0.55f * strength), radius * 0.25f, c, style = Stroke(radius * 0.012f))
        drawCircle(Svan.Gold.copy(alpha = 0.85f * strength), radius * 0.055f, c)
    }
}

private fun DrawScope.petalRing(c: Offset, n: Int, tip: Float, base: Float, width: Double, offset: Double, fill: Color, edge: Color, stroke: Float) {
    val step = 2 * PI / n
    val half = width * step / 2
    for (i in 0 until n) {
        val a = offset + i * step
        val p = Path().apply {
            val l = polar(c, base, a - half)
            val r = polar(c, base, a + half)
            val t = polar(c, tip, a)
            moveTo(l.x, l.y)
            polar(c, tip * 0.9f, a - half * 0.75).let { k -> quadraticBezierTo(k.x, k.y, t.x, t.y) }
            polar(c, tip * 0.9f, a + half * 0.75).let { k -> quadraticBezierTo(k.x, k.y, r.x, r.y) }
            polar(c, base * 0.92f, a).let { k -> quadraticBezierTo(k.x, k.y, l.x, l.y) }
            close()
        }
        drawPath(p, fill)
        drawPath(p, edge, style = Stroke(stroke))
    }
}

/** The lotus as a composable; [turning] makes it rotate very slowly (one turn a minute). */
@Composable
fun LotusMandala(modifier: Modifier = Modifier, turning: Boolean = false, strength: Float = 1f) {
    val spin = rememberInfiniteTransition(label = "lotus")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(60_000, easing = LinearEasing)), label = "turn")
    Canvas(modifier) { drawLotusMandala(center, size.minDimension / 2f, if (turning) angle else 0f, strength = strength) }
}

/** The colour that carries each sound style's meaning. */
fun feelColor(f: Feel): Color = when (f) {
    Feel.BALANCED -> Svan.Ash
    Feel.WARM -> Svan.Gold
    Feel.BRIGHT -> Svan.Lapis
    Feel.PUNCHY -> Svan.Molten
    Feel.SPACIOUS -> Svan.Peacock
    Feel.INTIMATE -> Svan.Lotus
}

/**
 * A small hand-drawn sign for each sound style: a balance for Balanced, a sun for Warm, a star for Bright,
 * a struck drum for Punchy, opening arcs for Spacious and a lotus bud for Intimate.
 */
@Composable
fun FeelGlyph(f: Feel, modifier: Modifier = Modifier) {
    val color = feelColor(f)
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2f
        val line = Stroke(r * 0.13f, cap = StrokeCap.Round)
        when (f) {
            Feel.BALANCED -> {
                drawLine(color, Offset(c.x, c.y - r * 0.75f), Offset(c.x, c.y + r * 0.7f), r * 0.12f, StrokeCap.Round)
                drawLine(color, Offset(c.x - r * 0.8f, c.y - r * 0.45f), Offset(c.x + r * 0.8f, c.y - r * 0.45f), r * 0.12f, StrokeCap.Round)
                drawArc(color, 0f, 180f, false, Offset(c.x - r * 0.95f, c.y - r * 0.2f), androidx.compose.ui.geometry.Size(r * 0.6f, r * 0.45f), style = line)
                drawArc(color, 0f, 180f, false, Offset(c.x + r * 0.35f, c.y - r * 0.2f), androidx.compose.ui.geometry.Size(r * 0.6f, r * 0.45f), style = line)
                drawLine(color, Offset(c.x - r * 0.45f, c.y + r * 0.72f), Offset(c.x + r * 0.45f, c.y + r * 0.72f), r * 0.12f, StrokeCap.Round)
            }
            Feel.WARM -> {
                drawCircle(Brush.radialGradient(listOf(Svan.Molten, color, Svan.Bronze), c, r * 0.5f), r * 0.42f, c)
                for (i in 0 until 8) {
                    val a = i * PI / 4
                    drawLine(color, polar(c, r * 0.6f, a), polar(c, r * 0.92f, a), r * 0.12f, StrokeCap.Round)
                }
            }
            Feel.BRIGHT -> {
                val star = Path()
                for (i in 0 until 16) {
                    val p = polar(c, if (i % 2 == 0) r * 0.95f else r * 0.32f, i * PI / 8 - PI / 2)
                    if (i == 0) star.moveTo(p.x, p.y) else star.lineTo(p.x, p.y)
                }
                star.close()
                drawPath(star, color)
                drawCircle(Color.White.copy(alpha = 0.55f), r * 0.14f, c)
            }
            Feel.PUNCHY -> {
                drawCircle(color, r * 0.32f, c)
                drawArc(color, -50f, 100f, false, Offset(c.x - r * 0.62f, c.y - r * 0.62f), androidx.compose.ui.geometry.Size(r * 1.24f, r * 1.24f), style = line)
                drawArc(color, 130f, 100f, false, Offset(c.x - r * 0.62f, c.y - r * 0.62f), androidx.compose.ui.geometry.Size(r * 1.24f, r * 1.24f), style = line)
                drawArc(color.copy(alpha = 0.55f), -40f, 80f, false, Offset(c.x - r * 0.92f, c.y - r * 0.92f), androidx.compose.ui.geometry.Size(r * 1.84f, r * 1.84f), style = line)
                drawArc(color.copy(alpha = 0.55f), 140f, 80f, false, Offset(c.x - r * 0.92f, c.y - r * 0.92f), androidx.compose.ui.geometry.Size(r * 1.84f, r * 1.84f), style = line)
            }
            Feel.SPACIOUS -> {
                drawCircle(color, r * 0.16f, Offset(c.x, c.y + r * 0.55f))
                for (k in 1..3) {
                    val rr = r * (0.38f + 0.27f * k)
                    drawArc(color.copy(alpha = 1f - 0.22f * k), 200f, 140f, false,
                        Offset(c.x - rr, c.y + r * 0.55f - rr), androidx.compose.ui.geometry.Size(rr * 2, rr * 2), style = line)
                }
            }
            Feel.INTIMATE -> {
                val bud = Offset(c.x, c.y + r * 0.1f)
                for (a in listOf(-0.55, 0.0, 0.55)) {
                    val tip = polar(bud, r * (if (a == 0.0) 0.95f else 0.75f), -PI / 2 + a)
                    val p = Path().apply {
                        moveTo(bud.x, bud.y + r * 0.55f)
                        polar(bud, r * 0.7f, -PI / 2 + a - 0.5).let { k -> quadraticBezierTo(k.x, k.y, tip.x, tip.y) }
                        polar(bud, r * 0.7f, -PI / 2 + a + 0.5).let { k -> quadraticBezierTo(k.x, k.y, bud.x, bud.y + r * 0.55f) }
                        close()
                    }
                    drawPath(p, color.copy(alpha = if (a == 0.0) 0.95f else 0.7f))
                }
                drawLine(Svan.Tulsi, Offset(bud.x, bud.y + r * 0.55f), Offset(bud.x, c.y + r * 0.95f), r * 0.11f, StrokeCap.Round)
            }
        }
    }
}
