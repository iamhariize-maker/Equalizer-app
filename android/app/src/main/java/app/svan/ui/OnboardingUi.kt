package app.svan.ui

import androidx.activity.compose.BackHandler
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.svan.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive

enum class HelpPanel { NONE, DETECTION, BATTERY, COMPATIBILITY }
object OnboardingUi {
    val panel = MutableStateFlow(HelpPanel.NONE)
    // Only a debuggable preview Activity can set this. Fixtures never grant or route audio.
    val fixture = MutableStateFlow<String?>(null)
}

@Composable
fun ObserveWhileVisible(onTick: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val currentTick by rememberUpdatedState(onTick)
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) { currentTick(); delay(1_000) }
        }
    }
}

@Composable
fun SetupHelpHost() {
    val panel by OnboardingUi.panel.collectAsState()
    val fixture by OnboardingUi.fixture.collectAsState()
    if (panel != HelpPanel.NONE) {
        BackHandler { OnboardingUi.panel.value = HelpPanel.NONE; OnboardingUi.fixture.value = null }
        Surface(color = Svan.Black, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp)) {
                if (fixture != null) Text("UI test fixture · not live detection", color = Svan.Ember,
                    style = MaterialTheme.typography.labelMedium)
                key(panel, fixture) {
                    Column(Modifier.weight(1f).clipToBounds().verticalScroll(rememberScrollState())) {
                        when (panel) {
                            HelpPanel.DETECTION -> if (fixture?.startsWith("status-") == true)
                                WorkingStatusCard(workingFixture(fixture.orEmpty()), fixture = true) else DetectionWizard(fixture)
                            HelpPanel.BATTERY -> BatteryGuide()
                            HelpPanel.COMPATIBILITY -> CompatibilityList()
                            else -> Unit
                        }
                    }
                }
                OutlinedButton(onClick = { OnboardingUi.panel.value = HelpPanel.NONE; OnboardingUi.fixture.value = null },
                    modifier = Modifier.fillMaxWidth()) { Text("Back to Svan") }
            }
        }
    }
}

