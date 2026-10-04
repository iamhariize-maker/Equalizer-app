package app.svan

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
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
    private var bound = false
    private var generation = 0
    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, DetectionGrantService::class.java.name))
            .daemon(false).processNameSuffix("detection_setup").version(1)
    }

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
                if (result == PackageManager.PERMISSION_GRANTED) bind()
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
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bind()
            else if (Shizuku.shouldShowRequestPermissionRationale()) {
                mutableState.value = State(Stage.ERROR, "Open Shizuku → Authorized applications and allow Svan, then retry.")
            } else Shizuku.requestPermission(REQUEST)
        } catch (e: RuntimeException) {
            mutableState.value = State(Stage.ERROR, "Could not request detection access: ${e.message}")
        }
    }

    private fun bind() {
        if (busy) return
        busy = true
        val attempt = ++generation
        mutableState.value = State(Stage.WORKING, "Enabling Android app detection…")
        try {
            bound = true
            Shizuku.bindUserService(args, connection)
            main.postDelayed({ if (busy && attempt == generation) finish("Setup timed out. Open Shizuku and check that it is running, then retry.") }, 15_000)
        } catch (e: RuntimeException) { finish("Could not start detection setup: ${e.message}") }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (!busy || binder == null) return
            val attempt = generation
            thread(name = "svan-detection-grant") {
                val result = runCatching { IDetectionGrant.Stub.asInterface(binder).enableDetection() }
                    .getOrElse { "Detection setup failed: ${it.message}" }
                main.post {
                    if (busy && attempt == generation) finish(if (result == "OK") null else result)
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            if (busy) finish("Detection setup disconnected. Please retry.")
        }
    }

    private fun finish(error: String?) {
        busy = false
        generation++
        if (bound) {
            bound = false
            runCatching { Shizuku.unbindUserService(args, connection, true) }
        }
        if (PlaybackSessions.hasDumpPermission(context)) {
            mutableState.value = State(Stage.READY, "Enhanced app detection is enabled. Play music and check its route below.")
            EqController.log("detection setup: granted via Shizuku")
            SystemEqService.refreshDetection(context)
        } else mutableState.value = State(Stage.ERROR, error ?: "Android did not grant detection access. Please retry.")
    }

    private const val REQUEST = 369
}
