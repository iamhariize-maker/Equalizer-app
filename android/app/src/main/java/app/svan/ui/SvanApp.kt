package app.svan.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

private data class Tab(val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("EQ", Icons.Outlined.Equalizer),
    Tab("Presets", Icons.Outlined.LibraryMusic),
    Tab("Audiophile", Icons.Outlined.Tune),
    Tab("Lab", Icons.Outlined.Science),
)

@Composable
fun SvanApp(
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    labActions: List<Pair<String, () -> Unit>>,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        containerColor = Svan.Black,
        bottomBar = {
            NavigationBar(containerColor = Svan.Surface, tonalElevation = androidx.compose.ui.unit.Dp(0f)) {
                TABS.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Svan.Saffron,
                            selectedTextColor = Svan.Saffron,
                            indicatorColor = Svan.Saffron.copy(alpha = 0.14f),
                            unselectedIconColor = Svan.TextMuted,
                            unselectedTextColor = Svan.TextMuted,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AnimatedContent(tab, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "tab") { t ->
                when (t) {
                    0 -> EqScreen()
                    1 -> PresetsScreen()
                    2 -> AudiophileScreen(onStartCapture, onStopCapture)
                    else -> LabScreen(labActions)
                }
            }
        }
    }
}

