package app.svan.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.svan.R

/** Stable appearance IDs are deliberately independent of audio/settings backup models. */
enum class AppearanceTheme(
    val storedId: String,
    val title: String,
    val description: String,
    val isLight: Boolean,
    val landscape: Int?,
    val print: Int,
    val printOrigin: IntOffset,
    val printSize: IntSize,
) {
    ORIGINAL("svan_original", "Svan Original", "Familiar gold · desert at night", false, null,
        R.drawable.print_original, IntOffset(42, 264), IntSize(2090, 194)),
    MIDNIGHT_RAGA("midnight_raga", "Midnight Raga", "Night miniature · fine botanical brass", false,
        R.drawable.landscape_raga, R.drawable.print_raga, IntOffset(4, 293), IntSize(2162, 164)),
    SANDSTONE_ATELIER("sandstone_atelier", "Sandstone Atelier", "Warm parchment · etched dunes", true,
        R.drawable.landscape_sandstone, R.drawable.print_sandstone, IntOffset(10, 264), IntSize(2150, 200)),
    INDIGO_LOOM("indigo_loom", "Indigo Loom", "Indigo contours · geometric print", false,
        R.drawable.landscape_indigo, R.drawable.print_indigo, IntOffset(0, 294), IntSize(2172, 137)),
    SVAN_LAB("mint_circuit", "Mint Circuit", "Mint on charcoal · clean instrument panels", false, null,
        R.drawable.print_original, IntOffset(42, 264), IntSize(2090, 194));

    companion object {
        fun fromStoredId(id: String?): AppearanceTheme = entries.firstOrNull { it.storedId == id } ?: ORIGINAL
    }
}

/** App-context-only presentation state. No audio controller or service is called here. */
object SvanAppearance {
    private const val PREFERENCES = "svan_appearance"
    private const val THEME = "theme_id"
    private var preferences: SharedPreferences? = null
    var current by mutableStateOf(AppearanceTheme.ORIGINAL)
        private set
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == THEME) current = AppearanceTheme.fromStoredId(prefs.getString(THEME, null))
    }

    fun initialize(context: Context) {
        if (preferences != null) return
        val prefs = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        current = AppearanceTheme.fromStoredId(prefs.getString(THEME, null))
        preferences = prefs
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun choose(theme: AppearanceTheme) {
        current = theme
        preferences?.edit()?.putString(THEME, theme.storedId)?.apply()
    }
}

/** Real generated print, cropped only at draw time; transparent source pixels stay unchanged. */
@Composable
fun ThemePrint(modifier: Modifier = Modifier, theme: AppearanceTheme = SvanAppearance.current, alpha: Float = 0.72f) {
    if (theme == AppearanceTheme.SVAN_LAB) {
        androidx.compose.foundation.Canvas(modifier.fillMaxWidth().height(18.dp)) {
            drawLine(theme.palette.grid, androidx.compose.ui.geometry.Offset(0f, size.height / 2),
                androidx.compose.ui.geometry.Offset(size.width, size.height / 2), 1.dp.toPx())
            drawCircle(theme.palette.accent, 3.dp.toPx(), androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2))
        }
        return
    }
    val bitmap = ImageBitmap.imageResource(theme.print)
    Image(
        painter = BitmapPainter(bitmap, theme.printOrigin, theme.printSize),
        contentDescription = null,
        modifier = modifier.fillMaxWidth().height(if (theme == AppearanceTheme.ORIGINAL) 18.dp else 22.dp),
        contentScale = ContentScale.Fit,
        alpha = alpha,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSheet(onDismiss: () -> Unit) {
    val selected = SvanAppearance.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Svan.Surface, contentColor = Svan.Text,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text("Appearance", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text("A complete world for listening. Your sound stays as you left it.",
                style = MaterialTheme.typography.bodyMedium, color = Svan.TextMuted)
            Spacer(Modifier.height(20.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            AppearanceTheme.entries.forEach { theme ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(if (selected == theme) Svan.SurfaceHigher else Svan.Surface)
                        .selectable(selected == theme, role = Role.RadioButton, onClick = { SvanAppearance.choose(theme) })
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(64.dp, 48.dp).clip(RoundedCornerShape(8.dp)).background(theme.palette.background),
                        contentAlignment = Alignment.Center) {
                        if (theme.landscape != null) Image(painterResource(theme.landscape), null,
                            Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                        else ThemePrint(Modifier.padding(horizontal = 4.dp), theme, alpha = 1f)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(theme.title, style = MaterialTheme.typography.titleMedium)
                        Text(theme.description, style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
                        if (theme == AppearanceTheme.ORIGINAL) Text("Default", style = MaterialTheme.typography.labelSmall, color = Svan.Gold)
                        if (selected == theme) Text("Selected", style = MaterialTheme.typography.labelSmall, color = Svan.Gold)
                    }
                    RadioButton(selected == theme, onClick = null)
                }
                Spacer(Modifier.height(4.dp))
            }
            }
            Spacer(Modifier.height(14.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Done") }
            Spacer(Modifier.height(20.dp))
        }
    }
}
