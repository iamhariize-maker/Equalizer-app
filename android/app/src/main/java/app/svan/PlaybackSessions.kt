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
    /** Client pid from `u/pid:uid/pid`; -1 when the report had no pid. */
    val pid: Int = -1,
    /** `type:` of the player (android.media.AudioTrack, AAudio, OpenSL ES…); empty if absent. */
    val playerType: String = "",
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
    /** Real app players the audio service lists without a session id (native/AAudio/OpenSL). */
    val sessionlessPlayers: List<PlaybackSession> = emptyList(),
    /** Every playback record line of the last scan, for the shareable diagnostic report. */
    val allConfigLines: List<String> = emptyList(),
)

/**
 * Finds other apps' audio sessions without relying on their OPEN broadcasts,
 * by reading the `audio` system service dump. DUMP is granted once through
 * the in-app Shizuku setup (or by ADB); it is not a normal runtime permission.
 */
object PlaybackSessions {

    private val UID = Regex("""(?:u/pid\s*:\s*|(?:client)?uid\s*[:=]\s*)(-?\d+)""", RegexOption.IGNORE_CASE)
    private val PID = Regex("""u/pid\s*:\s*-?\d+\s*/\s*(-?\d+)""", RegexOption.IGNORE_CASE)
    private val TYPE = Regex("""\btype\s*:\s*(\S+)""", RegexOption.IGNORE_CASE)
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

    /**
     * Like [parse] but also keeps real app players whose session id is 0: native (AAudio/OpenSL) or
     * OEM-reported players. Their session is recovered from the audio server's own tables by [SessionLedger].
     */
    fun parseAll(dump: String): List<PlaybackSession> = parseDump(dump).let { it.sessions + it.sessionless }

