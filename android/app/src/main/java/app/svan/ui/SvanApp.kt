package app.svan.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.Headphones
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The in-app Svaramanas bubble: tap to open the dialog, hold to hear the music without it. */
@Composable
private fun SvaramanasBubble(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val request by app.svan.svaramanas.Svaramanas.request.collectAsState()
    val listening by app.svan.svaramanas.Svaramanas.listening.collectAsState()
    val eq by app.svan.SvanRepository.eq.collectAsState()
    Box(
        modifier
            .size(58.dp)
            .shadow(10.dp, CircleShape, ambientColor = Svan.Gold, spotColor = Svan.Gold)
            .clip(CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { app.svan.svaramanas.SvaramanasActivity.open(context) },
                    onLongPress = { app.svan.svaramanas.Svaramanas.setBypass(true) },
                    onPress = {
                        tryAwaitRelease()
                        if (app.svan.SvanRepository.eq.value.smartBypass) app.svan.svaramanas.Svaramanas.setBypass(false)
                    },
                )
            }
            .semantics { contentDescription = "${request.mode.plainName}. Tap to open, hold to compare." },
    ) {
        if (request.mode == app.svan.svaramanas.SmartMode.SVARESA) {
            SvaresaMark(58.dp, listening = listening && request.enabled, resting = !request.enabled || eq.smartBypass)
        } else {
            SvaramanasMark(58.dp, listening = listening && request.enabled, resting = !request.enabled || eq.smartBypass)
        }
    }
}

private data class Tab(val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("Sound", Icons.Outlined.Headphones),
    Tab("EQ", Icons.Outlined.Equalizer),
    Tab("Presets", Icons.Outlined.LibraryMusic),
    Tab("Hi-Fi", Icons.Outlined.Tune),
    Tab("Lab", Icons.Outlined.Science),
)

@Composable
fun SvanApp(
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    labActions: List<Pair<String, () -> Unit>>,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val detection by app.svan.DetectionMonitor.status.collectAsState()
    val tabState = rememberSaveableStateHolder()
    // Boot animation once per app start (survives rotation, not a fresh launch).
    var booted by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = Svan.Black,
        bottomBar = {
            NavigationBar(containerColor = Svan.Surface, tonalElevation = androidx.compose.ui.unit.Dp(0f)) {
                TABS.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label, maxLines = 1, softWrap = false) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Svan.Gold,
                            selectedTextColor = Svan.Gold,
                            indicatorColor = Svan.Gold.copy(alpha = 0.14f),
                            unselectedIconColor = Svan.TextMuted,
                            unselectedTextColor = Svan.TextMuted,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (tab != 3 && detection.atMs > 0 && !detection.dumpPermission && detection.knownAudioSessions == 0 &&
                (detection.publicActive ?: 0) > 0) {
                androidx.compose.material3.TextButton(onClick = { tab = 3 }) {
                    Text("Music is playing · enable detection in Hi-Fi", color = Svan.Ember)
                }
            }
            AnimatedContent(tab, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "tab") { t ->
                tabState.SaveableStateProvider(t) {
                    when (t) {
                        0 -> SoundScreen()
                        1 -> EqScreen(onOpenDetection = { tab = 3 })
                        2 -> PresetsScreen()
                        3 -> AudiophileScreen(onStartCapture, onStopCapture)
                        else -> LabScreen(labActions)
                    }
                }
            }
        }
    }
    SvaramanasBubble(Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(end = 18.dp, bottom = 96.dp))
    if (!booted) BootAnimation(onDone = { booted = true })
    }
}
