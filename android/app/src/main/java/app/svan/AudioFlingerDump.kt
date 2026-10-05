package app.svan

/** One AudioFlinger thread (an output mixer, direct output, offload, MMAP stream…). */
data class AfThread(
    val name: String,
    /** MIXER, DIRECT, OFFLOAD, MMAP_PLAYBACK, BIT_PERFECT, SPATIALIZER, DUPLICATING, RECORD… */
    val type: String,
    val output: Boolean,
    val devices: String = "",
    val streamFlags: String = "",
) {
    /** True when Android lets per-session effects (and our EQ) run on this path. */
    val supportsSessionEffects: Boolean get() = type == "MIXER" || type == "SPATIALIZER" || type == "DUPLICATING"
}

/** A playing/idle client track as the audio server sees it. */
data class AfTrack(
    val thread: AfThread,
    val pid: Int,
    val sessionId: Int,
    val active: Boolean,
    /** audio_usage_t, null when the row did not carry it (older releases, MMAP rows without it). */
    val usage: Int?,
    val patch: Boolean = false,
)

/** An effect instance on a session; [threadName] is null for an orphan chain (no live track yet). */
data class AfEffect(
    val sessionId: Int,
    val threadName: String?,
    val name: String,
    val typeUuid: String,
    val enabled: Boolean,
    val suspended: Boolean,
    val clientPids: List<Int>,
) {
    val isDynamicsProcessing: Boolean
        get() = typeUuid.equals(DP_TYPE_UUID, ignoreCase = true) || name.contains("Dynamics", ignoreCase = true)

    companion object { const val DP_TYPE_UUID = "7261676f-6d75-7369-6364-28e2fd3aac22" }
}

/** `Global session refs`: who owns each audio session (package name only on newer releases). */
data class AfSessionRef(val sessionId: Int, val pid: Int, val uid: Int, val packageName: String)

data class AfSnapshot(
    val threads: List<AfThread>,
    val tracks: List<AfTrack>,
    val effects: List<AfEffect>,
    val refs: List<AfSessionRef>,
    /** True when the output was cut short (timeout/size) but the thread sections were still read. */
    val partial: Boolean = false,
) {
    val usable: Boolean get() = threads.isNotEmpty() || refs.isNotEmpty()
}

/**
 * Parser for `dumpsys media.audio_flinger` (AOSP 10–15 layouts; verified against the android14-release
 * sources). Works from the audio server's own books, so it still sees players whose entry in the
 * AudioService playback list is missing, mislabelled, or has no session id (native / AAudio / OpenSL).
 * Every section is optional and matched tolerantly: an OEM that changes one table keeps the others.
 */
object AudioFlingerDump {
    private val THREAD = Regex("""^\s*(Output|Input)\s+thread\b(.*)$""")
    private val THREAD_NAME = Regex("""\bname\s+(\S+?)\s*[,:]""")
    private val THREAD_TYPE = Regex("""\btype\s+\d+\s*\(([A-Za-z_]+)\)""")
    private val DEVICES = Regex("""^\s*Output devices:\s*(.*)$""")
    private val STREAM_OUT = Regex("""^\s*AudioStreamOut:.*\bflags\s+(\S+)\s*(\(.*\))?""")
    private val EFFECT_CHAIN = Regex("""^\s*(\d+)\s+effects\s+for\s+session\s+(-?\d+)""")
    private val EFFECT_ID = Regex("""^\s*Effect ID\s+\d+:""")
    private val EFFECT_STATE_HEADER = Regex("""Session\s+State\s+Registered\s+Enabled\s+Suspended""")
    private val EFFECT_TYPE = Regex("""^\s*-\s*TYPE:\s*(\S+)""")
    private val EFFECT_NAME = Regex("""^\s*-\s*name:\s*(.*?)\s*$""")
    private val CLIENT_HEADER = Regex("""^\s*Pid\s+Priority\s+Ctrl""")
    private val CLIENT_ROW = Regex("""^\s+(\d+)\s+(-?\d+)\s+(yes|no)\b""")
    private val SESSION_REFS_HEADER = Regex("""^\s*session\s+cnt\s+pid""", RegexOption.IGNORE_CASE)
    private val SESSION_REFS_OLD = Regex("""^\s*session\s+pid\s+c(?:ou)?nt""", RegexOption.IGNORE_CASE)
    private val ORPHAN = Regex("""^\s*Orphan Effect Chains""")
    private val INT = Regex("""^-?\d+$""")
    private val HEX = Regex("""^[0-9A-Fa-f]+$""")

