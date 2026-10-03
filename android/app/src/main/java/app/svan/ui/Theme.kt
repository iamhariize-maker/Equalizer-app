package app.svan.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.svan.NativeEngine.FilterType

/**
 * Svan palette — the warm gold of molten metal, polished brass and the embers
 * of a yajna fire, on charred, earthy darks. Gold is the only hue; everything
 * else is warm neutral. Ember is reserved for warnings.
 */
object Svan {
    // Grounds: charred wood and dark bronze, never pure black.
    val Black = Color(0xFF0C0A08)
    val Surface = Color(0xFF15120E)
    val SurfaceHigh = Color(0xFF1C1813)
    val SurfaceHigher = Color(0xFF262019)
    val Outline = Color(0xFF3A3024)
    val Grid = Color(0xFF221D16)

    // Parchment text.
    val Text = Color(0xFFEEE6D8)
    val TextMuted = Color(0xFFA79D8D)
    val TextFaint = Color(0xFF6E6558)

    // The gold spectrum.
    val Gold = Color(0xFFD9A84E)    // polished brass — the accent
    val Molten = Color(0xFFF3D58F)  // molten highlight
    val Bronze = Color(0xFF9A6B2A)  // depth
    val Ember = Color(0xFFB4552E)   // glowing coal: warnings and clipping only
    val Ash = Color(0xFFA39A8B)     // cool counterweight: cuts, sustain, "negative" sides
    val Glow = Color(0xFFE9C46A)    // live indicators

    val OnGold = Color(0xFF1A1206)

    /** Molten-metal gradient: bronze depth to a bright gold crest. */
    val AccentBrush = Brush.horizontalGradient(listOf(Bronze, Gold, Molten, Gold))
    val CurveBrush = Brush.horizontalGradient(listOf(Bronze, Gold, Molten))

    // Filter types stay within the gold family, told apart by value, not hue.
    fun typeColor(t: FilterType): Color = when (t) {
        FilterType.PEAK -> Gold
        FilterType.LOW_SHELF, FilterType.HIGH_SHELF -> Molten
        FilterType.LOW_PASS, FilterType.HIGH_PASS -> Bronze
        FilterType.BAND_PASS -> Glow
        FilterType.NOTCH -> Ember
        FilterType.ALL_PASS -> Ash
    }

    fun typeShort(t: FilterType): String = when (t) {
        FilterType.PEAK -> "Bell"
        FilterType.LOW_SHELF -> "Low shelf"
        FilterType.HIGH_SHELF -> "High shelf"
        FilterType.LOW_PASS -> "Low pass"
        FilterType.HIGH_PASS -> "High pass"
        FilterType.BAND_PASS -> "Band pass"
        FilterType.NOTCH -> "Notch"
        FilterType.ALL_PASS -> "All pass"
    }

    fun typeCode(t: FilterType): String = when (t) {
        FilterType.PEAK -> "PK"
        FilterType.LOW_SHELF -> "LS"
        FilterType.HIGH_SHELF -> "HS"
        FilterType.LOW_PASS -> "LP"
        FilterType.HIGH_PASS -> "HP"
        FilterType.BAND_PASS -> "BP"
        FilterType.NOTCH -> "NO"
        FilterType.ALL_PASS -> "AP"
    }
}

fun formatHz(f: Double): String = when {
    f >= 10000 -> "%.1f kHz".format(f / 1000).replace(".0 kHz", " kHz")
    f >= 1000 -> "%.2f kHz".format(f / 1000).trimEnd('0').trimEnd('.').let { if (it.endsWith("kHz")) it else "$it kHz" }
    else -> "%.0f Hz".format(f)
}

fun formatDb(g: Double): String = "%+.1f dB".format(g).replace("+0.0", "0.0").replace("-0.0", "0.0")

private val SvanType = Typography(
    // Serif display type for titles and the wordmark: the ancient-world gravitas.
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, letterSpacing = 0.2.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 21.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 1.2.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
)

@Composable
fun SvanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Svan.Gold,
            onPrimary = Svan.OnGold,
            secondary = Svan.Molten,
            tertiary = Svan.Bronze,
            error = Svan.Ember,
            background = Svan.Black,
            onBackground = Svan.Text,
            surface = Svan.Surface,
            onSurface = Svan.Text,
            surfaceVariant = Svan.SurfaceHigh,
            onSurfaceVariant = Svan.TextMuted,
            surfaceContainer = Svan.Surface,
            surfaceContainerHigh = Svan.SurfaceHigh,
            surfaceContainerHighest = Svan.SurfaceHigher,
            outline = Svan.Outline,
            outlineVariant = Svan.Grid,
        ),
        typography = SvanType,
        content = content,
    )
}
