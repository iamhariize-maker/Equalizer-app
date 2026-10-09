package app.svan.diag

/**
 * Pure text extraction from Android's own audio reports. Everything here is tolerant: OEM builds reorder
 * and reword these reports, so the raw lines are always kept next to anything that was interpreted.
 */
object Excerpt {
    /** A token as a whole number: "42225" must not match inside "142225" or "42225001". */
    fun numberToken(n: Int): Regex = Regex("""(?<![0-9])$n(?![0-9])""")

    /** Blocks of [before] lines, the match, and [after] lines, for the first [maxBlocks] matches. */
    fun around(text: String, matcher: Regex, before: Int, after: Int, maxBlocks: Int, maxChars: Int = 6_000): List<String> {
        val lines = text.lines()
        val out = mutableListOf<String>()
        var total = 0
        var i = 0
        while (i < lines.size && out.size < maxBlocks) {
            if (matcher.containsMatchIn(lines[i])) {
                val from = (i - before).coerceAtLeast(0)
                val to = (i + after).coerceAtMost(lines.lastIndex)
                val block = lines.subList(from, to + 1).joinToString("\n") { it.trimEnd() }.take(maxChars)
                total += block.length
                out += block
                if (total >= maxChars * 2) break
                i = to + 1
            } else i++
        }
        return out
    }

    /** Lines mentioning any of [tokens] as whole numbers, capped. */
    fun linesWithNumbers(text: String, numbers: Collection<Int>, maxLines: Int = 60): List<String> {
        val res = numbers.filter { it >= 0 }.map { numberToken(it) }
        if (res.isEmpty()) return emptyList()
        return text.lineSequence().filter { line -> res.any { it.containsMatchIn(line) } }
            .map { it.trimEnd().take(260) }.take(maxLines).toList()
    }
}

/** `dumpsys media.audio_policy`: per-track clients as the policy itself sees them. */
object PolicyDump {
    data class Client(
        val portId: Int,
        val sessionId: Int,
        val uid: Int,
        val active: Boolean,
        val formatLine: String?,
        /** The policy's effective attributes line: includes native-set and UID-wide capture flags. */
        val attributesLine: String?,
        val usage: String?,
        /** Attribute flags (FLAG_NO_MEDIA_PROJECTION is 0x400, FLAG_NO_SYSTEM_CAPTURE is 0x1000). Null when unreadable. */
        val attributeFlags: Int?,
        val streamLine: String?,
        /** Output thread/handle ids this track is also being copied to (capture mixes). Empty = not attached to any mix. */
        val secondaryOutputs: List<Int>,
        val hasSecondaryLine: Boolean,
        val raw: List<String>,
    ) {
        val blocksMediaProjection: Boolean get() = attributeFlags?.let { it and FLAG_NO_MEDIA_PROJECTION != 0 } == true
        val blocksSystemCapture: Boolean get() = attributeFlags?.let { it and FLAG_NO_SYSTEM_CAPTURE != 0 } == true
        val attachedToCaptureMix: Boolean get() = secondaryOutputs.isNotEmpty()
    }

    const val FLAG_NO_MEDIA_PROJECTION = 1 shl 10
    const val FLAG_NO_SYSTEM_CAPTURE = 1 shl 12

    private val header = Regex("""Port ID:\s*(-?\d+);\s*Session ID:\s*(-?\d+);\s*uid\s*(-?\d+);\s*State:\s*(\w+)""")
    private val usageRe = Regex("""[Uu]sage:?\s*([A-Za-z_0-9]+)""")
    private val flagsRe = Regex("""[Ff]lags:?\s*[=:]?\s*(0[xX][0-9a-fA-F]+|[0-9]+)""")
    private val streamRe = Regex("""Stream:\s*(\d+);\s*Flags:\s*([0-9a-fA-F]+)""")
    private val secondaryRe = Regex("""DAP Secondary Outputs:\s*(.*)""")

    /** Every track client in the report, in order. */
    fun clients(text: String): List<Client> {
        val lines = text.lines()
        val out = mutableListOf<Client>()
        var i = 0
        while (i < lines.size) {
            val m = header.find(lines[i])
            if (m == null) { i++; continue }
            val block = mutableListOf(lines[i].trimEnd())
            var j = i + 1
            while (j < lines.size && block.size < 9 && lines[j].isNotBlank() && !header.containsMatchIn(lines[j])) {
                block += lines[j].trimEnd(); j++
            }
            out += build(m.groupValues, block)
            i = j
        }
        return out
    }

    fun forUid(text: String, uid: Int): List<Client> = clients(text).filter { it.uid == uid }

