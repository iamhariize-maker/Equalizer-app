package app.svan

/** Where Svan learned about a session. More than one source = cross-checked. */
enum class SessionSource { AUDIO_SERVICE, AUDIO_SERVER, BOTH }

/** One player session with everything Svan could establish about it. */
data class LedgerSession(
    val session: PlaybackSession,
    val source: SessionSource,
    val pid: Int,
    /** AudioFlinger thread types carrying this session's tracks (MIXER, DIRECT, OFFLOAD, MMAP_PLAYBACK…). */
    val threadTypes: Set<String> = emptySet(),
    val devices: String = "",
    val streamFlags: String = "",
    val serverActive: Boolean? = null,
) {
    /** null = unknown (audio server tables unavailable); false = every track is on a path that bypasses session effects. */
    val effectsPossible: Boolean?
        get() = if (threadTypes.isEmpty()) null else threadTypes.any { it in EFFECT_PATHS }

    val pathLabel: String
        get() = when {
            threadTypes.isEmpty() -> ""
            threadTypes.any { it in EFFECT_PATHS } -> "mixer"
            "OFFLOAD" in threadTypes -> "offload"
            "BIT_PERFECT" in threadTypes -> "bit-perfect"
            "DIRECT" in threadTypes -> "direct"
            "MMAP_PLAYBACK" in threadTypes -> "exclusive/MMAP"
            else -> threadTypes.first().lowercase()
        }

    private companion object { val EFFECT_PATHS = setOf("MIXER", "SPATIALIZER", "DUPLICATING") }
}

data class Ledger(
    val sessions: List<LedgerSession>,
    /** Real app players the audio service lists that no session could be found for. */
    val unresolved: List<PlaybackSession>,
)

/**
 * Fuses every source of "who is playing, on which audio session" into one list:
 *  1. AudioService player records (uid/pid/state/usage, sometimes no session id);
 *  2. AudioFlinger's tracks and `Global session refs` (session → pid/uid/package; the audio server's
 *     own books, which also expose native players and players whose records are missing or odd).
 * Either source alone is enough to keep detection working, which is the point.
 */
object SessionLedger {
    /** audio_usage_t values a music/game player uses: UNKNOWN, MEDIA, GAME. */
    private val MEDIA_USAGES = setOf(0, 1, 14)
    private const val FIRST_APP_UID = 10_000

