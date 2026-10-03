package app.svan

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.FileInputStream

/** One player as seen by the audio service. */
data class PlaybackSession(
    val sessionId: Int,
    val uid: Int,
    val usage: String,
    val state: String,
    val flags: Int,
    val packageName: String = "",
) {
    /** Usages AudioPlaybackCapture can see (docs: MEDIA, GAME, UNKNOWN). */
    val usageCapturable: Boolean get() = usage in CAPTURABLE_USAGES

    /** Per-player opt-outs that show up in the attributes. App-level manifest
     *  opt-outs do NOT appear here; CaptureCompat probes for those. */
    val flagsBlockCapture: Boolean get() = flags and (FLAG_NO_MEDIA_PROJECTION or FLAG_NO_SYSTEM_CAPTURE) != 0

    companion object {
        val CAPTURABLE_USAGES = setOf("USAGE_MEDIA", "USAGE_GAME", "USAGE_UNKNOWN")
        const val FLAG_NO_MEDIA_PROJECTION = 1 shl 10
        const val FLAG_NO_SYSTEM_CAPTURE = 1 shl 12
    }
}

/**
 * Finds other apps' audio sessions without relying on their OPEN broadcasts,
 * by reading the `audio` system service dump. Needs
 * `adb shell pm grant app.svan android.permission.DUMP`.
 */
object PlaybackSessions {

    private val UID_PID = Regex("""u/pid:(-?\d+)/(-?\d+)""")
    private val USAGE = Regex("""usage=(\w+)""")
    private val STATE = Regex("""state:(\w+)""")
    private val FLAGS = Regex("""flags=0x([0-9A-Fa-f]+)""")
    private val SESSION = Regex("""sessionId:(\d+)""")

    /**
     * Parses the AudioPlaybackConfiguration lines of `dumpsys audio` (API 31+
     * format, which carries the session id). Field order differs between
     * releases, so each field is matched independently.
     */
    fun parse(dump: String): List<PlaybackSession> =
        dump.lineSequence()
            .filter { "AudioPlaybackConfiguration" in it && "sessionId:" in it }
            .mapNotNull { line ->
                val uid = UID_PID.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                val sid = SESSION.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                if (sid <= 0) return@mapNotNull null
                PlaybackSession(
                    sessionId = sid,
                    uid = uid,
                    usage = USAGE.find(line)?.groupValues?.get(1) ?: "USAGE_UNKNOWN",
                    state = STATE.find(line)?.groupValues?.get(1) ?: "unknown",
                    flags = FLAGS.find(line)?.groupValues?.get(1)?.toLongOrNull(16)?.toInt() ?: 0,
                )
            }
            // A session can have several players; the dump lists the newest last.
            .associateBy { it.sessionId }
            .values
            .toList()

    fun hasDumpPermission(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED

    /** Returns null (with [lastError] set) if the dump is unavailable. */
    fun query(context: Context): List<PlaybackSession>? {
        val dump = dumpService("audio") ?: return null
        val pm = context.packageManager
        return parse(dump).map { s ->
            s.copy(packageName = pm.getPackagesForUid(s.uid)?.firstOrNull() ?: s.uid.toString())
        }
    }

    @Volatile var lastError: String? = null
        private set

    /**
     * android.os.ServiceManager is a hidden API (greylisted), reached by
     * reflection. If a future Android blocks it, Shizuku's
     * SystemServiceHelper is the fallback.
     */
    fun dumpService(name: String): String? = try {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, name) as IBinder?
        if (binder == null) {
            lastError = "service '$name' not found"
            null
        } else {
            val (read, write) = ParcelFileDescriptor.createPipe()
            binder.dumpAsync(write.fileDescriptor, arrayOf())
            write.close()
            FileInputStream(read.fileDescriptor).bufferedReader().use { it.readText() }.also {
                read.close()
                lastError = if (it.isBlank()) "empty dump (DUMP permission not granted?)" else null
            }.ifBlank { null }
        }
    } catch (e: Throwable) {
        lastError = "${e.javaClass.simpleName}: ${e.message}"
        null
    }
}