    private fun build(g: List<String>, block: List<String>): Client {
        val attrLine = block.firstOrNull { it.contains("Attributes:") }
        val flags = attrLine?.let { flagsRe.find(it.substringAfter("Attributes:"))?.groupValues?.get(1) }?.let {
            if (it.startsWith("0x", true)) it.drop(2).toLongOrNull(16) else it.toLongOrNull()
        }?.takeIf { it in 0..0xffffffffL }?.toInt()
        val secondaryLine = block.firstOrNull { it.contains("DAP Secondary Outputs") }
        val secondary = secondaryLine?.let { secondaryRe.find(it)?.groupValues?.get(1) }
            ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.filter { it > 0 }.orEmpty()
        return Client(
            portId = g[1].toInt(), sessionId = g[2].toInt(), uid = g[3].toInt(), active = g[4].equals("Active", true),
            formatLine = block.getOrNull(1)?.trim(),
            attributesLine = attrLine?.trim(),
            usage = attrLine?.let { usageRe.find(it)?.groupValues?.get(1) },
            attributeFlags = flags,
            streamLine = block.firstOrNull { streamRe.containsMatchIn(it) }?.trim(),
            secondaryOutputs = secondary,
            hasSecondaryLine = secondaryLine != null,
            raw = block,
        )
    }

    /** Registered dynamic policy mixes (capture mixes appear here while a capture recorder exists). */
    fun mixSections(text: String): List<String> =
        Excerpt.around(text, Regex("""(?i)policy\s*mix"""), before = 0, after = 18, maxBlocks = 3, maxChars = 3_500)

    /**
     * The capture-blocking bits in the policy's *effective* attributes for [uid]'s track(s), or null when none block.
     * The audio server merges native-set and UID-wide flags into these, so they can block capture while the Java
     * player list still shows 0x0. [sessionId] 0 means any of the uid's tracks; otherwise only that session's.
     */
    fun blockingMask(clients: List<Client>, uid: Int, sessionId: Int): Int? {
        val bits = FLAG_NO_MEDIA_PROJECTION or FLAG_NO_SYSTEM_CAPTURE
        val mask = clients.filter { it.uid == uid && (sessionId == 0 || it.sessionId == sessionId) }
            .fold(0) { acc, c -> acc or ((c.attributeFlags ?: 0) and bits) }
        return mask.takeIf { it != 0 }
    }

    /** The UID-wide capture policy table, raw. */
    fun capturePolicyTable(text: String): List<String> =
        Excerpt.around(text, Regex("""AllowedCapturePolicies"""), before = 0, after = 12, maxBlocks = 1, maxChars = 1_500)
}

/** `dumpsys media.audio_flinger`: where a session's tracks run and which effects are on it. */
object FlingerView {
    private val threadHeader = Regex("""(?i)^\s*(Output|Playback|Mmap playback|Direct|Offload|Duplicating)\b.*thread\b|^\s*(Output|Input) thread""")

    /**
     * Track rows, thread headers and effect chains that mention [sessionId], plus any patch/submix lines
     * (capture taps show up there). Bounded: the full report can be several megabytes.
     */
    fun forSession(text: String, sessionId: Int, maxLines: Int = 90): List<String> {
        val token = Excerpt.numberToken(sessionId)
        val effectRe = Regex("""(?i)effects? for session\s+0*$sessionId(?![0-9])""")
        val out = mutableListOf<String>()
        var header: String? = null
        var headerEmitted = false
        var effectWindow = 0 // lines of an effect chain still to include after its "N effects for session S" line
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            if (threadHeader.containsMatchIn(line)) { header = line.trim().take(200); headerEmitted = false; effectWindow = 0 }
            val effectStart = effectRe.containsMatchIn(line)
            if (effectStart) effectWindow = 24
            if (token.containsMatchIn(line) || effectWindow > 0) {
                if (!headerEmitted && header != null) { out += "[thread] $header"; headerEmitted = true }
                out += line.take(260)
                if (effectWindow > 0 && !effectStart) effectWindow--
            }
            if (out.size >= maxLines) break
        }
        return out
    }

    /** Lines that name patch tracks/records or the remote submix: evidence of a capture tap on the audio server. */
    fun captureTaps(text: String, maxLines: Int = 24): List<String> =
        text.lineSequence().filter { Regex("""(?i)patch(track|record)|remote.?submix|submix""").containsMatchIn(it) }
            .map { it.trimEnd().take(240) }.take(maxLines).toList()
}

/** `dumpsys audio` playback records for one app. */
object PlaybackRecords {
    fun forUid(lines: List<String>, uid: Int): List<String> {
        val a = Regex("""u/pid\s*:\s*$uid/""")
        val b = Regex("""(?i)(client)?uid\s*[:=]\s*$uid(?![0-9])""")
        return lines.filter { a.containsMatchIn(it) || b.containsMatchIn(it) }.map { it.take(420) }
    }
}
