package app.svan

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/** Optional shell reports; basic detection remains usable without setup. */
object DetectionSetup {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    enum class Stage { INSTALL, START, AUTHORIZE, WORKING, READY, ERROR }
    data class State(val stage: Stage = Stage.INSTALL, val message: String = "")
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private lateinit var context: Context
    private val main = Handler(Looper.getMainLooper())
    private var initialized = false

    fun init(ctx: Context) {
        if (initialized) return
        context = ctx.applicationContext
        initialized = true
        Shizuku.addBinderReceivedListenerSticky { main.post { ShizukuAudioReports.connect(context, retry = true); refresh() } }
        Shizuku.addBinderDeadListener { main.post { ShizukuAudioReports.disconnect(); refresh(); SystemEqService.refreshDetection(context) } }
        Shizuku.addRequestPermissionResultListener { code, result -> main.post {
            if (code == REQUEST) {
                if (result == PackageManager.PERMISSION_GRANTED) { ShizukuAudioReports.connect(context, retry = true); refresh() }
                else mutableState.value = State(Stage.ERROR, "Svan was not approved in Shizuku. You can continue with basic detection, or allow Svan in Shizuku's Authorized applications and retry.")
            }
        } }
        refresh()
    }

    fun refresh(clearError: Boolean = false) {
        if (!initialized) return
        val shell = ShizukuAudioReports.state.value
        mutableState.value = when {
            PlaybackSessions.hasDumpPermission(context) -> State(Stage.READY, "Enhanced detection is available through your existing audio-report permission.")
            ShizukuAudioReports.ready -> State(Stage.READY, "Enhanced detection is on while Shizuku is running. No app permission grant was needed.")
            shell.stage == ShizukuAudioReports.Stage.CONNECTING -> State(Stage.WORKING, "Checking music detection…")
            shell.stage == ShizukuAudioReports.Stage.ERROR && !clearError -> State(Stage.ERROR, detectionOemAdvice(Build.MANUFACTURER, shell.detail))
            !clearError && state.value.stage == Stage.ERROR -> return
            runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> State(Stage.AUTHORIZE)
            runCatching { context.packageManager.getApplicationInfo(SHIZUKU_PACKAGE, 0) }.isSuccess -> State(Stage.START)
            else -> State(Stage.INSTALL)
        }
    }

    fun enable() {
        if (!initialized || state.value.stage == Stage.WORKING) return
        refresh(clearError = true)
        if (state.value.stage == Stage.READY) { SystemEqService.refreshDetection(context); return }
        if (state.value.stage != Stage.AUTHORIZE) return
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { ShizukuAudioReports.connect(context, retry = true); refresh() }
            else if (Shizuku.shouldShowRequestPermissionRationale()) mutableState.value = State(Stage.ERROR, "Open Shizuku → Authorized applications and allow Svan, then retry. Basic detection stays available.")
            else Shizuku.requestPermission(REQUEST)
        } catch (e: RuntimeException) {
            EqController.log("detection setup: permission request failed: ${e.javaClass.simpleName}")
            mutableState.value = State(Stage.ERROR, "Could not connect to Shizuku. Basic detection stays available. Open Shizuku and retry.")
        }
    }

    fun diagnostics(): String = buildString {
        appendLine("Setup stage: ${state.value.stage} · ${state.value.message}")
        if (initialized) appendLine("App DUMP granted: ${PlaybackSessions.hasDumpPermission(context)}")
        appendLine("Shell reports: ${ShizukuAudioReports.state.value}")
        appendLine("Shizuku running: ${runCatching { Shizuku.pingBinder() }.getOrDefault(false)}")
        appendLine("Shizuku authorization: ${runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)}")
    }
    private const val REQUEST = 369
}
