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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A blocked OEM launch never blocks basic session detection or the UI. */
object ShizukuAudioReports {
    enum class Stage { IDLE, CONNECTING, READY, ERROR }
    data class State(val stage: Stage = Stage.IDLE, val detail: String = "")
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    @Volatile private var service: IAudioReports? = null
    private val main = Handler(Looper.getMainLooper())
    private var connection: ServiceConnection? = null
    private var args: Shizuku.UserServiceArgs? = null
    @Volatile private var generation = 0
    // Control calls can also stall on a ROM. Never make them on the UI thread or queue without bound.
    private val control = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { task -> Thread(task, "svan-shell-control").apply { isDaemon = true } })
    private val reader = BoundedReportReader()
    val ready: Boolean get() = state.value.stage == Stage.READY && service?.asBinder()?.isBinderAlive == true &&
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    fun connect(context: Context, retry: Boolean = false) {
        if (ready || state.value.stage == Stage.CONNECTING || (!retry && state.value.stage == Stage.ERROR)) return
        if (!runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)) return
        disconnect()
        val attempt = ++generation
        mutableState.value = State(Stage.CONNECTING)
        val app = context.applicationContext
        val spec = Shizuku.UserServiceArgs(ComponentName(app, AudioReportsService::class.java))
            .daemon(false).processNameSuffix("audio_reports").debuggable(false).version(1)
        val callback = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (attempt != generation || binder == null) return
                val remote = IAudioReports.Stub.asInterface(binder)
                // Successful binding alone does not prove this ROM permits audio reports.
                thread(name = "svan-shell-probe") {
                    val result = reader.read(7_500) {
                        val audio = PlaybackSessions.readPipe(remote.openReport(0), "audio", 3_000, 2 * 1024 * 1024, false)
                        if (audio.text != null) audio else PlaybackSessions.readPipe(remote.openReport(1), "media.audio_flinger", 3_000, 3 * 1024 * 1024, true)
                    }
                    main.post {
                        if (attempt != generation) return@post
                        if (result.text != null && binder.isBinderAlive) {
                            service = remote
                            mutableState.value = State(Stage.READY)
                            EqController.log("detection setup: shell audio reports ready (no app DUMP grant)")
                            DetectionSetup.refresh()
                            SystemEqService.refreshDetection(app)
                        } else fail(result.error ?: "Shell audio reports unavailable", app)
                    }
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) { if (attempt == generation) fail("Shizuku audio helper disconnected", app) }
        }
        args = spec
        connection = callback
        main.postDelayed({ if (attempt == generation && !ready) fail("Shell helper did not respond within 10 seconds", app) }, 10_000)
        try {
            control.execute {
                if (attempt == generation) runCatching { Shizuku.bindUserService(spec, callback) }.onFailure { e ->
                    main.post { if (attempt == generation) fail(e.javaClass.simpleName, app) }
                }
            }
        } catch (_: RuntimeException) { fail("Previous shell connection is still waiting for Android", app) }
    }

    private fun fail(detail: String, context: Context) {
        disconnect()
        mutableState.value = State(Stage.ERROR, detail)
        EqController.log("detection setup: shell reports unavailable: $detail; session announcements remain active")
        DetectionSetup.refresh()
        SystemEqService.refreshDetection(context)
    }

    fun reportsFailed(context: Context, detail: String) {
        main.post { if (ready) fail(detail, context.applicationContext) }
    }

    fun disconnect() {
        generation++
        service = null
        val oldArgs = args
        val oldConnection = connection
        args = null
        connection = null
        if (oldArgs != null && oldConnection != null) runCatching {
            control.execute { runCatching { Shizuku.unbindUserService(oldArgs, oldConnection, true) } }
        }
        mutableState.value = State()
    }

    fun readReport(name: String, timeoutMs: Long, maxBytes: Int, keepPartial: Boolean): PlaybackSessions.ServiceRead {
        val id = when (name) { "audio" -> 0; "media.audio_flinger" -> 1; else -> return PlaybackSessions.ServiceRead(null, error = "Unsupported audio report") }
        val remote = if (ready) service else null
        if (remote == null) return PlaybackSessions.ServiceRead(null, error = "Shell helper unavailable")
        return reader.read(timeoutMs + 250) {
            PlaybackSessions.readPipe(remote.openReport(id), name, timeoutMs, maxBytes, keepPartial)
        }
    }
}
