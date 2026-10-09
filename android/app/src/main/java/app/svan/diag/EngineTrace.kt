package app.svan.diag

import java.io.File
import java.util.concurrent.Executors

/**
 * Flight recorder for the live engines: what the router decided, what the capture checks found, when the
 * recorder opened, when the first frame and the first sound arrived, how the output behaved every two seconds,
 * and every fail-open. The Hi-Fi log tail is overwritten after minutes; this keeps a classified, timestamped
 * history that needs no enhanced reports, so the same record exists on a phone without DUMP or Shizuku.
 *
 * It holds package names, session ids and numbers only. The audio loop calls [mark] and [sample] a handful of
 * times per run and every two seconds, never per block. File writes happen on a separate thread.
 */
object EngineTrace {
    enum class Cat { LIFECYCLE, ROUTE, PROBE, RECORDER, OUTPUT, FAILOPEN, MUTE, DETECT }

    /** One-time milestones of a capture run, in the order a healthy run reaches them. */
    enum class Mark { FOREGROUND, PROJECTION, ROUTING_DONE, RATE_NEGOTIATED, RECORDER_STARTED, FIRST_FRAME, FIRST_AUDIO, FIRST_OUTPUT, STOPPED }

    data class Event(val atMs: Long, val cat: Cat, val pkg: String?, val text: String)

    /** One two-second window of the running engine, taken on the audio thread from values it already keeps. */
    data class Sample(
        val atMs: Long,
        val epochId: Long?,
        val rateHz: Int,
        val sourceState: String,
        val capturedFrames: Long,
        val generatedFrames: Long,
        val inputPeakDb: Double,
        val outputPeakDb: Double,
        val queuedMs: Double,
        val underruns: Int,
        val dspPercent: Double,
        val overBudgetBlocks: Int,
        val readWaitMaxMs: Double,
        val writeWaitMaxMs: Double,
        /** Android's own flag for "the recorder is being silenced"; null when it could not be read. */
        val clientSilenced: Boolean?,
        val otherPlayers: Int,
        val mutedPackages: List<String>,
    )

    /** What a previous process left behind: used to tell a clean stop from the system killing Svan. */
    data class PreviousRun(val savedAtMs: Long, val cleanStop: Boolean, val events: List<Event>)

    private const val MAX_EVENTS = 500
    private const val MAX_SAMPLES = 120
    private const val SAVED_EVENTS = 80
    private val events = ArrayDeque<Event>()
    private val samples = ArrayDeque<Sample>()
    private val marks = java.util.EnumMap<Mark, Long>(Mark::class.java)
    @Volatile var runStartedMs: Long? = null
        private set
    @Volatile var runCount = 0
        private set
    @Volatile var restarts = 0
        private set
    @Volatile var previousRun: PreviousRun? = null
        private set

