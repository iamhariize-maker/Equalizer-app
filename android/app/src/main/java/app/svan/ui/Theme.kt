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

/** Svan palette: AMOLED black, deep indigo surfaces, saffron accent. */
object Svan {
    val Black = Color(0xFF000000)
    val Surface = Color(0xFF0D0E14)
    val SurfaceHigh = Color(0xFF151722)
    val SurfaceHigher = Color(0xFF1D2030)
    val Outline = Color(0xFF272A3B)
    val Grid = Color(0xFF1B1E2B)
    val Text = Color(0xFFECEDF3)
    val TextMuted = Color(0xFF8B8FA3)
    val TextFaint = Color(0xFF5A5E72)
    val Saffron = Color(0xFFFFA63D)
    val Rose = Color(0xFFFF5D7E)
    val Cyan = Color(0xFF4FD3EA)
    val Violet = Color(0xFFA48BFF)
    val Green = Color(0xFF5BE3A1)

    val AccentBrush = Brush.horizontalGradient(listOf(Saffron, Rose))

    fun typeColor(t: FilterType): Color = when (t) {
        FilterType.PEAK -> Saffron
        FilterType.LOW_SHELF, FilterType.HIGH_SHELF -> Cyan
        FilterType.LOW_PASS, FilterType.HIGH_PASS -> Violet
        FilterType.BAND_PASS -> Green
        FilterType.NOTCH -> Rose
        FilterType.ALL_PASS -> TextMuted
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
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 26.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
)

@Composable
fun SvanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Svan.Saffron,
            onPrimary = Color(0xFF1E1200),
            secondary = Svan.Rose,
            tertiary = Svan.Cyan,
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
