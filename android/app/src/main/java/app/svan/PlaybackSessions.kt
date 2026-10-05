package app.svan

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

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

/** Local-only summary of the most recent Android audio-service scan. */
data class PlaybackScanReport(
    val scannedAtMs: Long = 0L,
    val playbackConfigCount: Int = 0,
    val parsedSessionCount: Int = 0,
    val mediaSessions: List<PlaybackSession> = emptyList(),
    val unparsedConfigCount: Int = 0,
    val configPreview: String = "",
    val error: String? = null,
)

/**
 * Finds other apps' audio sessions without relying on their OPEN broadcasts,
 * by reading the `audio` system service dump. DUMP is granted once through
 * the in-app Shizuku setup (or by ADB); it is not a normal runtime permission.
 */
object PlaybackSessions {

    private val UID = Regex("""(?:u/pid\s*:\s*|(?:client)?uid\s*[:=]\s*)(-?\d+)""", RegexOption.IGNORE_CASE)
    private val USAGE = Regex("""\busage\s*[:=]\s*([\w]+)""", RegexOption.IGNORE_CASE)
    private val STATE = Regex("""\bstate\s*[:=]\s*([\w]+)""", RegexOption.IGNORE_CASE)
    private val FLAGS = Regex("""\bflags\s*[:=]\s*(0[xX][0-9A-Fa-f]+|\d+)""", RegexOption.IGNORE_CASE)
    private val SESSION = Regex("""\bsession(?:Id|_id|\s+id)?\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE)
    // Anchor to the record start: timestamps in the playback event HISTORY must
    // never resurrect a released session or override the current state.
    private val CONFIG = Regex("""^\s*(?:AudioPlaybackConfiguration\b|piid\s*[:=])""", RegexOption.IGNORE_CASE)
    private val CONTINUATION = Regex("""^\s+(?:u/pid|(?:client)?uid|state|session(?:Id|_id|\s+id)?|attr|AudioAttributes|usage|flags|deviceId|type|content|tags|mutedState)\b""", RegexOption.IGNORE_CASE)

    private val mutableReport = MutableStateFlow(PlaybackScanReport())
    val report = mutableReport.asStateFlow()

    /**
     * Parses the AudioPlaybackConfiguration lines of `dumpsys audio` (API 31+
     * format, which carries the session id). Field order differs between
     * releases, so each field is matched independently.
     */
    fun parse(dump: String): List<PlaybackSession> = parseDump(dump).sessions

    private data class ParsedDump(
        val configLines: List<String>,
        val sessions: List<PlaybackSession>,
        val unparsedConfigCount: Int,
    )

    private fun parseDump(dump: String): ParsedDump {
        val configLines = mutableListOf<String>()
        var record: StringBuilder? = null
        fun flush() { record?.let { configLines.add(it.toString()) }; record = null }
        dump.lineSequence().forEach { line ->
            when {
                CONFIG.containsMatchIn(line) -> { flush(); record = StringBuilder(line) }
                record != null && CONTINUATION.containsMatchIn(line) -> record!!.append(' ').append(line.trim())
                else -> flush()
            }
        }
        flush()
        var unparsed = 0
        val records = configLines.mapNotNull { line ->
            val session = parseConfigLine(line)
            if (session == null) unparsed++
            session
        }
        val sessions = records.groupBy { it.sessionId }.values.map { players ->
            // A released/paused track can appear after a playing track on
            // the same session. Preserve activity and every capture opt-out.
            val active = players.firstOrNull { it.state == "started" }
                ?: players.lastOrNull { it.state != "released" } ?: players.last()
            active.copy(flags = players.fold(0) { flags, player -> flags or player.flags })
        }
        return ParsedDump(configLines, sessions, unparsed)
    }

    private fun parseConfigLine(line: String): PlaybackSession? {
        if (!CONFIG.containsMatchIn(line)) return null
        val uid = UID.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (uid < 0) return null // anonymized playback is not an attachable app identity
        val sid = SESSION.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (sid <= 0) return null // session 0 cannot be attached to a player effect
        val rawUsage = USAGE.find(line)?.groupValues?.get(1)?.uppercase()
        val usage = when (rawUsage) {
            "1", "MEDIA" -> "USAGE_MEDIA"
            "14", "GAME" -> "USAGE_GAME"
            "0", "UNKNOWN", null -> "USAGE_UNKNOWN"
            else -> if (rawUsage.startsWith("USAGE_")) rawUsage else "USAGE_$rawUsage"
        }
        val rawFlags = FLAGS.find(line)?.groupValues?.get(1)
        val flags = rawFlags?.let {
            if (it.startsWith("0x", ignoreCase = true)) it.drop(2).toLongOrNull(16)?.toInt()
            else it.toLongOrNull()?.toInt()
        } ?: 0
        val rawState = STATE.find(line)?.groupValues?.get(1)?.lowercase()?.removePrefix("player_state_")
        val state = when (rawState) {
            "0" -> "released"
            "1" -> "idle"
            "2", "playing", "active" -> "started"
            "3" -> "paused"
            "4" -> "stopped"
            else -> rawState ?: "unknown"
        }
        return PlaybackSession(
            sessionId = sid,
            uid = uid,
            usage = usage,
            state = state,
            flags = flags,
        )
    }

    fun hasDumpPermission(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED

    /** Returns null (with [lastError] set) if the dump is unavailable. */
    fun query(context: Context): List<PlaybackSession>? {
        if (!hasDumpPermission(context)) {
            lastError = "Enhanced detection is not enabled. Open Hi-Fi → Music detection."
            mutableReport.value = mutableReport.value.copy(scannedAtMs = System.currentTimeMillis(), error = lastError)
            return null
        }
        val dump = dumpService("audio")
        if (dump == null) {
            mutableReport.value = mutableReport.value.copy(scannedAtMs = System.currentTimeMillis(), error = lastError)
            return null
        }
        val parsed = parseDump(dump)
        val pm = context.packageManager
        val sessions = parsed.sessions.map { s ->
            val packages = runCatching { pm.getPackagesForUid(s.uid) }.getOrNull()
            s.copy(packageName = packages?.firstOrNull() ?: "uid:${s.uid}")
        }
        mutableReport.value = PlaybackScanReport(
            scannedAtMs = System.currentTimeMillis(),
            playbackConfigCount = parsed.configLines.size,
            parsedSessionCount = sessions.size,
            mediaSessions = sessions.filter { it.usageCapturable },
            unparsedConfigCount = parsed.unparsedConfigCount,
            configPreview = parsed.configLines.take(5).joinToString("\n").take(1600),
        )
        return sessions
    }

    @Volatile var lastError: String? = null
        private set

    @Volatile var lastDumpSize: Int = 0
        private set

    /** Raw playback-config lines of the last dump, for diagnosing parser misses. */
    @Volatile var lastConfigLines: String = ""
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
            val pipe = ParcelFileDescriptor.createPipe()
            val read = pipe[0]
            val write = pipe[1]
            // An OEM dump can stall or keep its writer open. A bounded poll/read
            // lets the next scan recover instead of occupying the discovery worker forever.
            val dump = read.use {
                write.use { binder.dumpAsync(it.fileDescriptor, arrayOf()) }
                val poll = StructPollfd().apply { fd = read.fileDescriptor; events = OsConstants.POLLIN.toShort() }
                val deadline = SystemClock.elapsedRealtime() + 3_000L
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("Audio scan cancelled")
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0) throw IOException("Audio scan timed out; retrying automatically")
                    if (Os.poll(arrayOf(poll), remaining.coerceAtMost(200).toInt()) == 0) continue
                    val events = poll.revents.toInt()
                    if (events and (OsConstants.POLLERR or OsConstants.POLLNVAL) != 0) throw IOException("Audio report pipe closed unexpectedly")
                    if (events and (OsConstants.POLLIN or OsConstants.POLLHUP) == 0) continue
                    val count = Os.read(read.fileDescriptor, buffer, 0, buffer.size)
                    if (count == 0) break
                    if (output.size() + count > 2 * 1024 * 1024) throw IOException("Audio report exceeded scan limit")
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
            dump.also {
                lastDumpSize = it.length
                lastConfigLines = it.lineSequence().filter { l -> CONFIG.containsMatchIn(l) }.take(6).joinToString("\n")
                lastError = when {
                    it.isBlank() -> "Android returned an empty audio report."
                    it.contains("Permission Denial", ignoreCase = true) -> "Android denied the audio report. Re-enable music detection."
                    else -> null
                }
            }.takeIf { lastError == null }
        }
    } catch (e: Throwable) {
        lastError = "${e.javaClass.simpleName}: ${e.message}"
        null
    }
}
