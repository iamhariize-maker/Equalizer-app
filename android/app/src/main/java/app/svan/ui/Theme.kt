package app.svan.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.svan.R
import app.svan.NativeEngine.FilterType

/**
 * Svan palette — the warm gold of molten metal, polished brass and the embers
 * of a yajna fire, on charred, earthy darks. Gold leads; Ember is reserved for warnings.
 *
 * Jewel accents (owner decision, 8 October 2026). Each has one meaning and is used only for it,
 * like the colours of a miniature painting rather than a rainbow UI:
 * - Lapis and Indigo (complementary to gold): air, treble, the sky above the music, listening.
 * - Peacock (complementary): space, width, atmosphere.
 * - Lotus (a warm contrast): the voice and intimacy.
 * - Tulsi (a living contrast): your own learned sound and confirmations.
 */
object Svan {
    private val palette get() = SvanAppearance.current.palette
    val Black get() = palette.background
    val Surface get() = palette.surface
    val SurfaceHigh get() = palette.raised
    val SurfaceHigher get() = palette.tonal
    val Outline get() = palette.border
    val Grid get() = palette.grid
    val Text get() = palette.text
    val TextMuted get() = palette.secondary
    val TextFaint get() = palette.muted
    val Gold get() = palette.accent
    val Molten get() = palette.highlight
    val Bronze get() = palette.depth
    val Ember get() = palette.warning
    val Ash get() = palette.neutral
    val Glow get() = palette.live
    val Lapis get() = palette.air
    val Indigo get() = palette.sky
    val Peacock get() = palette.space
    val Lotus get() = palette.voice
    val Tulsi get() = palette.learned
    val OnGold get() = palette.onAccent
    val AccentBrush get() = palette.accentBrush
    val CurveBrush get() = palette.curveBrush
    val SpectrumFill get() = palette.spectrumFill
    val DisplayFont get() = when (SvanAppearance.current) {
        AppearanceTheme.ORIGINAL -> FontFamily.Serif
        AppearanceTheme.SVAN_LAB -> FontFamily.SansSerif
        else -> AtelierDisplay
    }

    // Filter types stay within the gold family, told apart by value, not hue.
    fun typeColor(t: FilterType): Color = when (t) {
        FilterType.PEAK -> Gold
        FilterType.LOW_SHELF, FilterType.HIGH_SHELF -> Molten
        FilterType.LOW_PASS, FilterType.HIGH_PASS -> Bronze
        FilterType.BAND_PASS -> Glow
        FilterType.NOTCH -> Ash
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

private val OriginalType = Typography(
    // Serif display type for titles and the wordmark: the ancient-world gravitas.
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, letterSpacing = 0.2.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 21.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 1.2.sp),
    // Tabular figures keep numbers aligned without the console look of a monospace face.
    labelSmall = TextStyle(fontSize = 11.sp, letterSpacing = 0.2.sp, fontFeatureSettings = "tnum"),
)

private val AtelierDisplay = FontFamily(Font(R.font.cormorant_semibold, FontWeight.SemiBold))
private val AtelierUi = FontFamily(
    Font(R.font.noto_regular, FontWeight.Normal),
    Font(R.font.noto_medium, FontWeight.Medium),
    Font(R.font.noto_semibold, FontWeight.SemiBold),
)
private val AtelierType = Typography(
    displayLarge = TextStyle(fontFamily = AtelierDisplay, fontWeight = FontWeight.SemiBold, fontSize = 48.sp, lineHeight = 54.sp),
    displayMedium = TextStyle(fontFamily = AtelierDisplay, fontWeight = FontWeight.SemiBold, fontSize = 44.sp, lineHeight = 50.sp),
    displaySmall = TextStyle(fontFamily = AtelierDisplay, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 46.sp),
    headlineLarge = TextStyle(fontFamily = AtelierDisplay, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 46.sp),
    headlineMedium = TextStyle(fontFamily = AtelierDisplay, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 42.sp),
    headlineSmall = TextStyle(fontFamily = AtelierDisplay, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp),
    titleLarge = TextStyle(fontFamily = AtelierDisplay, fontWeight = FontWeight.SemiBold, fontSize = 23.sp, lineHeight = 29.sp),
    titleMedium = TextStyle(fontFamily = AtelierUi, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = AtelierUi, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = AtelierUi, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = AtelierUi, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = AtelierUi, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = AtelierUi, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = AtelierUi, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 1.2.sp),
    labelSmall = TextStyle(fontFamily = AtelierUi, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 17.sp, fontFeatureSettings = "tnum"),
)

@Composable
fun SvanTheme(content: @Composable () -> Unit) {
    val theme = SvanAppearance.current
    val base = if (theme.isLight) lightColorScheme() else darkColorScheme()
    MaterialTheme(
        colorScheme = base.copy(
            primary = Svan.Gold,
            onPrimary = Svan.OnGold,
            primaryContainer = Svan.SurfaceHigher,
            onPrimaryContainer = Svan.Text,
            secondary = Svan.Molten,
            onSecondary = Svan.OnGold,
            secondaryContainer = Svan.SurfaceHigher,
            onSecondaryContainer = Svan.Text,
            tertiary = Svan.Bronze,
            onTertiary = Svan.OnGold,
            tertiaryContainer = Svan.SurfaceHigher,
            onTertiaryContainer = Svan.Text,
            error = Svan.Ember,
            onError = if (theme.isLight) Svan.OnGold else Svan.Black,
            errorContainer = Svan.SurfaceHigh,
            onErrorContainer = Svan.Ember,
            background = Svan.Black,
            onBackground = Svan.Text,
            surface = Svan.Surface,
            onSurface = Svan.Text,
            surfaceVariant = Svan.SurfaceHigh,
            onSurfaceVariant = Svan.TextMuted,
            surfaceContainer = Svan.Surface,
            surfaceContainerLow = Svan.Surface,
            surfaceContainerLowest = Svan.Black,
            surfaceContainerHigh = Svan.SurfaceHigh,
            surfaceContainerHighest = Svan.SurfaceHigher,
            surfaceBright = Svan.SurfaceHigher,
            surfaceDim = Svan.Black,
            inverseSurface = Svan.Text,
            inverseOnSurface = Svan.Black,
            inversePrimary = Svan.OnGold,
            outline = Svan.Outline,
            outlineVariant = Svan.Grid,
        ),
        typography = when (theme) {
            AppearanceTheme.ORIGINAL -> OriginalType
            AppearanceTheme.SVAN_LAB -> OriginalType.copy(
                headlineMedium = OriginalType.headlineMedium.copy(fontFamily = FontFamily.SansSerif),
                titleLarge = OriginalType.titleLarge.copy(fontFamily = FontFamily.SansSerif),
            )
            else -> AtelierType
        },
        content = content,
    )
}