    fun parse(dump: String, partial: Boolean = false): AfSnapshot {
        val threads = mutableListOf<AfThread>()
        val tracks = mutableListOf<AfTrack>()
        val effects = mutableListOf<AfEffect>()
        val refs = mutableListOf<AfSessionRef>()

        var threadDevices = ""
        var threadFlags = ""
        var threadName = ""
        var threadType = ""
        var threadOutput = true
        var threadEmitted = false
        var inOrphans = false

        // track table
        var trackMode = TrackMode.NONE

        // effect chain state
        var chainSession = -1
        var fx: EffectBuilder? = null
        var inClients = false
        var section = Section.NONE

        fun currentThread(): AfThread {
            val t = AfThread(threadName.ifEmpty { "?" }, threadType.ifEmpty { "unknown" }, threadOutput, threadDevices, threadFlags)
            return t
        }
        fun emitThread() {
            if (!threadEmitted && threadType.isNotEmpty()) { threads.add(currentThread()); threadEmitted = true }
        }
        fun flushEffect() {
            fx?.let { b ->
                effects.add(AfEffect(b.session, if (inOrphans) null else threadName.ifEmpty { null }, b.name, b.type, b.enabled, b.suspended, b.pids))
            }
            fx = null
            inClients = false
        }

        for (raw in dump.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isEmpty()) {
                if (trackMode != TrackMode.NONE) trackMode = TrackMode.NONE
                continue
            }

            val th = THREAD.find(line)
            if (th != null) {
                flushEffect()
                emitThread()
                threadOutput = th.groupValues[1] == "Output"
                threadName = THREAD_NAME.find(th.groupValues[2])?.groupValues?.get(1) ?: ""
                threadType = THREAD_TYPE.find(th.groupValues[2])?.groupValues?.get(1) ?: if (threadOutput) "MIXER" else "RECORD"
                threadDevices = ""; threadFlags = ""; threadEmitted = false; inOrphans = false
                trackMode = TrackMode.NONE; section = Section.NONE
                continue
            }

            if (ORPHAN.containsMatchIn(line)) {
                flushEffect(); emitThread(); inOrphans = true; trackMode = TrackMode.NONE; section = Section.NONE; continue
            }

            val dev = DEVICES.find(line)
            if (dev != null) { threadDevices = dev.groupValues[1].trim(); continue }
            val out = STREAM_OUT.find(line)
            if (out != null) { threadFlags = (out.groupValues[1] + " " + out.groupValues[2]).trim(); continue }

            // ---- Global session refs (outside any thread) ----
            if (SESSION_REFS_HEADER.containsMatchIn(line)) { section = Section.REFS_NEW; continue }
            if (SESSION_REFS_OLD.containsMatchIn(line)) { section = Section.REFS_OLD; continue }
            if (section == Section.REFS_NEW || section == Section.REFS_OLD) {
                val t = line.trim().split(Regex("""\s+"""))
                val ok = t.size >= 3 && t.take(3).all { INT.matches(it) }
                if (!ok) { section = Section.NONE } else {
                    if (section == Section.REFS_NEW) {
                        // session cnt pid [uid [name…]]
                        refs.add(AfSessionRef(t[0].toInt(), t[2].toInt(), t.getOrNull(3)?.toIntOrNull() ?: -1, t.drop(4).joinToString(" ")))
                    } else refs.add(AfSessionRef(t[0].toInt(), t[1].toInt(), -1, ""))
                    continue
                }
            }

            // ---- track tables ----
            if (line.contains("Active") && line.contains("Client") && line.contains("Session")) {
                trackMode = if (line.contains("Port Id")) TrackMode.PLAYBACK_PORT else TrackMode.PLAYBACK; emitThread(); continue
            }
            if (line.trimStart().startsWith("Client") && line.contains("Session") && !line.contains("Active")) {
                trackMode = TrackMode.MMAP; emitThread(); continue
            }
            if (trackMode != TrackMode.NONE && threadOutput) {
                val row = parseTrackRow(line, trackMode, currentThread())
                if (row != null) { tracks.add(row); continue }
            }

            // ---- effect chains ----
            val chain = EFFECT_CHAIN.find(line)
            if (chain != null) {
                flushEffect(); chainSession = chain.groupValues[2].toInt(); trackMode = TrackMode.NONE; emitThread(); continue
            }
            if (EFFECT_ID.containsMatchIn(line)) { flushEffect(); fx = EffectBuilder(chainSession); continue }
            val b = fx
            if (b != null) {
                if (EFFECT_STATE_HEADER.containsMatchIn(line)) { b.expectState = true; continue }
                if (b.expectState) {
                    val t = line.trim().split(Regex("""\s+"""))
                    // session state registered enabled suspended
                    if (t.size >= 5) {
                        b.session = t[0].toIntOrNull() ?: b.session
                        b.enabled = t[3] == "y"
                        b.suspended = t[4] == "y"
                    }
                    b.expectState = false; continue
                }
                val type = EFFECT_TYPE.find(line)
                if (type != null) { b.type = type.groupValues[1]; continue }
                val name = EFFECT_NAME.find(line)
                if (name != null) { b.name = name.groupValues[1]; continue }
                if (CLIENT_HEADER.containsMatchIn(line)) { inClients = true; continue }
                if (inClients) {
                    val client = CLIENT_ROW.find(line)
                    if (client != null) { b.pids.add(client.groupValues[1].toInt()); continue }
                }
            }
        }
        flushEffect()
        emitThread()
        return AfSnapshot(threads, tracks, effects, refs, partial)
    }

    /**
     * The few dozen lines of a (huge) report that explain routing: thread headers, output devices,
     * track tables, effect chains and session owners. Used by the shareable diagnostic report so a
     * format difference on a given phone can be fixed without guessing.
     */
    fun excerpt(dump: String, maxLines: Int = 260): String {
        val out = mutableListOf<String>()
        var rows = false
        var refs = false
        var stateRow = false
        var clients = false
        for (raw in dump.lineSequence()) {
            if (out.size >= maxLines) break
            val l = raw.trimEnd()
            if (l.isEmpty()) { rows = false; refs = false; stateRow = false; clients = false; continue }
            val header = l.contains("Active") && l.contains("Client") && l.contains("Session")
            val take = when {
                THREAD.containsMatchIn(l) || DEVICES.containsMatchIn(l) || STREAM_OUT.containsMatchIn(l) ||
                    ORPHAN.containsMatchIn(l) || EFFECT_CHAIN.containsMatchIn(l) || EFFECT_ID.containsMatchIn(l) ||
                    EFFECT_TYPE.containsMatchIn(l) || EFFECT_NAME.containsMatchIn(l) -> true
                header -> { rows = true; true }
                SESSION_REFS_HEADER.containsMatchIn(l) || SESSION_REFS_OLD.containsMatchIn(l) -> { refs = true; true }
                EFFECT_STATE_HEADER.containsMatchIn(l) -> { stateRow = true; true }
                CLIENT_HEADER.containsMatchIn(l) -> { clients = true; true }
                stateRow -> { stateRow = false; true }
                rows || refs || clients -> true
                else -> false
            }
            if (take) out.add(l.take(220))
        }
        return out.joinToString("\n")
    }

    private enum class TrackMode { NONE, PLAYBACK, PLAYBACK_PORT, MMAP }
    private enum class Section { NONE, REFS_NEW, REFS_OLD }

    private class EffectBuilder(var session: Int) {
        var name = ""; var type = ""; var enabled = false; var suspended = false; var expectState = false
        val pids = mutableListOf<Int>()
    }

    private fun parseTrackRow(line: String, mode: TrackMode, thread: AfThread): AfTrack? {
        val t = line.trim().split(Regex("""\s+"""))
        if (mode == TrackMode.MMAP) {
            // Client Session Port Id Format ChnMask SRate Flags Usg CT
            if (t.size < 4 || !INT.matches(t[0]) || !INT.matches(t[1])) return null
            val usage = t.getOrNull(7)?.takeIf { HEX.matches(it) }?.toIntOrNull(16)
            return AfTrack(thread, t[0].toInt(), t[1].toInt(), true, usage)
        }
        // [F<n>] [S|P] Id Active Client Session [Port] State Flags Format ChnMask SRate ST Usg CT …
        val a = t.indexOfFirst { it == "yes" || it == "no" }
        if (a < 1) return null
        val need = if (mode == TrackMode.PLAYBACK_PORT) 3 else 2
        if (t.size < a + 1 + need) return null
        val ints = t.subList(a + 1, a + 1 + need)
        if (!ints.all { INT.matches(it) }) return null
        val patch = t.subList(0, a).any { it == "P" }
        // After pid, session[, port] comes the state token(s), then 0x flags, format, mask, rate, ST, Usg, CT
        val flagsIdx = t.indexOfFirst { it.startsWith("0x") && it.length >= 3 }
        var usage: Int? = null
        if (flagsIdx > 0 && t.size > flagsIdx + 5) usage = t[flagsIdx + 5].takeIf { HEX.matches(it) }?.toIntOrNull(16)
        return AfTrack(thread, ints[0].toInt(), ints[1].toInt(), t[a] == "yes", usage, patch)
    }
}