    fun merge(
        players: List<PlaybackSession>?,
        af: AfSnapshot?,
        ownPid: Int,
        ownUid: Int,
        packageForUid: (Int) -> String? = { null },
    ): Ledger {
        class Draft(val sid: Int) {
            var apc: PlaybackSession? = null
            val tracks = mutableListOf<AfTrack>()
        }
        val drafts = LinkedHashMap<Int, Draft>()
        fun draft(sid: Int) = drafts.getOrPut(sid) { Draft(sid) }
        val afTracks = af?.tracks.orEmpty().filter { !it.patch && it.thread.output && it.sessionId > 0 && it.pid != ownPid }
        val unresolved = mutableListOf<PlaybackSession>()

        players.orEmpty().filter { it.uid != ownUid }.forEach { p ->
            if (p.sessionId > 0) {
                val d = draft(p.sessionId)
                // Several records for one session: keep a started one, never lose a capture opt-out.
                val cur = d.apc
                d.apc = if (cur == null) p else (if (cur.state != "started" && p.state == "started") p else cur)
                    .let { keep -> keep.copy(flags = keep.flags or (cur?.flags ?: 0) or p.flags) }
            } else {
                val sessions = if (p.pid > 0) afTracks.filter { it.pid == p.pid }.map { it.sessionId }.distinct() else emptyList()
                if (sessions.isEmpty()) unresolved += p
                else sessions.forEach { sid -> draft(sid).apc = p.copy(sessionId = sid) }
            }
        }

        val refBySession = af?.refs.orEmpty().groupBy { it.sessionId }
        val uidByPid = buildMap {
            players.orEmpty().forEach { if (it.pid > 0 && it.uid >= 0) put(it.pid, it.uid) }
            af?.refs.orEmpty().forEach { if (it.pid > 0 && it.uid >= 0) putIfAbsent(it.pid, it.uid) }
        }

        afTracks.forEach { t ->
            if (t.usage != null && t.usage !in MEDIA_USAGES) return@forEach
            val ref = refBySession[t.sessionId]?.firstOrNull { it.pid == t.pid } ?: refBySession[t.sessionId]?.firstOrNull()
            val uid = ref?.uid?.takeIf { it >= 0 } ?: uidByPid[t.pid] ?: -1
            if (uid == ownUid) return@forEach
            if (uid in 0 until FIRST_APP_UID) return@forEach // system services, not a music app
            val d = draft(t.sessionId)
            // An idle track with an unknown owner is noise; an active one, or a known app's, is a player.
            if (d.apc == null && !t.active && uid < 0) return@forEach
            d.tracks += t
        }

        val result = drafts.values.mapNotNull { d ->
            if (d.apc == null && d.tracks.isEmpty()) return@mapNotNull null
            val tracks = d.tracks
            val pid = d.apc?.pid?.takeIf { it > 0 } ?: tracks.firstOrNull()?.pid ?: -1
            val ref = refBySession[d.sid]?.firstOrNull { it.pid == pid } ?: refBySession[d.sid]?.firstOrNull()
            val activeOnServer = tracks.takeIf { it.isNotEmpty() }?.any { it.active }
            val session = d.apc?.let { apc ->
                var named = apc
                if (named.packageName.isEmpty() || named.packageName.startsWith("uid:")) {
                    val better = ref?.packageName?.takeIf { it.isNotBlank() && it != "?" } ?: packageForUid(named.uid)
                    if (better != null) named = named.copy(packageName = better)
                }
                // If the player list had no state we trust, the server's view of the track decides.
                if (named.state == "unknown" && activeOnServer != null) named = named.copy(state = if (activeOnServer) "started" else "paused")
                named
            } ?: run {
                val uid = ref?.uid?.takeIf { it >= 0 } ?: uidByPid[pid] ?: -1
                val usage = when (tracks.firstOrNull()?.usage) { 14 -> "USAGE_GAME"; 0 -> "USAGE_UNKNOWN"; else -> "USAGE_MEDIA" }
                val pkg = ref?.packageName?.takeIf { it.isNotBlank() && it != "?" } ?: packageForUid(uid) ?: if (uid >= 0) "uid:$uid" else "pid:$pid"
                PlaybackSession(d.sid, uid, usage, if (activeOnServer == true) "started" else "paused", 0, pkg, pid)
            }
            val source = when {
                d.apc != null && tracks.isNotEmpty() -> SessionSource.BOTH
                d.apc != null -> SessionSource.AUDIO_SERVICE
                else -> SessionSource.AUDIO_SERVER
            }
            LedgerSession(
                session = session,
                source = source,
                pid = pid,
                threadTypes = tracks.map { it.thread.type }.toSet(),
                devices = tracks.firstOrNull()?.thread?.devices.orEmpty(),
                streamFlags = tracks.firstOrNull()?.thread?.streamFlags.orEmpty(),
                serverActive = activeOnServer,
            )
        }
        return Ledger(result, unresolved)
    }
}

/** What Svan can prove about a session it attached system effects to. */
enum class Verification(val summary: String) {
    PROCESSING("Verified in Android's audio server"),
    SUSPENDED("Attached, but Android has suspended effects on this stream (often power-saving offload)"),
    BYPASSED("Attached, but this stream uses a direct/offload/exclusive output that bypasses effects"),
    WAITING("Attached; waiting for the player's audio track to start"),
    MISSING("Not found in the audio server; re-attaching"),
    UNKNOWN(""),
}

object EffectVerifier {
    /** [af] must come from a successful report. Returns UNKNOWN whenever the server's tables cannot prove anything. */
    fun verify(sessionId: Int, af: AfSnapshot?, ownPid: Int): Verification {
        if (af == null || !af.usable) return Verification.UNKNOWN
        val ours = af.effects.filter { it.isDynamicsProcessing && ownPid in it.clientPids }
        val mine = ours.filter { it.sessionId == sessionId }
        if (mine.isEmpty()) {
            val tracks = af.tracks.filter { it.sessionId == sessionId && !it.patch }
            if (tracks.isNotEmpty() && tracks.none { it.thread.supportsSessionEffects }) return Verification.BYPASSED
            // Only claim "missing" if the report is complete and the parser demonstrably sees this app's other effects.
            return if (!af.partial && ours.isNotEmpty() && af.threads.isNotEmpty()) Verification.MISSING else Verification.UNKNOWN
        }
        val fx = mine.first()
        if (fx.threadName == null) {
            val tracks = af.tracks.filter { it.sessionId == sessionId && !it.patch }
            return if (tracks.isNotEmpty() && tracks.none { it.thread.supportsSessionEffects }) Verification.BYPASSED else Verification.WAITING
        }
        val thread = af.threads.firstOrNull { it.name == fx.threadName }
        return when {
            thread != null && !thread.supportsSessionEffects -> Verification.BYPASSED
            fx.suspended || !fx.enabled -> Verification.SUSPENDED
            else -> Verification.PROCESSING
        }
    }
}