@Composable
private fun DetectionWizard(fixture: String?) {
    val context = LocalContext.current
    val grant by DetectionSetup.state.collectAsState()
    var live by remember { mutableStateOf(OnboardingAndroid.wizard(context)) }
    var linkError by remember { mutableStateOf<String?>(null) }
    ObserveWhileVisible { if (fixture == null) { DetectionSetup.refresh(); live = OnboardingAndroid.wizard(context) } }
    val snapshot = fixture?.let(::wizardFixture) ?: live
    val working = if (fixture != null) fixture == "working" else grant.stage == DetectionSetup.Stage.WORKING
    val error = if (fixture != null) fixture == "error" else grant.stage == DetectionSetup.Stage.ERROR
    ScreenTitle(if (snapshot.dumpGranted) "Music detection enabled" else "Music detection", "Optional help for players that do not announce an audio session.")
    Text("System effects can work without this setup when a player announces its session.",
        style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
    if (fixture == null) {
        Spacer(Modifier.height(8.dp))
        if (error) Text(grant.message, style = MaterialTheme.typography.bodySmall, color = Svan.Ember)
        PlayerRecognitionOption()
        TextButton(onClick = { OnboardingUi.panel.value = HelpPanel.NONE }) { Text("Continue with basic detection") }
    }
    Spacer(Modifier.height(10.dp))
    WizardStep.entries.forEachIndexed { index, step ->
        val checked = when (step) {
            WizardStep.INSTALL -> snapshot.installed == true
            WizardStep.DEBUGGING -> snapshot.debugging.developer == SettingState.ON && snapshot.debugging.wireless == SettingState.ON
            WizardStep.START -> snapshot.running == true
            WizardStep.AUTHORIZE -> snapshot.authorized == true
            WizardStep.GRANT -> snapshot.dumpGranted
            WizardStep.FINISH -> snapshot.dumpGranted && snapshot.debugging.verifiablyOff
        }
        val label = if (step == WizardStep.GRANT && error) "Unavailable · optional" else if (step == WizardStep.GRANT && !checked) "Optional" else if (snapshot.dumpGranted && step.ordinal < WizardStep.GRANT.ordinal && !checked) "Not needed now" else if (checked) "✓" else if (step == snapshot.step) "Current" else "Pending"
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${index + 1}. ${step.title}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(label, color = if (checked || step == snapshot.step) Svan.Gold else Svan.TextMuted,
                style = MaterialTheme.typography.labelSmall)
        }
    }
    Spacer(Modifier.height(8.dp))
    SvanCard {
        Column {
            Text(snapshot.step.title, style = MaterialTheme.typography.titleMedium, color = Svan.Gold)
            Text(when (snapshot.step) {
                WizardStep.INSTALL -> if (snapshot.installed == null) "Can't check Shizuku. Open it or install it, then check again." else "Install the official Shizuku app, then return here. Keep Play Protect enabled."
                WizardStep.DEBUGGING -> if (Build.VERSION.SDK_INT >= 30) "In Developer options, enable Wireless debugging on Wi-Fi; then open Shizuku to pair." else "Android 10 lacks phone-only wireless pairing. Shizuku's official guide describes computer-assisted setup."
                WizardStep.START -> "In Shizuku, choose Pairing and enter Android's pairing code, then tap Start."
                WizardStep.AUTHORIZE -> "Tap Allow Svan below and approve Svan in Shizuku's permission prompt."
                WizardStep.GRANT -> "Tap Enable music detection. Svan reads audio details through Shizuku; it does not grant a permission to the app."
                WizardStep.FINISH -> "Enhanced detection is available. Check the active mode below before turning debugging off."
            }, style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
            if (snapshot.step == WizardStep.DEBUGGING || snapshot.step == WizardStep.FINISH) DebuggingReadout(snapshot.debugging)
            if (fixture == null && grant.message.isNotBlank() && !error) Text(grant.message,
                color = Svan.Ember, style = MaterialTheme.typography.bodySmall)
            if (fixture == "error") Text(detectionGrantFailureMessage(false), color = Svan.Ember, style = MaterialTheme.typography.bodySmall)
            if (fixture == "working") Text("Enabling detection…", style = MaterialTheme.typography.bodySmall, color = Svan.Gold)
            linkError?.let { Text(it, color = Svan.Ember, style = MaterialTheme.typography.bodySmall) }
            fun open(intent: Intent) { linkError = openSettings(context, intent) }
            when (snapshot.step) {
                WizardStep.INSTALL -> Button(onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))) }, enabled = fixture == null,
                    modifier = Modifier.fillMaxWidth()) { Text("Get Shizuku") }
                WizardStep.DEBUGGING -> {
                    Button(onClick = { open(Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")) }, enabled = fixture == null,
                        modifier = Modifier.fillMaxWidth()) { Text("Open wireless debugging") }
                    OutlinedButton(onClick = { open(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }, enabled = fixture == null,
                        modifier = Modifier.fillMaxWidth()) { Text("Open Developer options") }
                    Text("If Developer options is hidden, open About phone and tap Build number seven times; the name varies by phone.",
                        style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
                }
                WizardStep.START -> Button(onClick = {
                    val launch = context.packageManager.getLaunchIntentForPackage(DetectionSetup.SHIZUKU_PACKAGE)
                    if (launch == null) linkError = "Shizuku could not be opened. Open it from your app list, then retry." else open(launch)
                }, enabled = fixture == null, modifier = Modifier.fillMaxWidth()) { Text("Open Shizuku") }
                WizardStep.AUTHORIZE, WizardStep.GRANT -> Button(onClick = { DetectionSetup.enable() },
                    enabled = fixture == null && !working, modifier = Modifier.fillMaxWidth()) {
                    Text(if (working) "Enabling detection…" else if (snapshot.step == WizardStep.AUTHORIZE) "Allow Svan" else "Enable music detection")
                }
                WizardStep.FINISH -> {
                    OutlinedButton(onClick = { open(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }, enabled = fixture == null,
                        modifier = Modifier.fillMaxWidth()) { Text("Open Developer options") }
                    Text(if (fixture == null && !PlaybackSessions.hasDumpPermission(context)) "Keep Shizuku running for enhanced detection. Restart it after a phone reboot. You can try turning USB and wireless debugging off; some phones then stop Shizuku, and Svan returns to basic detection." else "Your existing audio-report permission stays after Shizuku stops. You can turn USB and wireless debugging off.",
                        style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
                    Button(onClick = { OnboardingUi.panel.value = HelpPanel.NONE }, enabled = fixture == null,
                        modifier = Modifier.fillMaxWidth()) { Text("Finish") }
                }
            }
            TextButton(onClick = {
                linkError = null
                DetectionSetup.refresh(clearError = true)
                if (live.running == true && !live.dumpGranted) DetectionSetup.enable()
                live = OnboardingAndroid.wizard(context)
            }, enabled = fixture == null) {
                Text(if (error) "Retry" else "Check again")
            }
        }
    }
    if (fixture == null && error) {
        TextButton(onClick = { linkError = openSettings(context, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }) { Text("Open Developer options") }
        var advanced by remember { mutableStateOf(false) }
        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide computer option" else "Other option: use a computer") }
        if (advanced) Text("If you have a computer with adb, an optional existing-permission route is: adb shell pm grant app.svan android.permission.DUMP. This may still be blocked by your phone. Svan never runs this grant itself.",
            style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
    }
    if (!snapshot.dumpGranted) Text("Pairing cannot be verified separately from a running Shizuku binder. Its running tick is the observed result of startup, not a claim about a stored pairing.",
        style = MaterialTheme.typography.labelSmall, color = Svan.TextMuted)
}

@Composable
private fun PlayerRecognitionOption() {
    val context = LocalContext.current
    val connected by PlayerRecognition.connected.collectAsState()
    var enabled by remember { mutableStateOf(PlayerRecognition.enabled(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    ObserveWhileVisible { enabled = PlayerRecognition.enabled(context) }
    SvanCard {
        Column {
            Text("Player recognition · optional", style = MaterialTheme.typography.titleMedium, color = Svan.Gold)
            Text("Shows which music app is playing without Shizuku. Android asks for notification access; Svan only checks the app name and play/pause state. It never reads notification text or messages, or saves or shares this information.",
                style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
            Text("EQ still needs the player's audio connection. Your sound and quality settings stay as you chose them.",
                style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
            if (enabled) Text(if (connected) "Player recognition is on." else "Access is allowed; waiting for Android to connect. Try switching access off and on if it stays here.",
                style = MaterialTheme.typography.bodySmall, color = Svan.Gold)
            OutlinedButton(onClick = {
                error = openSettings(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }, modifier = Modifier.fillMaxWidth()) { Text(if (enabled) "Manage player recognition" else "Use player recognition") }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Svan.Ember) }
        }
    }
}

@Composable
fun DebuggingReadout(state: DebuggingState) {
    Text("Developer options: ${state.developer.label} · USB: ${state.usb.label} · Wireless: ${state.wireless.label}",
        style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted, modifier = Modifier.padding(vertical = 8.dp))
    Text(if (state.verifiablyOff) "Debugging is off." else "Debugging is on or can't be verified off. Check Developer options.",
        style = MaterialTheme.typography.bodyMedium, color = if (state.verifiablyOff) Svan.Gold else Svan.Ember)
    Text("Payment and banking app compatibility still needs a phone check.", style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
}

/** Exact settings actions when supported; a visible fallback on OEMs that do not expose them. */
private fun openSettings(context: Context, intent: Intent): String? = try {
    if (intent.resolveActivity(context.packageManager) != null) { context.startActivity(intent); null }
    else if (intent.action == "android.settings.WIRELESS_DEBUGGING_SETTINGS") {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        "Opened Developer options. Find Wireless debugging and open it."
    } else "This shortcut is unavailable. Open the matching page in Android Settings, then retry."
} catch (e: RuntimeException) {
    EqController.log("setup settings shortcut failed: ${e.javaClass.simpleName}: ${e.message}")
    "Android could not open that page. Open Settings manually, then retry."
}

@Composable
fun WorkingStatusCard(state: WorkingState, fixture: Boolean = false, stats: CaptureService.Stats? = null) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    SvanCard {
        Column {
            Text("Is it working?", style = MaterialTheme.typography.titleMedium, color = Svan.Gold)
            Text(when (state.kind) {
                WorkingKind.IDLE -> "Nothing playing"
                WorkingKind.UNKNOWN -> "Can't tell whether playback is active"
                WorkingKind.UNREACHABLE -> "Player found, but not connected"
                WorkingKind.ROUTED -> "Player found and routed"
            }, style = MaterialTheme.typography.bodyMedium)
            state.players.forEach { player ->
                Text("${player.name} · ${player.engine?.title ?: player.reason ?: "No attachable session"}",
                    style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
                if (player.engine != null && player.reason != null) Text(player.reason,
                    style = MaterialTheme.typography.labelSmall, color = Svan.TextMuted)
            }
            if (state.showsCapturePeaks(hasStats = stats != null, fixture = fixture)) stats?.let { stats ->
                Text("Capture peaks · in %.1f dBFS · out %.1f dBFS".format(stats.inputPeakDb, stats.outputPeakDb),
                    style = MaterialTheme.typography.bodySmall, color = Svan.Gold)
            }
            if (state.kind == WorkingKind.ROUTED) Text("Routing is observed here; measured response and phone compatibility need separate checks.",
                style = MaterialTheme.typography.labelSmall, color = Svan.TextMuted)
            TextButton(onClick = {
                val text = OnboardingAndroid.summary(context, state)
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Svan diagnostic summary", text))
                copied = true
            }, enabled = !fixture) { Text(if (copied) "Diagnostic summary copied" else "Copy diagnostic summary") }
            Text("Copies version, Android, detection state, counts, engine routes and output type locally. No media titles or account details.",
                style = MaterialTheme.typography.labelSmall, color = Svan.TextMuted)
        }
    }
}

@Composable
fun ContextualSetupPrompt(state: WorkingState) {
    val context = LocalContext.current
    var dismissed by remember { mutableStateOf(OnboardingAndroid.dismissed(context)) }
    ObserveWhileVisible { dismissed = OnboardingAndroid.dismissed(context) }
    val key = state.promptKey(dismissed) ?: return
    val name = state.players.first { it.key == key }.name
    SvanCard {
        Column {
            Text("$name is playing but Svan can't reach its audio yet. Try the optional detection options.", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { OnboardingUi.panel.value = HelpPanel.DETECTION }) { Text("Fix music detection") }
                TextButton(onClick = { OnboardingAndroid.dismiss(context, key); dismissed = OnboardingAndroid.dismissed(context) }) { Text("Not now") }
            }
        }
    }
}

@Composable
fun FirstRunWelcome() {
    val context = LocalContext.current
    var show by remember { mutableStateOf(!OnboardingAndroid.prefs(context).getBoolean("welcome_seen", false)) }
    if (!show) return
    SvanCard {
        Column {
            Text("Start with your music", style = MaterialTheme.typography.titleMedium, color = Svan.Gold)
            Text("System effects starts with Flat on a new install. Play your music; optional detection help appears only when Svan cannot reach it.",
                style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
            TextButton(onClick = { OnboardingUi.panel.value = HelpPanel.BATTERY }) { Text("Background audio help") }
            TextButton(onClick = { OnboardingAndroid.prefs(context).edit().putBoolean("welcome_seen", true).apply(); show = false }) { Text("Got it") }
        }
    }
}

@Composable
fun SetupHelpLinks() {
    val context = LocalContext.current
    var reset by remember { mutableStateOf(false) }
    TextButton(onClick = { OnboardingUi.panel.value = HelpPanel.DETECTION }) { Text("Music detection setup") }
    TextButton(onClick = { OnboardingUi.panel.value = HelpPanel.BATTERY }) { Text("Background audio help") }
    TextButton(onClick = { OnboardingUi.panel.value = HelpPanel.COMPATIBILITY }) { Text("Player compatibility evidence") }
    TextButton(onClick = { OnboardingAndroid.resetPrompts(context); reset = true }) { Text(if (reset) "Hidden prompts reset" else "Reset hidden setup prompts") }
}

@Composable
private fun BatteryGuide() {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    ScreenTitle("Background audio", "Android may stop background audio to save battery.")
    val suggestion = batteryAdvice(Build.MANUFACTURER)
    Text(suggestion, color = Svan.TextMuted, style = MaterialTheme.typography.bodyMedium)
    Text("Allowing background activity may use more battery and does not guarantee survival on every phone. Change only what you need, then test with the screen off.",
        color = Svan.TextMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
    OutlinedButton(onClick = { message = openSettings(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) },
        modifier = Modifier.fillMaxWidth()) { Text("Open Svan's app settings") }
    // REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is absent: link to the list, never request an exemption.
    OutlinedButton(onClick = { message = openSettings(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
        modifier = Modifier.fillMaxWidth()) { Text("Open battery optimisation settings") }
    message?.let { Text(it, color = Svan.Ember, style = MaterialTheme.typography.bodySmall) }
    TextButton(onClick = { OnboardingUi.panel.value = HelpPanel.COMPATIBILITY }) { Text("Player compatibility evidence") }
}

@Composable
private fun CompatibilityList() {
    val context = LocalContext.current
    val rows = remember { CompatibilityEntry.parse(context.assets.open("compatibility.tsv").bufferedReader().use { it.readText() }) }
    ScreenTitle("Player evidence", "Synthetic tests and earlier phone reports have different limits.")
    Text("Unverified means this version, phone and route still need a test. A listed package is not proof of compatibility.",
        style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
    rows.forEach { row ->
        SvanCard {
            Column {
                Text(row.player, style = MaterialTheme.typography.titleMedium)
                Text("Without setup: ${row.withoutSetup}\nMusic detection: ${row.withDetection}\nEvidence: ${row.verification}",
                    style = MaterialTheme.typography.bodySmall, color = Svan.Gold)
                Text(row.note, style = MaterialTheme.typography.bodySmall, color = Svan.TextMuted)
            }
        }
    }
}

private fun wizardFixture(name: String): WizardSnapshot {
    val off = DebuggingState(SettingState.OFF, SettingState.OFF, SettingState.OFF)
    val on = DebuggingState(SettingState.ON, SettingState.ON, SettingState.ON)
    return when (name) {
        "install" -> WizardSnapshot(false, false, false, false, off)
        "debugging" -> WizardSnapshot(true, false, false, false, off)
        "start" -> WizardSnapshot(true, false, false, false, on)
        "authorize", "error" -> WizardSnapshot(true, true, false, false, on)
        "grant", "working" -> WizardSnapshot(true, true, true, false, on)
        "finish-off" -> WizardSnapshot(true, false, false, true, off)
        "finish-unknown" -> WizardSnapshot(true, false, false, true, DebuggingState())
        else -> WizardSnapshot(true, true, true, true, on)
    }
}

private fun workingFixture(name: String): WorkingState {
    val player = WorkingPlayer("ci.ui.fixture", "CI player (UI fixture)", true, false,
        reason = "Android has not exposed an attachable audio session.")
    return when (name) {
        "status-idle" -> WorkingState.derive(0, emptyList(), false)
        "status-unknown" -> WorkingState.derive(null, emptyList(), false)
        "status-system" -> WorkingState.derive(1, listOf(player.copy(attachable = true, engine = UiEngine.SYSTEM_EFFECTS, reason = null)), true)
        "status-audiophile" -> WorkingState.derive(1, listOf(player.copy(attachable = true, engine = UiEngine.AUDIOPHILE, reason = null)), true)
        else -> WorkingState.derive(1, listOf(player), false)
    }
}
