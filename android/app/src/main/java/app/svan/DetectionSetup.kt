package app.svan

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

/** Phone-only setup; normal playback needs neither Shizuku nor wireless debugging afterwards. */
object DetectionSetup {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    enum class Stage { INSTALL, START, AUTHORIZE, WORKING, READY, ERROR }
    data class State(val stage: Stage = Stage.INSTALL, val message: String = "")
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private lateinit var context: Context
    private val main = Handler(Looper.getMainLooper())
    private var initialized = false
    private var busy = false
    @Volatile private var grantPending = false
    private val grantLog = ArrayDeque<String>()
    private var generation = 0

    fun init(ctx: Context) {
        if (initialized) return
        context = ctx.applicationContext
        initialized = true
        Shizuku.addBinderReceivedListenerSticky { main.post { refresh() } }
        Shizuku.addBinderDeadListener { main.post {
            if (busy) finish("Shizuku stopped. Open it, start it, then try again.") else refresh()
        } }
        Shizuku.addRequestPermissionResultListener { code, result -> main.post {
            if (code == REQUEST) {
                if (result == PackageManager.PERMISSION_GRANTED) grant()
                else mutableState.value = State(Stage.ERROR, "Detection permission was declined. You can allow Svan in Shizuku's Authorized applications, then retry.")
            }
        } }
        refresh()
    }

    fun refresh() {
        if (!initialized || busy) return
        val ready = PlaybackSessions.hasDumpPermission(context)
        mutableState.value = when {
            ready -> State(Stage.READY, "Enhanced app detection is enabled.")
            runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> State(Stage.AUTHORIZE)
            runCatching { context.packageManager.getApplicationInfo(SHIZUKU_PACKAGE, 0) }.isSuccess -> State(Stage.START)
            else -> State(Stage.INSTALL)
        }
    }

    fun enable() {
        if (busy) return
        refresh()
        if (mutableState.value.stage == Stage.READY) {
            SystemEqService.refreshDetection(context)
            return
        }
        if (mutableState.value.stage != Stage.AUTHORIZE) return
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) grant()
            else if (Shizuku.shouldShowRequestPermissionRationale()) {
                mutableState.value = State(Stage.ERROR, "Open Shizuku → Authorized applications and allow Svan, then retry.")
            } else Shizuku.requestPermission(REQUEST)
        } catch (e: RuntimeException) {
            mutableState.value = State(Stage.ERROR, "Could not request detection access: ${e.message}")
        }
    }

    private fun record(message: String) {
        val line = "${System.currentTimeMillis()}: $message"
        synchronized(grantLog) {
            grantLog.addLast(line)
            while (grantLog.size > 12) grantLog.removeFirst()
        }
        EqController.log("detection setup: $message")
    }

    fun diagnostics(): String = buildString {
        appendLine("Setup stage: ${state.value.stage} · ${state.value.message}")
        appendLine("Grant in flight: $grantPending")
        appendLine("Shizuku running: ${runCatching { Shizuku.pingBinder() }.getOrDefault(false)}")
        appendLine("Shizuku API version: ${runCatching { Shizuku.getVersion() }.getOrNull() ?: "unavailable"}")
        appendLine("Shizuku authorization granted: ${runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)}")
        synchronized(grantLog) { grantLog.forEach { appendLine(it) } }
    }

    private fun grant() {
        if (busy) return
        if (grantPending) {
            mutableState.value = State(Stage.ERROR, "The previous grant request is still waiting for Android. No second request was started. Share report if it stays blocked.")
            return
        }
        busy = true
        grantPending = true
        val attempt = ++generation
        mutableState.value = State(Stage.WORKING, "Granting Svan's audio-session access through Shizuku…")
        record("requesting fixed DUMP grant through direct package binder")
        // No UserService/app_process launch: it timed out on the owner's HiOS device despite Shizuku running.
        thread(name = "svan-detection-grant") {
            val result = runCatching { ShizukuDetectionGrant.enable(context) }
                .getOrElse { "Detection grant failed: ${it.javaClass.simpleName}: ${it.message}" }
            main.post {
                grantPending = false
                if (busy && attempt == generation) finish(if (result == "OK") null else result)
                else {
                    record("late grant response: $result")
                    // A late successful grant is real access, even after the UI deadline expired.
                    if (PlaybackSessions.hasDumpPermission(context)) { refresh(); SystemEqService.refreshDetection(context) }
                }
            }
        }
        main.postDelayed({
            if (busy && attempt == generation) finish("Shizuku is authorized, but Android did not answer the audio-session permission request. Detection is still disabled. Share report for the setup details.")
        }, 15_000)
    }

    private fun finish(error: String?) {
        busy = false
        generation++
        if (PlaybackSessions.hasDumpPermission(context)) {
            mutableState.value = State(Stage.READY, "Enhanced app detection is enabled. Play music and check its route below.")
            record("granted via Shizuku (direct package binder)")
            SystemEqService.refreshDetection(context)
        } else {
            val reason = error ?: "Android did not grant detection access. Please retry."
            record(reason)
            mutableState.value = State(Stage.ERROR, reason)
        }
    }

    private const val REQUEST = 369
}
