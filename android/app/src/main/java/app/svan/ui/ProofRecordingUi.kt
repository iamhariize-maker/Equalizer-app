package app.svan.ui

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import app.svan.listening.ProofCapture
import app.svan.listening.ProofRecorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** Countdown persists across tab changes, but cancellation never starts a recording. */
internal object ProofRecordingUi {
    val countdown = MutableStateFlow<Int?>(null)
    var wavBits = 16
    var matchLevel = false
    var abMatchLevel = true
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Fixed-height recording reference above every tab, so editing settings keeps the clock in view. */
@Composable
fun ProofRecordingPanel() {
    val state by ProofRecorder.state.collectAsStateWithLifecycle()
    if (state !is ProofRecorder.State.Recording) return
    val context = LocalContext.current
    val label by ProofRecorder.currentLabel.collectAsStateWithLifecycle()
    val sync by ProofRecorder.lastSync.collectAsStateWithLifecycle()
    val ab by ProofRecorder.lastAb.collectAsStateWithLifecycle()
    val cueNote by ProofCapture.cueNote.collectAsStateWithLifecycle()
    var frame by remember { mutableLongStateOf(ProofRecorder.recordedFrames) }
    var marking by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var markError by remember { mutableStateOf<String?>(null) }
    VisibleEffect(Unit) {
        while (true) withFrameNanos { frame = ProofRecorder.recordedFrames }
    }
    Column(Modifier.fillMaxWidth().height(274.dp).background(Svan.Black).padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text("SVANAM SHRESHTHAM · Recording", color = Svan.Gold, style = MaterialTheme.typography.labelSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        BoxWithConstraints(Modifier.fillMaxWidth().height(62.dp), contentAlignment = Alignment.CenterStart) {
            val size = (maxWidth.value / 6.2f / LocalDensity.current.fontScale).coerceAtMost(48f).sp
            Text(ProofRecorder.clockText(frame), fontSize = size, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, softWrap = false,
                // Announce whole seconds, avoiding millisecond accessibility-event floods.
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = "Recording time ${ProofRecorder.clockText(frame / ProofRecorder.sampleRate * ProofRecorder.sampleRate)}"
                })
        }
        Text(ab?.let { val choice = if (it.isAfter) "After" else "Before"; if (label == choice) choice else "$choice · $label" } ?: label,
            fontSize = 20.sp, lineHeight = 24.sp, color = Svan.Gold, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().height(50.dp))
        Text(sync?.let { "Sync at ${ProofRecorder.clockText(it.frame)}" } ?: "Press Sync for a flash and speaker clicks",
            color = Svan.Text, style = MaterialTheme.typography.bodySmall, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { ProofCapture.markAb(false) }, modifier = Modifier.weight(1f)) { Text("Before") }
            OutlinedButton(onClick = { ProofCapture.markAb(true) }, modifier = Modifier.weight(1f)) { Text("After") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { ProofCapture.sync(context) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Sync", maxLines = 1) }
            OutlinedButton(onClick = { marking = true; name = ""; markError = null }, modifier = Modifier.weight(1.2f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Mark now", maxLines = 1) }
            Button(onClick = { ProofCapture.stop() }, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = Svan.Gold, contentColor = Svan.OnGold)) { Text("Stop", maxLines = 1) }
        }
        Text(cueNote ?: "Svan's digital output", color = if (cueNote == null) Svan.TextMuted else Svan.Ember,
            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (marking) AlertDialog(
        onDismissRequest = { marking = false },
        title = { Text("Name the next segment") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                    label = { Text("Segment label") }, supportingText = { Text("${name.length}/40") })
                markError?.let { Text(it, color = Svan.Ember) }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                if (ProofCapture.mark(name)) marking = false else markError = "Segment limit reached or recording stopped."
            }) { Text("Mark now") }
        },
        dismissButton = { TextButton(onClick = { marking = false }) { Text("Cancel") } },
    )
}

/** Root overlay also covers navigation; white is only the requested sync flash. */
@Composable
fun ProofRecordingOverlay() {
    val context = LocalContext.current
    val state by ProofRecorder.state.collectAsStateWithLifecycle()
    // These rare events stay live: a started countdown must complete, and an old sync flash
    // must not replay when the Activity returns. They create no idle polling timer.
    val countdown by ProofRecordingUi.countdown.collectAsState()
    val flashId by ProofCapture.flash.collectAsState()
    var flash by remember { mutableStateOf(false) }
    var seenFlash by remember { mutableLongStateOf(flashId) }
    val awake = state is ProofRecorder.State.Recording || countdown != null
    DisposableEffect(awake) {
        val window = context.activity()?.window
        val kept = (window?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        val brightness = window?.attributes?.screenBrightness
        if (awake) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.let { it.attributes = it.attributes.also { attrs -> attrs.screenBrightness = 1f } }
        }
        onDispose {
            if (awake) {
                if (!kept) window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (brightness != null) window?.let { it.attributes = it.attributes.also { attrs -> attrs.screenBrightness = brightness } }
            }
        }
    }
    LaunchedEffect(flashId) {
        if (flashId != seenFlash) { seenFlash = flashId; flash = true; delay(150); flash = false }
    }
    LaunchedEffect(countdown) {
        val n = countdown ?: return@LaunchedEffect
        if (n > 0) { delay(1000); ProofRecordingUi.countdown.value = n - 1 }
        else {
            runCatching { ProofCapture.start(context, ProofRecordingUi.wavBits, ProofRecordingUi.matchLevel,
                automaticSync = true, abMatchLevel = ProofRecordingUi.abMatchLevel) }
                .onFailure { ProofRecorder.state.value = ProofRecorder.State.Failed(it.message ?: "Could not start recording") }
            ProofRecordingUi.countdown.value = null
        }
    }
    if (countdown != null || flash) Dialog(
        onDismissRequest = { if (!flash) ProofRecordingUi.countdown.value = null },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
            dismissOnBackPress = !flash, dismissOnClickOutside = false),
    ) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                window.attributes = window.attributes.also { it.screenBrightness = 1f }
            }
        }
        Box(Modifier.fillMaxSize().background(if (flash) Color.White else Svan.Black), contentAlignment = Alignment.Center) {
            if (!flash) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SVANAM SHRESHTHAM", color = Svan.Gold, style = MaterialTheme.typography.titleLarge)
                Text("$countdown", fontSize = 120.sp, fontFamily = FontFamily.Monospace, color = Color.White)
                TextButton(onClick = { ProofRecordingUi.countdown.value = null }) { Text("Cancel") }
            }
        }
    }
}
