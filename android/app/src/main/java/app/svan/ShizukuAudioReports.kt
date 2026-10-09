package app.svan

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import kotlin.concurrent.thread
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A blocked OEM launch never blocks basic session detection or the UI. */
object ShizukuAudioReports {
    enum class Stage { IDLE, CONNECTING, READY, ERROR }
    /** HELPER: a small process Shizuku starts for us. DIRECT: Shizuku's own shell process makes the call. */
    enum class Route { HELPER, DIRECT }
    data class State(val stage: Stage = Stage.IDLE, val detail: String = "", val route: Route? = null)
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    @Volatile private var service: IAudioReports? = null
    private val main = Handler(Looper.getMainLooper())
    private var connection: ServiceConnection? = null
    private var args: Shizuku.UserServiceArgs? = null
    @Volatile private var generation = 0
    // Some ROMs (seen on HiOS) never let the helper process answer. After direct works once, skip the wait.
    @Volatile private var preferDirect = false
    // Control calls can also stall on a ROM. Never make them on the UI thread or queue without bound.
    private val control = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { task -> Thread(task, "svan-shell-control").apply { isDaemon = true } })
    private val reader = BoundedReportReader()
    val ready: Boolean get() = state.value.stage == Stage.READY && service?.asBinder()?.isBinderAlive == true &&
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    fun connect(context: Context, retry: Boolean = false, forceDirect: Boolean = false) {
        if (!forceDirect && (ready || state.value.stage == Stage.CONNECTING || (!retry && state.value.stage == Stage.ERROR))) return
        if (!runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)) return
        disconnect()
        val attempt = ++generation
        mutableState.value = State(Stage.CONNECTING)
        val app = context.applicationContext
        if (forceDirect || preferDirect) { startDirect(app, attempt, null); return }
        val spec = Shizuku.UserServiceArgs(ComponentName(app, AudioReportsService::class.java))
            .daemon(false).processNameSuffix("audio_reports").debuggable(false).version(2)
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
                            mutableState.value = State(Stage.READY, route = Route.HELPER)
                            EqController.log("detection setup: shell audio reports ready (no app DUMP grant)")
                            DetectionSetup.refresh()
                            SystemEqService.refreshDetection(app)
                        } else helperFailed(result.error ?: "Shell audio reports unavailable", app, attempt)
                    }
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) { if (attempt == generation) helperFailed("Shizuku audio helper disconnected", app, attempt) }
        }
        args = spec
        connection = callback
        main.postDelayed({ if (attempt == generation && !ready) helperFailed("Shell helper did not respond within 10 seconds", app, attempt) }, 10_000)
        try {
            control.execute {
                if (attempt == generation) runCatching { Shizuku.bindUserService(spec, callback) }.onFailure { e ->
                    main.post { if (attempt == generation) helperFailed(e.javaClass.simpleName, app, attempt) }
                }
            }
        } catch (_: RuntimeException) { helperFailed("Previous shell connection is still waiting for Android", app, attempt) }
    }

    /** The helper process did not work here: read the same two reports through Shizuku itself before giving up. */
    private fun helperFailed(detail: String, app: Context, attempt: Int) {
        if (attempt != generation) return
        disconnect()
        val next = ++generation
        mutableState.value = State(Stage.CONNECTING)
        EqController.log("detection setup: shell helper unavailable ($detail); trying the direct Shizuku route")
        startDirect(app, next, detail)
    }

    private fun startDirect(app: Context, attempt: Int, helperDetail: String?) {
        thread(name = "svan-direct-probe") {
            val remote = DirectReports()
            val result = reader.read(7_500) {
                val audio = PlaybackSessions.readPipe(remote.openReport(0), "audio", 3_000, 2 * 1024 * 1024, false)
                if (audio.text != null) audio else PlaybackSessions.readPipe(remote.openReport(1), "media.audio_flinger", 3_000, 3 * 1024 * 1024, true)
            }
            main.post {
                if (attempt != generation) return@post
                if (result.text != null) {
                    service = remote
                    if (helperDetail != null) preferDirect = true
                    mutableState.value = State(Stage.READY, route = Route.DIRECT)
                    EqController.log("detection setup: shell audio reports ready (direct Shizuku route; no helper process, no app DUMP grant)")
                    DetectionSetup.refresh()
                    SystemEqService.refreshDetection(app)
                } else fail(listOfNotNull(helperDetail?.let { "helper: $it" }, "direct route: ${result.error ?: "no report"}").joinToString("; "), app)
            }
        }
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

    /**
     * Fixed audio reports without a helper process: Shizuku's shell-identity server makes the dump call for us.
     * The wrapper's own dump() would run in this process, so the transaction is sent explicitly.
     */
    private class DirectReports : IAudioReports.Stub() {
        override fun destroy() {}
        override fun openReport(report: Int): ParcelFileDescriptor {
            val name = when (report) {
                0 -> "audio"
                1 -> "media.audio_flinger"
                2 -> "media.audio_policy"
                else -> throw IllegalArgumentException("Unsupported audio report")
            }
            val service = SystemServiceHelper.getSystemService(name) ?: error("Audio service unavailable")
            val wrapped = ShizukuBinderWrapper(service)
            val pipe = ParcelFileDescriptor.createPipe()
            thread(name = "svan-direct-dump", isDaemon = true) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeFileDescriptor(pipe[1].fileDescriptor)
                    data.writeStringArray(emptyArray())
                    wrapped.transact(DUMP_TRANSACTION, data, reply, 0)
                    reply.readException()
                } catch (e: Exception) {
                    EqController.log("direct report $name failed: ${e.javaClass.simpleName}")
                } finally {
                    data.recycle(); reply.recycle()
                    runCatching { pipe[1].close() }
                }
            }
            return pipe[0]
        }
    }

    private const val DUMP_TRANSACTION = ('_'.code shl 24) or ('D'.code shl 16) or ('M'.code shl 8) or 'P'.code

    fun readReport(name: String, timeoutMs: Long, maxBytes: Int, keepPartial: Boolean): PlaybackSessions.ServiceRead {
        val id = when (name) { "audio" -> 0; "media.audio_flinger" -> 1; "media.audio_policy" -> 2; else -> return PlaybackSessions.ServiceRead(null, error = "Unsupported audio report") }
        val remote = if (ready) service else null
        if (remote == null) return PlaybackSessions.ServiceRead(null, error = "Shell helper unavailable")
        return reader.read(timeoutMs + 250) {
            PlaybackSessions.readPipe(remote.openReport(id), name, timeoutMs, maxBytes, keepPartial)
        }
    }
}
