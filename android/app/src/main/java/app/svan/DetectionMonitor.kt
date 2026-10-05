package app.svan

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Runs one complete detection pass and publishes what it found, why, and how healthy it is.
 *
 * Three independent views are combined, so no single Android report format can blind Svan:
 *  - the AudioService player list (`dumpsys audio`),
 *  - the audio server's tracks, effect chains and session owners (`dumpsys media.audio_flinger`),
 *  - the public, permission-free active-playback count (used only to notice a blind spot).
 */
object DetectionMonitor {
    private val mutableStatus = MutableStateFlow(DetectionStatus())
    val status: StateFlow<DetectionStatus> = mutableStatus.asStateFlow()

    @Volatile var lastAfSnapshot: AfSnapshot? = null
        private set
    @Volatile var lastAfExcerpt: String = ""
        private set
    @Volatile var scans = 0
        private set
    @Volatile var lastCompleteScanMs = 0L
        private set

    /** Debug-only fault injection (emulator regressions): behave as if one Android report were unreadable. */
    @Volatile var debugBlindPlayers = false
    @Volatile var debugBlindServer = false
    private var lastSummary = ""
    private var lastServerReadMs = 0L
    private var lastServerOk: Boolean? = null
    private var lastServerError: String? = null
    private const val IDLE_SERVER_READ_MS = 30_000L

    data class Outcome(val ledger: Ledger, val complete: Boolean, val af: AfSnapshot?, val status: DetectionStatus, val serverRead: Boolean)

    /** Number of players Android says are active, from the public callback API (no permission). Null if unavailable. */
    fun publicActiveCount(context: Context): Int? = runCatching {
        context.getSystemService(AudioManager::class.java).activePlaybackConfigurations.count {
            val usage = it.audioAttributes.usage
            usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_GAME || usage == AudioAttributes.USAGE_UNKNOWN
        }
    }.getOrNull()

    /** Blocking; call from the discovery worker. Never throws. */
    fun scan(context: Context): Outcome {
        scans++
        val now = System.currentTimeMillis()
        val perm = PlaybackSessions.hasDumpPermission(context)
        val ownPid = Process.myPid()
        val ownUid = Process.myUid()
        var players: List<PlaybackSession>? = null
        var playersError: String? = null
        var af: AfSnapshot? = null
        var afError: String? = null
        val publicActive = publicActiveCount(context)
        // The audio-server report is bigger and holds a global lock while it is written, so it is read only
        // while Android says something is playing (or every 30 s, or whenever it has never worked).
        val needServer = perm && (publicActive == null || publicActive > 0 || now - lastServerReadMs > IDLE_SERVER_READ_MS ||
            lastServerOk != true || debugBlindServer)
        if (perm) {
            players = runCatching { PlaybackSessions.queryPlayers(context) }.getOrNull()
            if (players == null) playersError = PlaybackSessions.lastError ?: "unavailable"
        }
        if (needServer) {
            lastServerReadMs = now
            val read = runCatching { PlaybackSessions.readService("media.audio_flinger", 4_000L, 3 * 1024 * 1024, keepPartial = true) }
                .getOrElse { PlaybackSessions.ServiceRead(null, error = it.toString()) }
            if (read.text != null) {
                af = runCatching { AudioFlingerDump.parse(read.text, read.partial) }.getOrNull()
                if (af?.usable == true) lastAfExcerpt = AudioFlingerDump.excerpt(read.text) else { afError = "unrecognised audio-server report"; af = null }
            } else afError = read.error
            lastServerOk = af != null
            lastServerError = afError
        }
        if (perm) {
            if (debugBlindPlayers) { players = null; playersError = "test: player list blinded" }
            if (debugBlindServer) { af = null; afError = "test: audio-server report blinded" }
            if (needServer) lastAfSnapshot = af
        }
        val pm = context.packageManager
        val ledger = SessionLedger.merge(players, af, ownPid, ownUid) { uid ->
            runCatching { pm.getPackagesForUid(uid)?.firstOrNull() }.getOrNull()
        }
        val ownActive = when {
            players != null -> players.count { it.uid == ownUid && it.state == "started" }
            CaptureService.isRunning -> 1
            else -> 0
        }
        val verification = buildMap {
            EqController.globalEq.attachedSessions.forEach { sid -> put(sid, EffectVerifier.verify(sid, af, ownPid)) }
        }
        val (health, headline, advice) = DetectionStatus.assess(
            perm, players != null, if (needServer) af != null else lastServerOk == true, publicActive, ownActive, ledger.sessions, ledger.unresolved, verification,
        )
        val status = DetectionStatus(
            atMs = now, dumpPermission = perm, serviceRunning = SystemEqService.isRunning,
            playersOk = players != null, playersError = playersError,
            serverOk = if (needServer) af != null else lastServerOk == true,
            serverError = if (needServer) afError else lastServerError, serverPartial = af?.partial == true,
            publicActive = publicActive, sessions = ledger.sessions, unresolved = ledger.unresolved,
            verification = verification, health = health, headline = headline, advice = advice,
        )
        mutableStatus.value = status
        val summary = "players=${if (players != null) "ok" else "fail"} server=${if (af != null) "ok" else "fail"} public=$publicActive " +
            "sessions=[" + ledger.sessions.joinToString { "${it.session.packageName}#${it.session.sessionId}:${it.session.state}:${it.source}:${it.pathLabel.ifEmpty { "?" }}:${verification[it.session.sessionId] ?: "-"}" } + "] " +
            "unresolved=${ledger.unresolved.size} health=$health"
        if (summary != lastSummary) { lastSummary = summary; EqController.log("detect: $summary") }
        val complete = perm && players != null && af != null
        if (complete) lastCompleteScanMs = now
        return Outcome(ledger, complete, af, status, serverRead = needServer && af != null)
    }
}