enum class Health { OK, IDLE, DEGRADED, BLIND, NO_PERMISSION }

/** One scan's complete outcome, rendered by the UI and the shareable diagnostic report. */
data class DetectionStatus(
    val atMs: Long = 0L,
    val dumpPermission: Boolean = false,
    val serviceRunning: Boolean = false,
    val playersOk: Boolean = false,
    val playersError: String? = null,
    val serverOk: Boolean = false,
    val serverError: String? = null,
    val serverPartial: Boolean = false,
    /** Public API: players Android says are active (anonymized; includes Svan's own output when Hi-Fi runs). */
    val publicActive: Int? = null,
    val sessions: List<LedgerSession> = emptyList(),
    val unresolved: List<PlaybackSession> = emptyList(),
    val verification: Map<Int, Verification> = emptyMap(),
    val health: Health = Health.IDLE,
    val headline: String = "Waiting for the first scan",
    val advice: String = "",
) {
    companion object {
        /** Pure so it can be unit-tested. [ownActive] = Svan's own audible players (Engine B output). */
        fun assess(
            dumpPermission: Boolean, playersOk: Boolean, serverOk: Boolean, publicActive: Int?, ownActive: Int,
            sessions: List<LedgerSession>, unresolved: List<PlaybackSession>, verification: Map<Int, Verification>,
        ): Triple<Health, String, String> {
            val other = publicActive?.let { (it - ownActive).coerceAtLeast(0) }
            val playing = sessions.filter { it.session.state == "started" || it.serverActive == true }
            if (!dumpPermission) {
                return if (other != null && other > 0) Triple(Health.NO_PERMISSION,
                    "Android reports $other player(s) playing, but Svan cannot see which",
                    "Finish the one-time Music detection setup below. Without it Svan only hears players that announce themselves.")
                else Triple(Health.NO_PERMISSION, "Music detection setup is not finished",
                    "Complete the one-time setup below so Svan can see every player.")
            }
            if (!playersOk && !serverOk) return Triple(Health.BLIND, "Android would not share the audio report",
                "Both audio reports failed this scan. Svan retries automatically; if this stays, share the diagnostic report.")
            if (playing.isNotEmpty()) {
                val bypass = playing.filter { it.effectsPossible == false }
                val missing = playing.filter { verification[it.session.sessionId] == Verification.MISSING || verification[it.session.sessionId] == Verification.SUSPENDED }
                return when {
                    bypass.isNotEmpty() -> Triple(Health.DEGRADED, "${labelOf(bypass.first())} is playing on a ${bypass.first().pathLabel} output",
                        "Android bypasses system effects on that kind of output. Switch the player's output to its standard Android/AudioTrack option, or turn off its hi-res/exclusive/bit-perfect/offload setting.")
                    missing.isNotEmpty() -> Triple(Health.DEGRADED, "${labelOf(missing.first())} is detected, but Android is not applying the effect",
                        "Svan is retrying. If it persists the player may use a power-saving offload path.")
                    else -> Triple(Health.OK, "Detected: " + playing.map { labelOf(it) }.distinct().joinToString(), "")
                }
            }
            if (other != null && other > 0) {
                val why = when {
                    unresolved.isNotEmpty() -> "A player is active but Android gave no session to attach to (native/AAudio output)."
                    !serverOk -> "The audio-server report was unavailable, so Svan relied on the player list only."
                    !playersOk -> "The player list was unavailable, so Svan relied on the audio server only."
                    else -> "Neither Android report lists a media session for it."
                }
                return Triple(Health.BLIND, "Android reports $other player(s) active, but Svan found no session to process", why)
            }
            return Triple(if (sessions.isEmpty()) Health.IDLE else Health.OK, if (sessions.isEmpty()) "Nothing is playing" else "Player(s) connected, paused", "")
        }

        private fun labelOf(s: LedgerSession) = s.session.packageName.substringAfterLast('.').ifBlank { s.session.packageName }
    }
}