    private var file: File? = null
    private var lastSaveMs = 0L
    private val writer by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "svan-trace-save").apply { isDaemon = true } } }

    // ---- recording ----------------------------------------------------------------------------------------

    @Synchronized
    fun record(cat: Cat, text: String, pkg: String? = null, nowMs: Long = System.currentTimeMillis()) {
        val t = text.take(260)
        val last = events.lastOrNull()
        if (last != null && last.cat == cat && last.text == t && last.pkg == pkg && nowMs - last.atMs < 2_000) return
        events.addLast(Event(nowMs, cat, pkg, t))
        while (events.size > MAX_EVENTS) events.removeFirst()
        saveSoon(nowMs, clean = false)
    }

    /** Feeds every `EqController.log` line in, classified. Lines that are periodic noise are not kept. */
    fun fromLog(line: String, nowMs: Long = System.currentTimeMillis()) {
        val c = LogClassifier.classify(line) ?: return
        record(c.cat, line, c.pkg, nowMs)
    }

    @Synchronized
    fun beginRun(nowMs: Long = System.currentTimeMillis()) {
        marks.clear()
        runStartedMs = nowMs
        runCount++
        restarts = 0
        record(Cat.LIFECYCLE, "capture run $runCount requested", nowMs = nowMs)
    }

    /** First call per run wins, so a milestone keeps the time it was first reached. */
    @Synchronized
    fun mark(mark: Mark, detail: String? = null, nowMs: Long = System.currentTimeMillis()) {
        if (marks.containsKey(mark)) return
        marks[mark] = nowMs
        record(Cat.LIFECYCLE, "milestone ${mark.name}" + (detail?.let { ": $it" } ?: ""), nowMs = nowMs)
        if (mark == Mark.STOPPED) saveSoon(nowMs, clean = true, force = true)
    }

    fun restarted(why: String, nowMs: Long = System.currentTimeMillis()) {
        restarts++
        record(Cat.LIFECYCLE, "capture engine reopening (restart $restarts): ${why.ifBlank { "no reason given" }}", nowMs = nowMs)
    }

    @Synchronized
    fun sample(s: Sample) {
        samples.addLast(s)
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
    }

    // ---- reading ------------------------------------------------------------------------------------------

    @Synchronized fun events(): List<Event> = events.toList()
    @Synchronized fun samples(): List<Sample> = samples.toList()
    @Synchronized fun marks(): Map<Mark, Long> = HashMap(marks)

    @Synchronized
    fun clearForTest() {
        events.clear(); samples.clear(); marks.clear()
        runStartedMs = null; runCount = 0; restarts = 0; previousRun = null; file = null
    }

    // ---- surviving a process death ------------------------------------------------------------------------

    /**
     * Reads what the previous process saved (once) and starts saving this one. A file whose last line says the
     * run did not stop cleanly means the process ended without Svan's own shutdown: the system killed it.
     */
    @Synchronized
    fun attachStorage(dir: File) {
        if (file != null) return
        val f = File(dir, "engine-trace.txt")
        file = f
        previousRun = runCatching { parseSaved(f.readLines()) }.getOrNull()
    }

    @Synchronized
    private fun saveSoon(nowMs: Long, clean: Boolean, force: Boolean = false) {
        val target = file ?: return
        if (!force && nowMs - lastSaveMs < 10_000) return
        lastSaveMs = nowMs
        val text = render(events.takeLast(SAVED_EVENTS), nowMs, clean)
        writer.execute { runCatching { target.parentFile?.mkdirs(); target.writeText(text) } }
    }

    internal fun render(list: List<Event>, savedAtMs: Long, clean: Boolean): String = buildString {
        appendLine("saved=$savedAtMs clean=$clean")
        list.forEach { appendLine("${it.atMs}|${it.cat.name}|${it.pkg ?: ""}|${it.text.replace('\n', ' ')}") }
    }

    internal fun parseSaved(lines: List<String>): PreviousRun? {
        val head = lines.firstOrNull() ?: return null
        val saved = Regex("saved=(\\d+) clean=(true|false)").matchEntire(head) ?: return null
        val parsed = lines.drop(1).mapNotNull { l ->
            val p = l.split('|', limit = 4)
            if (p.size < 4) return@mapNotNull null
            val cat = Cat.entries.firstOrNull { it.name == p[1] } ?: return@mapNotNull null
            Event(p[0].toLongOrNull() ?: return@mapNotNull null, cat, p[2].ifEmpty { null }, p[3])
        }
        return PreviousRun(saved.groupValues[1].toLong(), saved.groupValues[2] == "true", parsed)
    }
}

/** Sorts Svan's own log lines into trace categories and pulls out the package they are about. */
internal object LogClassifier {
    data class Classified(val cat: EngineTrace.Cat, val pkg: String?)

    private val pkgToken = Regex("\\b([a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*){2,})\\b")

    fun classify(line: String): Classified? {
        val l = line.trim()
        val cat = when {
            // Periodic 2-second lines are carried by structured samples instead.
            l.startsWith("capture level:") || l.startsWith("capture timing:") -> return null
            l.startsWith("fail-open:") || l.contains("failing open") -> EngineTrace.Cat.FAILOPEN
            l.startsWith("lost mute") -> EngineTrace.Cat.MUTE
            l.startsWith("capture check") || l.startsWith("DIAG") -> EngineTrace.Cat.PROBE
            l.startsWith("route:") || (l.startsWith("capture:") && l.contains("→ Engine A")) -> EngineTrace.Cat.ROUTE
            l.startsWith("capture filter") || l.startsWith("capture: rate") || l.startsWith("capture: read failed") ||
                l.startsWith("capture: started") -> EngineTrace.Cat.RECORDER
            l.contains("output write failed") || l.contains("underrun") -> EngineTrace.Cat.OUTPUT
            l.startsWith("capture:") || l.startsWith("shared output") || l.startsWith("diagnostic:") -> EngineTrace.Cat.LIFECYCLE
            l.startsWith("sync:") || l.startsWith("verify:") || l.startsWith("system effects:") || l.startsWith("CLOSE ignored") ||
                l.startsWith("scan") -> EngineTrace.Cat.DETECT
            else -> return null
        }
        return Classified(cat, pkgToken.find(l)?.groupValues?.get(1))
    }
}
