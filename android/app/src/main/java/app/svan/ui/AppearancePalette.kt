package app.svan.ui

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor

/** Immutable, cached palettes: reading a color never builds artwork or touches DSP state. */
internal data class AppearancePalette(
    val background: Color, val surface: Color, val raised: Color, val tonal: Color,
    val border: Color, val grid: Color,
    val text: Color, val secondary: Color, val muted: Color,
    val accent: Color, val highlight: Color, val depth: Color, val warning: Color, val neutral: Color,
    val live: Color, val air: Color, val sky: Color, val space: Color, val voice: Color, val learned: Color,
    val onAccent: Color, val metallic: Boolean = false,
) {
    val accentBrush: Brush = if (metallic) Brush.horizontalGradient(listOf(depth, accent, highlight, accent)) else SolidColor(accent)
    val curveBrush: Brush = if (metallic) Brush.horizontalGradient(0f to depth, 0.42f to accent, 0.78f to highlight, 1f to air) else SolidColor(accent)
    val spectrumFill: Brush = if (metallic) Brush.horizontalGradient(0f to depth, 0.45f to accent, 0.8f to highlight, 1f to air) else SolidColor(accent)
}

private val original = AppearancePalette(
    background = Color(0xFF0C0A08), surface = Color(0xFF15120E), raised = Color(0xFF1C1813), tonal = Color(0xFF262019),
    border = Color(0xFF8C8171), grid = Color(0xFF383329),
    text = Color(0xFFEEE6D8), secondary = Color(0xFFCECAC1), muted = Color(0xFFACA59A),
    accent = Color(0xFFD9A84E), highlight = Color(0xFFF3D58F), depth = Color(0xFFB99457),
    warning = Color(0xFFE3A499), neutral = Color(0xFFAFA79A), live = Color(0xFFE9C46A),
    air = Color(0xFF89A8DF), sky = Color(0xFF1A2138), space = Color(0xFF6DBCB1),
    voice = Color(0xFFE5ACB5), learned = Color(0xFFA7C69F), onAccent = Color(0xFF1A1206), metallic = true,
)
private val raga = AppearancePalette(
    background = Color(0xFF10110F), surface = Color(0xFF171714), raised = Color(0xFF20211B), tonal = Color(0xFF26261F),
    border = Color(0xFF8C8171), grid = Color(0xFF39392E),
    text = Color(0xFFF1E6D5), secondary = Color(0xFFCECAC1), muted = Color(0xFFABA79E),
    accent = Color(0xFFD6B475), highlight = Color(0xFFE8CC90), depth = Color(0xFFBAA06A),
    warning = Color(0xFFE3A499), neutral = Color(0xFFABA79E), live = Color(0xFFE1BD78),
    air = Color(0xFFA4BCE6), sky = Color(0xFF182238), space = Color(0xFF8FC6BA),
    voice = Color(0xFFE1ADB5), learned = Color(0xFFA7C69F), onAccent = Color(0xFF211B11),
)
private val sandstone = AppearancePalette(
    background = Color(0xFFF2EDE2), surface = Color(0xFFFAF6EC), raised = Color(0xFFF0E6D5), tonal = Color(0xFFE7DDCA),
    border = Color(0xFF827360), grid = Color(0xFFD4C8B4),
    text = Color(0xFF30281F), secondary = Color(0xFF5F554A), muted = Color(0xFF65594C),
    accent = Color(0xFF6F4F28), highlight = Color(0xFF6A4B28), depth = Color(0xFF765A36),
    warning = Color(0xFFA3412E), neutral = Color(0xFF65594C), live = Color(0xFF76512A),
    air = Color(0xFF3B567C), sky = Color(0xFFDEDACE), space = Color(0xFF2D6660),
    voice = Color(0xFF8C4255), learned = Color(0xFF3D633B), onAccent = Color(0xFFFFF8ED),
)
private val indigo = AppearancePalette(
    background = Color(0xFF0C1424), surface = Color(0xFF122034), raised = Color(0xFF19283E), tonal = Color(0xFF1D2E45),
    border = Color(0xFF8090A2), grid = Color(0xFF334258),
    text = Color(0xFFF3ECDF), secondary = Color(0xFFC3CCD6), muted = Color(0xFFA1ADB9),
    accent = Color(0xFFDAB884), highlight = Color(0xFFECD0A0), depth = Color(0xFFC8AE87),
    warning = Color(0xFFE4A39B), neutral = Color(0xFFA1ADB9), live = Color(0xFFE3BF82),
    air = Color(0xFFB1C9EC), sky = Color(0xFF0C1424), space = Color(0xFF9BCCC1),
    voice = Color(0xFFE3B3BD), learned = Color(0xFFB1CAA9), onAccent = Color(0xFF201A12),
)

internal val AppearanceTheme.palette: AppearancePalette get() = when (this) {
    AppearanceTheme.ORIGINAL -> original
    AppearanceTheme.MIDNIGHT_RAGA -> raga
    AppearanceTheme.SANDSTONE_ATELIER -> sandstone
    AppearanceTheme.INDIGO_LOOM -> indigo
}
