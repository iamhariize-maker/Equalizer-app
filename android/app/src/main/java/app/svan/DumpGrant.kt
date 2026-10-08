package app.svan

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Optional and only when the user taps the button: while Shizuku is connected, gives Svan Android's
 * audio-report permission once (the same package-manager "grant" command that `adb shell pm grant` runs).
 * The permission stays after Shizuku is uninstalled and Developer options are switched off, until Svan is
 * removed or the permission is revoked in Android's app settings, so enhanced detection no longer needs
 * Shizuku. Only this app and this one permission are involved.
 */
object DumpGrant {
    enum class Stage { IDLE, WORKING, GRANTED, FAILED }
    data class State(val stage: Stage = Stage.IDLE, val message: String = "")
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    private const val PERMISSION = "android.permission.DUMP"
    // Binder.SHELL_COMMAND_TRANSACTION: how `cmd package ...` reaches the package service.
    private const val SHELL_COMMAND_TRANSACTION = ('_'.code shl 24) or ('C'.code shl 16) or ('M'.code shl 8) or 'D'.code

    fun grant(context: Context) {
        val app = context.applicationContext
        if (state.value.stage == Stage.WORKING) return
        if (PlaybackSessions.hasDumpPermission(app)) { mutableState.value = State(Stage.GRANTED); return }
        mutableState.value = State(Stage.WORKING)
        thread(name = "svan-dump-grant") {
            val failure = try { runGrant(app) } catch (e: Exception) { "${e.javaClass.simpleName}: ${e.message ?: "no details"}" }
            if (failure == null) {
                mutableState.value = State(Stage.GRANTED)
                EqController.log("dump grant: granted (Shizuku no longer needed for enhanced detection)")
            } else {
                mutableState.value = State(Stage.FAILED, grantFailureAdvice(Build.MANUFACTURER, failure))
                EqController.log("dump grant: failed: $failure")
            }
            DetectionSetup.refresh(clearError = true)
            SystemEqService.refreshDetection(app)
        }
    }

    fun reset() { if (state.value.stage != Stage.WORKING) mutableState.value = State() }

    /** Null on success, otherwise what went wrong. Runs off the main thread. */
    private fun runGrant(app: Context): String? {
        if (!runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false))
            return "Shizuku is not running or has not approved Svan"
        val pm = SystemServiceHelper.getSystemService("package") ?: return "Package service unavailable"
        val wrapped = ShizukuBinderWrapper(pm)
        val out = ParcelFileDescriptor.createPipe()
        val stdin = ParcelFileDescriptor.createPipe()
        runCatching { stdin[1].close() }
        val code = AtomicInteger(Int.MIN_VALUE)
        val receiver = object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) { code.set(resultCode) }
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeFileDescriptor(stdin[0].fileDescriptor)
            data.writeFileDescriptor(out[1].fileDescriptor)
            data.writeFileDescriptor(out[1].fileDescriptor)
            data.writeStringArray(arrayOf("grant", app.packageName, PERMISSION))
            data.writeStrongBinder(null) // no shell callback
            receiver.writeToParcel(data, 0)
            wrapped.transact(SHELL_COMMAND_TRANSACTION, data, reply, 0)
            reply.readException()
        } finally {
            data.recycle(); reply.recycle()
            runCatching { stdin[0].close() }
            runCatching { out[1].close() }
        }
        // The command's output (an error, if any) ends when the service closes its copy of the pipe.
        var text = ""
        val reader = thread(name = "svan-dump-grant-out") {
            text = runCatching {
                val buffer = ByteArray(2048)
                var n = 0
                FileInputStream(out[0].fileDescriptor).let { input ->
                    while (n < buffer.size) { val r = input.read(buffer, n, buffer.size - n); if (r < 0) break; n += r }
                }
                String(buffer, 0, n, Charsets.UTF_8).trim()
            }.getOrDefault("")
        }
        reader.join(2_000)
        runCatching { out[0].close() }
        // The result code is delivered asynchronously; the permission itself is the truth.
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (PlaybackSessions.hasDumpPermission(app)) return null
            SystemClock.sleep(150)
        }
        return listOfNotNull(text.ifBlank { null }, code.get().takeIf { it != Int.MIN_VALUE }?.let { "result code $it" })
            .joinToString("; ").ifBlank { "the permission was not granted" }
    }
}