    private data class ParsedDump(
        val configLines: List<String>,
        val sessions: List<PlaybackSession>,
        val unparsedConfigCount: Int,
        val sessionless: List<PlaybackSession> = emptyList(),
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
        val sessionless = mutableListOf<PlaybackSession>()
        val records = configLines.mapNotNull { line ->
            val session = parseConfigLine(line, sessionless)
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
        // One entry per distinct pid is enough to resolve a native player's session later.
        val sessionlessPlayers = sessionless.groupBy { it.pid to it.uid }.values.map { players ->
            val active = players.firstOrNull { it.state == "started" } ?: players.lastOrNull { it.state != "released" } ?: players.last()
            active.copy(flags = players.fold(0) { flags, player -> flags or player.flags })
        }
        return ParsedDump(configLines, sessions, unparsed, sessionlessPlayers)
    }

    private fun parseConfigLine(line: String, sessionless: MutableList<PlaybackSession>? = null): PlaybackSession? {
        if (!CONFIG.containsMatchIn(line)) return null
        val uid = UID.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (uid < 0) return null // anonymized playback is not an attachable app identity
        val sid = SESSION.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val rawUsage = USAGE.find(line)?.groupValues?.get(1)?.uppercase()
        val usage = when (rawUsage) {
            "1", "MEDIA" -> "USAGE_MEDIA"
            "14", "GAME" -> "USAGE_GAME"
            "0", "UNKNOWN", null -> "USAGE_UNKNOWN"
            else -> if (rawUsage.startsWith("USAGE_")) rawUsage else "USAGE_$rawUsage"
        }
        val rawFlags = FLAGS.find(line)?.groupValues?.get(1)
        val flags = rawFlags?.let {
            val parsed = if (it.startsWith("0x", ignoreCase = true)) it.drop(2).toLongOrNull(16) else it.toLongOrNull()
            // Do not turn an overflowing/malformed policy field into permission to capture.
            if (parsed != null && parsed in 0L..0xffffffffL) parsed.toInt()
            else PlaybackSession.FLAG_NO_MEDIA_PROJECTION or PlaybackSession.FLAG_NO_SYSTEM_CAPTURE
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
        val session = PlaybackSession(
            sessionId = sid,
            uid = uid,
            usage = usage,
            state = state,
            flags = flags,
            pid = PID.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1,
            playerType = TYPE.find(line)?.groupValues?.get(1) ?: "",
        )
        if (sid <= 0) {
            // Session 0 / missing cannot be attached to a player effect directly.
            sessionless?.add(session)
            return null
        }
        return session
    }

    fun hasDumpPermission(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED

    /** Result of the last [queryPlayers]: every real app player, including those without a session id. */
    data class PlayerScan(val players: List<PlaybackSession>)

    /** Returns null (with [lastError] set) if the dump is unavailable. */
    fun query(context: Context): List<PlaybackSession>? = queryPlayers(context)?.filter { it.sessionId > 0 }

    /**
     * Like [query] but keeps players without a usable session id (their pid/uid let the ledger find
     * the real session in the audio server's tables). Returns null if the report is unavailable.
     */
    fun queryPlayers(context: Context): List<PlaybackSession>? {
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
        fun resolve(s: PlaybackSession): PlaybackSession {
            val packages = runCatching { pm.getPackagesForUid(s.uid) }.getOrNull()
            return s.copy(packageName = packages?.firstOrNull() ?: "uid:${s.uid}")
        }
        val sessions = parsed.sessions.map(::resolve)
        val sessionless = parsed.sessionless.map(::resolve)
        mutableReport.value = PlaybackScanReport(
            scannedAtMs = System.currentTimeMillis(),
            playbackConfigCount = parsed.configLines.size,
            parsedSessionCount = sessions.size,
            mediaSessions = sessions.filter { it.usageCapturable },
            unparsedConfigCount = parsed.unparsedConfigCount,
            configPreview = parsed.configLines.take(5).joinToString("\n").take(1600),
            sessionlessPlayers = sessionless,
            allConfigLines = parsed.configLines.take(40),
        )
        return sessions + sessionless
    }

    @Volatile var lastError: String? = null
        private set

    @Volatile var lastDumpSize: Int = 0
        private set

    /** Raw playback-config lines of the last dump, for diagnosing parser misses. */
    @Volatile var lastConfigLines: String = ""
        private set

    /** Output of [readService]; [partial] means the deadline/size limit hit after some data arrived. */
    data class ServiceRead(val text: String?, val partial: Boolean = false, val error: String? = null)

    /**
     * android.os.ServiceManager is a hidden API (greylisted), reached by
     * reflection. If a future Android blocks it, Shizuku's
     * SystemServiceHelper is the fallback.
     *
     * An OEM dump can stall or keep its writer open, so the read is bounded. With [keepPartial] the
     * text read so far is returned on timeout/size limit (AudioFlinger prints the sections Svan needs
     * first and slow hardware dumps last).
     */
    fun readService(name: String, timeoutMs: Long = 3_000L, maxBytes: Int = 2 * 1024 * 1024, keepPartial: Boolean = false): ServiceRead = try {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, name) as IBinder?
        if (binder == null) ServiceRead(null, error = "service '$name' not found")
        else {
            val pipe = ParcelFileDescriptor.createPipe()
            val read = pipe[0]
            val write = pipe[1]
            read.use {
                write.use { binder.dumpAsync(it.fileDescriptor, arrayOf()) }
                val poll = StructPollfd().apply { fd = read.fileDescriptor; events = OsConstants.POLLIN.toShort() }
                val deadline = SystemClock.elapsedRealtime() + timeoutMs
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var partial = false
                var failure: String? = null
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("Audio scan cancelled")
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0) { failure = "$name report timed out; retrying automatically"; partial = true; break }
                    if (Os.poll(arrayOf(poll), remaining.coerceAtMost(200).toInt()) == 0) continue
                    val events = poll.revents.toInt()
                    if (events and (OsConstants.POLLERR or OsConstants.POLLNVAL) != 0) throw IOException("$name report pipe closed unexpectedly")
                    if (events and (OsConstants.POLLIN or OsConstants.POLLHUP) == 0) continue
                    val count = Os.read(read.fileDescriptor, buffer, 0, buffer.size)
                    if (count == 0) break
                    if (output.size() + count > maxBytes) { failure = "$name report exceeded scan limit"; partial = true; break }
                    output.write(buffer, 0, count)
                }
                val text = output.toString(Charsets.UTF_8.name())
                when {
                    failure != null && !(keepPartial && text.isNotBlank()) -> ServiceRead(null, error = failure)
                    text.isBlank() -> ServiceRead(null, error = "Android returned an empty $name report.")
                    text.contains("Permission Denial", ignoreCase = true) && text.length < 4096 ->
                        ServiceRead(null, error = "Android denied the $name report. Re-enable music detection.")
                    else -> ServiceRead(text, partial = partial, error = failure)
                }
            }
        }
    } catch (e: Throwable) {
        ServiceRead(null, error = "${e.javaClass.simpleName}: ${e.message}")
    }

    fun dumpService(name: String): String? {
        val r = readService(name)
        r.text?.let {
            lastDumpSize = it.length
            lastConfigLines = it.lineSequence().filter { l -> CONFIG.containsMatchIn(l) }.take(6).joinToString("\n")
        }
        lastError = r.error
        return r.text
    }
}
