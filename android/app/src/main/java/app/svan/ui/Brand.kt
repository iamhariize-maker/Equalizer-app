package app.svan.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

const val BRAND_FULL = "Svanam Shreshtham"
const val BRAND_TAGLINE = "Ultimate Sound"

/**
 * The maker's mark shown above every screen title: fine, letter-spaced gold
 * small caps with a hairline rule, like an engraving on brass.
 */
@Composable
fun BrandLine(modifier: Modifier = Modifier) {
    Row(modifier.semantics { contentDescription = "$BRAND_FULL: $BRAND_TAGLINE" }, verticalAlignment = Alignment.CenterVertically) {
        Text(
            BRAND_FULL.uppercase(),
            style = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 3.sp),
            color = Svan.Gold.copy(alpha = 0.85f),
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.width(28.dp).height(1.dp).background(Svan.Bronze.copy(alpha = 0.7f)))
    }
}

/** Screen title under the brand line. */
@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    Column {
        BrandLine()
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium.copy(brush = Svan.AccentBrush))
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
    }
}

/**
 * "EQ exten9ed": the first "d" of "extended" is a lowercase d flipped
 * vertically, so it reads as a 9 (a nod to 3-6-9). Screen readers say "EQ Extended".
 */
@Composable
fun Exten9edTitle(modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.headlineMedium.copy(brush = Svan.AccentBrush)
    Row(modifier.clearAndSetSemantics { contentDescription = "EQ Extended" }, verticalAlignment = Alignment.Bottom) {
        Text("EQ exten", style = style)
        // A vertically flipped "d": the bowl rises to the top and the stem descends — a 9.
        Text("d", style = style, modifier = Modifier.graphicsLayer { scaleY = -1f; translationY = -style.fontSize.toPx() * 0.12f })
        Text("ed", style = style)
    }
}

/**
 * Boot animation: "Svan" glows in, opens out into "Svanam Shreshtham", and
 * "ULTIMATE SOUND" settles beneath on a gold rule — then it all dissolves into
 * the app. Beats at 0.3 / 0.6 / 0.9 s and a 369 ms fade-out (3-6-9). Tap to skip.
 */
@Composable
fun BootAnimation(onDone: () -> Unit) {
    val shortIn = remember { Animatable(0f) }     // "Svan"
    val morph = remember { Animatable(0f) }       // Svan -> Svanam Shreshtham
    val tagline = remember { Animatable(0f) }     // rule + ULTIMATE SOUND
    val rings = remember { Animatable(0f) }       // yantra rings expand
    val out = remember { Animatable(1f) }         // whole overlay
    LaunchedEffect(Unit) {
        val r = async { rings.animateTo(1f, tween(2100, easing = LinearEasing)) }
        shortIn.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
        delay(300)
        morph.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
        tagline.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
        r.await()
        out.animateTo(0f, tween(369, easing = FastOutSlowInEasing))
        onDone()
    }
    Box(
        Modifier
            .fillMaxSize()
            .alpha(out.value)
            .background(Svan.Black)
            .clickable(remember { MutableInteractionSource() }, indication = null) { onDone() },
        contentAlignment = Alignment.Center,
    ) {
        // Faint yantra: concentric rings and nine radial marks, slowly opening.
        Canvas(Modifier.size(320.dp)) {
            val maxR = size.minDimension / 2
            for (k in 1..3) {
                val rr = maxR * (0.35f + 0.2f * k) * (0.85f + 0.15f * rings.value)
                drawCircle(Svan.Gold.copy(alpha = 0.10f * rings.value * (4 - k) / 3f), radius = rr, style = Stroke(1.dp.toPx()))
            }
            for (k in 0 until 9) {
                val a = Math.PI * 2 * k / 9 - Math.PI / 2
                val r0 = maxR * 0.9f
                val r1 = maxR * (0.9f + 0.08f * rings.value)
                drawLine(Svan.Gold.copy(alpha = 0.18f * rings.value),
                    androidx.compose.ui.geometry.Offset(center.x + (r0 * kotlin.math.cos(a)).toFloat(), center.y + (r0 * kotlin.math.sin(a)).toFloat()),
                    androidx.compose.ui.geometry.Offset(center.x + (r1 * kotlin.math.cos(a)).toFloat(), center.y + (r1 * kotlin.math.sin(a)).toFloat()),
                    strokeWidth = 1.dp.toPx())
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(contentAlignment = Alignment.Center) {
                val big = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 46.sp, brush = Svan.AccentBrush)
                Text("Svan", style = big, modifier = Modifier.alpha(shortIn.value * (1f - morph.value))
                    .graphicsLayer { val s = 0.92f + 0.08f * shortIn.value; scaleX = s; scaleY = s })
                Text(
                    BRAND_FULL,
                    style = big.copy(fontSize = 34.sp, letterSpacing = (4 * (1 - morph.value)).sp),
                    modifier = Modifier.alpha(morph.value).graphicsLayer { val s = 0.9f + 0.1f * morph.value; scaleX = s; scaleY = s },
                )
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.width((120 * tagline.value).dp).height(1.dp).background(Svan.Gold.copy(alpha = 0.7f)))
            Spacer(Modifier.height(12.dp))
            Text(
                BRAND_TAGLINE.uppercase(),
                style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 13.sp, letterSpacing = (6 + 2 * (1 - tagline.value)).sp),
                color = Svan.Molten,
                modifier = Modifier.alpha(tagline.value).padding(start = 6.dp),
            )
        }
    }
}
