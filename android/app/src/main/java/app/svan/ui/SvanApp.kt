package app.svan.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable

/** Dedicated shortcut dock: it never overlays a slider, dial or numeric readout. */
@Composable
private fun SvaramanasDock(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val request by app.svan.svaramanas.Svaramanas.request.collectAsState()
    val listening by app.svan.svaramanas.Svaramanas.listening.collectAsState()
    val eq by app.svan.SvanRepository.eq.collectAsState()
    Row(
        modifier
            .fillMaxWidth().heightIn(min = 56.dp)
            .background(Svan.Surface)
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
            .semantics(mergeDescendants=true) {
                role=Role.Button
                contentDescription = "${request.mode.plainName}. Tap to open, hold to compare."
                onClick(label="Open ${request.mode.plainName}") { app.svan.svaramanas.SvaramanasActivity.open(context); true }
                customActions = listOf(CustomAccessibilityAction(if (eq.smartBypass) "Return to smart processing" else "Compare without smart adjustments") {
                    app.svan.svaramanas.Svaramanas.setBypass(!eq.smartBypass); true
                })
            }
            .padding(horizontal=16.dp, vertical=8.dp),
        verticalAlignment=Alignment.CenterVertically,
    ) {
        if (request.mode == app.svan.svaramanas.SmartMode.SVARESA) {
            SvaresaMark(32.dp, listening = listening && request.enabled, resting = !request.enabled || eq.smartBypass)
        } else {
            SvaramanasMark(32.dp, listening = listening && request.enabled, resting = !request.enabled || eq.smartBypass)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(request.mode.sanskritName,style=MaterialTheme.typography.labelLarge,color=Svan.Gold)
            Text("${request.mode.plainName} · Hold to compare",style=MaterialTheme.typography.labelSmall,color=Svan.TextMuted)
        }
        Text("Open",style=MaterialTheme.typography.labelMedium,color=Svan.Gold)
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
@OptIn(ExperimentalMaterial3Api::class)
fun SvanApp(
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    labActions: List<Pair<String, () -> Unit>>,
) {
    val blindOpen by app.svan.listening.BlindLab.open.collectAsState()
    val helpPanel by OnboardingUi.panel.collectAsState()
    if(blindOpen) BlindListening()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var sectionsOpen by rememberSaveable { mutableStateOf(false) }
    val largeNavigation = LocalDensity.current.fontScale > 1.5f && LocalConfiguration.current.screenWidthDp < 420
    val context = androidx.compose.ui.platform.LocalContext.current
    var working by androidx.compose.runtime.remember { mutableStateOf(app.svan.OnboardingAndroid.working(context)) }
    ObserveWhileVisible { working = app.svan.OnboardingAndroid.working(context) }
    val tabState = rememberSaveableStateHolder()
    // Boot animation once per app start (survives rotation, not a fresh launch).
    var booted by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
    if (helpPanel == HelpPanel.NONE) Scaffold(
        containerColor = Svan.Black,
        bottomBar = {
            Column {
            SvaramanasDock()
            if (largeNavigation) Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).background(Svan.Surface).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(TABS[tab].icon, null, tint = Svan.Gold)
                Spacer(Modifier.width(12.dp))
                Text(TABS[tab].label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { sectionsOpen = true }) { Icon(Icons.Outlined.Menu, "All sections", tint = Svan.Gold) }
            } else
            NavigationBar(containerColor = Svan.Surface, tonalElevation = androidx.compose.ui.unit.Dp(0f)) {
                TABS.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
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
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ProofRecordingPanel()
            ContextualSetupPrompt(working)
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
    if (!booted) BootAnimation(onDone = { booted = true })
    if (sectionsOpen) ModalBottomSheet(onDismissRequest = { sectionsOpen = false }, containerColor = Svan.Surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp)) {
            Text("Sections", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            TABS.forEachIndexed { index, destination ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .selectable(tab == index, role = Role.Tab, onClick = { tab = index; sectionsOpen = false })
                    .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(destination.icon, null, tint = if (tab == index) Svan.Gold else Svan.TextMuted)
                    Spacer(Modifier.width(16.dp))
                    Text(destination.label, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
    SetupHelpHost()
    ProofRecordingOverlay()
    }
}
