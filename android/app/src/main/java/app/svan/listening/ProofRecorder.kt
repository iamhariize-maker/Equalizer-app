package app.svan.listening

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.Locale
import java.util.Random
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Recording mode for the audiophile engine.
 *
 * Android's screen recorder cannot hear Engine B: Svan's own output opts out of
 * playback capture (it would feed back into the engine) and a second MediaProjection
 * competes with the recorder's. So Svan records itself. The capture loop hands this
 * object the exact buffer it read (dry) and the exact buffer it writes to the
 * AudioTrack (processed), block for block, so the two files are sample-aligned.
 *
 * Audio-thread contract: [offerDry] and [commitWet] never allocate, lock or block.
 * A writer thread drains a ring buffer to disk; if it ever falls behind, blocks are
 * dropped (in both streams equally) and counted in the report.
 */
object ProofRecorder {
    sealed class State {
        object Idle : State()
        data class Recording(val startedAtMs: Long) : State()
        object Finishing : State()
        data class Done(val result: Result) : State()
        data class Failed(val message: String) : State()
    }

    /** What one stretch of the recording measured, between two setting changes. */
    class Segment(
        val label: String, val settings: Map<String, Any?>, val startSeconds: Double, val seconds: Double,
        val dry: LevelMeter, val processed: LevelMeter, val bands: List<SpectrumBand>,
        val startFrame: Long, val endFrame: Long,
    ) {
        val rmsChangeDb = processed.rmsDbfs - dry.rmsDbfs
        val matchedGain = ProofWav.matchingGain(dry, processed)
    }

    class Result(
        val directory: File, val dryWav: File, val processedWav: File, val reportJson: File,
        val seconds: Double, val dry: LevelMeter, val processed: LevelMeter,
        val bands: List<SpectrumBand>, val droppedFrames: Long, val report: JSONObject,
        val segments: List<Segment> = emptyList(),
        val dryFromSyncWav: File? = null, val processedFromSyncWav: File? = null,
        val syncCueWav: File? = null, val matchedWav: File? = null,
        val abTimelineWav: File? = null, val abTimelineFromSyncWav: File? = null,
    )

    data class Options(val wavBits: Int = 24, val matchLevel: Boolean = false, val startWithSync: Boolean = false,
                       val abMatchLevel: Boolean = true)
    data class Sync(val frame: Long, val epochMs: Long)
    data class AbSwitch(val frame: Long, val isAfter: Boolean)

    private class Marker(val position: Long, val label: String, val settings: Map<String, Any?>)

    val state = MutableStateFlow<State>(State.Idle)
    val currentLabel = MutableStateFlow("Opening settings")
    val lastSync = MutableStateFlow<Sync?>(null)
    val lastAb = MutableStateFlow<AbSwitch?>(null)
    val recordedFrames: Long get() = session?.written?.div(2) ?: 0L
    val sampleRate: Int get() = session?.rate ?: 48000
    fun clockText(frame: Long = recordedFrames, rate: Int = sampleRate): String {
        val ms = frame * 1000 / rate
        return String.format(Locale.US, "%d:%02d.%03d", ms / 60000, ms / 1000 % 60, ms % 1000)
    }

    private const val RING_FLOATS = 48000 * 2 * 4   // four seconds of stereo
    private const val MAX_SECONDS = 600             // 10 min ≈ 173 MB per WAV
    const val MAX_SEGMENTS = 12

    private class Session(
        val dir: File, val rate: Int, val meta: Map<String, Any?>, val stats: () -> Map<String, Any?>?,
        val onFinished: (Result) -> Unit, val startedAtMs: Long, val options: Options,
    ) {
        val markers = ConcurrentLinkedQueue<Marker>()
        val syncs = ConcurrentLinkedQueue<Sync>()
        val abSwitches = ConcurrentLinkedQueue<AbSwitch>()
        val startedAtNanos = System.nanoTime()
        @Volatile var markerCount = 0
        @Volatile var ignoredMarkers = 0
        @Volatile var firstBlockNanos = 0L
        val dryRing = FloatArray(RING_FLOATS)
        val wetRing = FloatArray(RING_FLOATS)
        @Volatile var written = 0L      // floats committed by the audio thread
        @Volatile var consumed = 0L     // floats drained by the writer
        @Volatile var dropped = 0L      // floats dropped, both streams
        var pending = 0                 // audio thread only: floats offered but not yet committed
        val stop = AtomicBoolean(false)
    }

    @Volatile private var session: Session? = null

    // ---- audio thread -------------------------------------------------------------

    fun offerDry(input: FloatArray, samples: Int) {
        val s = session ?: return
        val n = samples - samples % 2
        if (n <= 0 || s.stop.get()) { s.pending = 0; return }
        if (RING_FLOATS - (s.written - s.consumed) < n) { s.dropped += n; s.pending = 0; return }
        if (s.firstBlockNanos == 0L) s.firstBlockNanos = System.nanoTime()
        copyIn(input, s.dryRing, (s.written % RING_FLOATS).toInt(), n)
        s.pending = n
    }

    /** Must follow [offerDry] for the same block, after the DSP has run on [output]. */
    fun commitWet(output: FloatArray, samples: Int) {
        val s = session ?: return
        val n = s.pending
        if (n <= 0) return
        copyIn(output, s.wetRing, (s.written % RING_FLOATS).toInt(), n)
        s.pending = 0
        s.written += n
    }

    private fun copyIn(src: FloatArray, ring: FloatArray, at: Int, n: Int) {
        val first = minOf(n, RING_FLOATS - at)
        System.arraycopy(src, 0, ring, at, first)
        if (first < n) System.arraycopy(src, first, ring, 0, n - first)
    }

    // ---- control ------------------------------------------------------------------

    val isRecording: Boolean get() = session != null

    /**
     * [stats] is polled about once a second so the report keeps the engine's last
     * readings even if capture stops first. [onFinished] runs on the writer thread.
     */
    @Synchronized
    fun start(dir: File, rate: Int, meta: Map<String, Any?>, stats: () -> Map<String, Any?>?, options: Options = Options(), onFinished: (Result) -> Unit) {
        check(session == null && state.value !is State.Finishing) { "Already recording or saving" }
        require(options.wavBits == 16 || options.wavBits == 24)
        require(rate in 8000..192000)
        dir.mkdirs()
        val s = Session(dir, rate, meta, stats, onFinished, System.currentTimeMillis(), options)
        currentLabel.value = "Opening settings"
        lastSync.value = null
        lastAb.value = null
        if (options.startWithSync) {
            val sync = Sync(0, s.startedAtMs); s.syncs.add(sync); lastSync.value = sync
        }
        session = s
        state.value = State.Recording(s.startedAtMs)
        Thread({ writerLoop(s) }, "svan-proof-writer").apply { priority = Thread.NORM_PRIORITY; start() }
    }

    fun stop() { session?.stop?.set(true) }

    /**
     * Starts a new measured segment at the current position: call after the listener has
     * changed a setting and it has settled. Beyond [MAX_SEGMENTS] changes merge into the last segment.
     */
    @Synchronized
    fun mark(label: String, settings: Map<String, Any?>, expectedRecording: State? = null): Boolean {
        val s = session ?: return false
        if (s.stop.get() || (expectedRecording != null && state.value !== expectedRecording)) return false
        return addMarker(s, s.written, label, settings)
    }

    private fun addMarker(s: Session, position: Long, label: String, settings: Map<String, Any?>): Boolean {
        if (s.markerCount >= MAX_SEGMENTS - 1) {
            s.ignoredMarkers++
            currentLabel.value = "Changes merged into final segment"
            return false
        }
        s.markerCount++
        s.markers.add(Marker(position, label, settings))
        currentLabel.value = label
        return true
    }

    /** Control-thread only. The volatile frame position has the same semantics as mark. */
    @Synchronized
    fun markSync(): Sync? {
        val s = session ?: return null
        if (s.stop.get()) return null
        val sync = Sync(s.written / 2, System.currentTimeMillis())
        s.syncs.add(sync); lastSync.value = sync
        return sync
    }

    /** Export choice only. Records one committed position; never changes DSP or live playback. */
    @Synchronized
    fun markAb(isAfter: Boolean, settings: Map<String, Any?> = emptyMap()): AbSwitch? {
        val s = session ?: return null
        if (s.stop.get()) return null
        val position = s.written
        val choice = AbSwitch(position / 2, isAfter)
        s.abSwitches.add(choice)
        lastAb.value = choice
        addMarker(s, position, if (isAfter) "After" else "Before", settings)
        return choice
    }

    /** Fixed 240-frame blocks, independent of disk chunk size and settings boundaries. */
    private class SignalBlocks {
        var firstFrame: Long? = null
        private var frame = 0L; private var fill = 0; private var dryEnergy = 0.0; private var wetEnergy = 0.0
        fun add(dry: FloatArray, wet: FloatArray, n: Int) {
            for (i in 0 until n step 2) {
                dryEnergy += dry[i].toDouble() * dry[i] + dry[i + 1].toDouble() * dry[i + 1]
                wetEnergy += wet[i].toDouble() * wet[i] + wet[i + 1].toDouble() * wet[i + 1]
                frame++; fill++
                if (fill == 240) close()
            }
        }
        fun close() {
            if (fill > 0 && firstFrame == null && maxOf(dryEnergy, wetEnergy) / (fill * 2) > 1e-9)
                firstFrame = frame - fill // RMS above -90 dBFS, before export dither
            fill = 0; dryEnergy = 0.0; wetEnergy = 0.0
        }
    }

    /** Analyse finished PCM at exact marker frames; a late marker cannot miss drained data. */
    private fun analyseSegments(s: Session, dry: File, wet: File, frames: Long): List<Segment> {
        val boundaries = ArrayList<Marker>()
        boundaries.add(Marker(0, "Opening settings", s.meta))
        s.markers.forEach { m ->
            if (m.position / 2 < frames) {
                if (boundaries.last().position == m.position) boundaries[boundaries.lastIndex] = m else boundaries.add(m)
            }
        }
        val h = ProofWav.header(dry)
        return boundaries.mapIndexed { index, marker ->
            val start = marker.position / 2
            val end = boundaries.getOrNull(index + 1)?.position?.div(2) ?: frames
            val d = LevelMeter(); val w = LevelMeter(); val spectrum = SpectrumPair(s.rate)
            RandomAccessFile(dry, "r").use { input ->
                input.seek(44 + start * h.frameBytes)
                val bytes = ByteArray(4096 * h.frameBytes); val buf = FloatArray(8192)
                ProofWav.visit(wet, start, end) { b, n, _ ->
                    input.readFully(bytes, 0, n * h.bits / 8)
                    ProofWav.decodeInto(bytes, n * h.bits / 8, h.bits, buf)
                    for (i in 0 until n) { d.add(buf[i]); w.add(b[i]) }
                    spectrum.add(buf, b, 0, n)
                }
            }
            Segment(marker.label, marker.settings, start.toDouble() / s.rate, (end - start).toDouble() / s.rate,
                d, w, if (maxOf(d.rmsDbfs, w.rmsDbfs) > -90) spectrum.bands() else emptyList(), start, end)
        }
    }

    // ---- writer thread --------------------------------------------------------------

    private fun writerLoop(s: Session) {
        val dryFile = File(s.dir, "svan-dry-input.wav")
        val wetFile = File(s.dir, "svan-processed-output.wav")
        val dryMeter = LevelMeter(); val wetMeter = LevelMeter()
        val spectrum = SpectrumPair(s.rate)
        var frames = 0L
        var lastStats: Map<String, Any?>? = null
        var lastPoll = 0L
        val signal = SignalBlocks()
        val dryDither = if (s.options.wavBits == 16) Random() else null
        val wetDither = if (s.options.wavBits == 16) Random() else null
        try {
            BufferedOutputStream(FileOutputStream(dryFile), 1 shl 16).use { dryOut ->
                BufferedOutputStream(FileOutputStream(wetFile), 1 shl 16).use { wetOut ->
                    ProofWav.writeHeader(dryOut, s.rate, 0, s.options.wavBits); ProofWav.writeHeader(wetOut, s.rate, 0, s.options.wavBits)
                    val dryChunk = FloatArray(8192); val wetChunk = FloatArray(8192)
                    val bytes = ByteArray(8192 * 3)
                    while (true) {
                        val stopping = s.stop.get()
                        val available = s.written - s.consumed
                        if (available <= 0) {
                            if (stopping) break
                            Thread.sleep(15)
                        } else {
                            val n = minOf(available, dryChunk.size.toLong()).toInt()
                            val at = (s.consumed % RING_FLOATS).toInt()
                            val first = minOf(n, RING_FLOATS - at)
                            System.arraycopy(s.dryRing, at, dryChunk, 0, first)
                            System.arraycopy(s.wetRing, at, wetChunk, 0, first)
                            if (first < n) {
                                System.arraycopy(s.dryRing, 0, dryChunk, first, n - first)
                                System.arraycopy(s.wetRing, 0, wetChunk, first, n - first)
                            }
                            s.consumed += n
                            for (i in 0 until n) {
                                dryMeter.add(dryChunk[i]); wetMeter.add(wetChunk[i])
                            }
                            dryOut.write(bytes, 0, ProofWav.encode(dryChunk, n, bytes, s.options.wavBits, dryDither))
                            wetOut.write(bytes, 0, ProofWav.encode(wetChunk, n, bytes, s.options.wavBits, wetDither))
                            spectrum.add(dryChunk, wetChunk, 0, n)
                            signal.add(dryChunk, wetChunk, n)
                            frames += n / 2
                            if (frames >= s.rate.toLong() * MAX_SECONDS) s.stop.set(true)
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastPoll >= 1000) {
                            lastPoll = now
                            runCatching { s.stats() }.getOrNull()?.let { lastStats = it }
                        }
                    }
                }
            }
            signal.close()
            ProofWav.patchHeader(dryFile, s.rate, frames, s.options.wavBits); ProofWav.patchHeader(wetFile, s.rate, frames, s.options.wavBits)
            state.value = State.Finishing
            session = null
            val segments = analyseSegments(s, dryFile, wetFile, frames)
            var drySync: File? = null; var wetSync: File? = null; var cue: File? = null
            s.syncs.peek()?.let { sync ->
                drySync = File(s.dir, "svan-dry-from-sync.wav").also { ProofWav.tail(dryFile, it, sync.frame) }
                wetSync = File(s.dir, "svan-processed-from-sync.wav").also { ProofWav.tail(wetFile, it, sync.frame) }
                cue = File(s.dir, "svan-processed-sync-cue.wav").also { ProofWav.withCue(wetSync!!, it) }
            }
            val matched = if (s.options.matchLevel) File(s.dir, "svan-processed-matched.wav").also { ProofWav.matched(wetFile, it, segments) } else null
            var abTimeline: File? = null; var abSync: File? = null
            var abGains: List<ProofAbTimeline.RangeGain> = emptyList()
            var abSyncGains: List<ProofAbTimeline.RangeGain> = emptyList()
            val abSwitches = s.abSwitches.toList()
            abSwitches.firstOrNull()?.let { first ->
                abTimeline = File(s.dir, "svan-ab-timeline.wav")
                abGains = ProofAbTimeline.render(dryFile, wetFile, abTimeline!!, abSwitches, segments,
                    first.frame.coerceAtMost(frames), s.options.abMatchLevel)
                s.syncs.peek()?.let { sync ->
                    abSync = File(s.dir, "svan-ab-timeline-from-sync.wav")
                    abSyncGains = ProofAbTimeline.render(dryFile, wetFile, abSync!!, abSwitches, segments,
                        sync.frame.coerceAtMost(frames), s.options.abMatchLevel)
                }
            }
            val seconds = frames.toDouble() / s.rate
            val bands = spectrum.bands()
            val report = buildReport(s, seconds, dryMeter, wetMeter, bands, lastStats, segments, signal.firstFrame)
            report.put("abSwitches", JSONArray().also { a -> abSwitches.forEach {
                a.put(JSONObject().put("frame", it.frame).put("seconds", it.frame.toDouble() / s.rate)
                    .put("choice", if (it.isAfter) "After" else "Before"))
            } })
            if (abTimeline != null) report.put("abTimeline", JSONObject()
                .put("startFrame", abSwitches.first().frame).put("fromSyncStartFrame", s.syncs.peek()?.frame ?: JSONObject.NULL)
                .put("matchLevel", s.options.abMatchLevel).put("crossfadeMs", 5)
                .put("rangeGains", abGainReport(abGains)).put("fromSyncRangeGains", abGainReport(abSyncGains))
                .put("note", "Export choices do not change live playback. Equal-power fades can lift correlated signals; any saturated timeline samples are counted. RMS gains use exact setting/choice ranges, with 5 ms gain transitions and a peak cap, not LUFS. The sync timeline uses full recording frame positions."))
            val reportFile = File(s.dir, "svan-proof-report.json").apply { writeText(report.toString(2)) }
            val result = Result(s.dir, dryFile, wetFile, reportFile, seconds, dryMeter, wetMeter, bands, s.dropped / 2, report, segments, drySync, wetSync, cue, matched, abTimeline, abSync)
            s.onFinished(result)
        } catch (e: Throwable) {
            session = null
            state.value = State.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun buildReport(s: Session, seconds: Double, dry: LevelMeter, wet: LevelMeter, bands: List<SpectrumBand>, stats: Map<String, Any?>?, segments: List<Segment>, firstSignal: Long?): JSONObject {
        val notes = JSONArray()
            .put("Recorded inside Svan's audiophile (capture) engine. Apps handled by system effects are not in these files.")
            .put("'Dry' is what the source app sent, tapped before any Svan processing. 'Processed' is the exact buffer Svan hands to Android's AudioTrack.")
            .put("This is Svan's digital output. It does not include the phone's own mixer, the DAC, Bluetooth encoding or the headphones, and it is not an acoustic measurement.")
            .put("Louder often sounds better. Judge segments at matched RMS level; these are RMS measurements, not LUFS.")
            .put("Speaker-click latency is unmeasured. Allow for device-dependent output delay; the frame-derived on-screen clock is the precise file reference. Flash/click timing must be checked on the owner phones.")
            .put("The sync-cue WAV is for alignment only; its mixed cue may clip. Delete it from the final video. Plain WAVs never contain the generated cue.")
            .put("Whole-recording levels/spectrum precede export; segment measurements use saved PCM, including quantisation/dither. Silent levels are represented by the meter floor (-180 dBFS).")
            .put("Matching uses a constant gain per segment with a peak cap; gain changes at boundaries may be audible. Processed silence cannot be raised to nonzero dry RMS.")
            .put("Levels are sample peaks (not true-peak). The spectrum is a Welch-averaged third-octave estimate of the mid (L+R) signal; only the dry-to-processed difference is meaningful.")
        if (s.ignoredMarkers > 0) notes.put("Segment limit reached: ${s.ignoredMarkers} later markers merged into the final stretch; its listed settings describe only its start.")
        if (s.dropped > 0) notes.put("The recorder dropped ${s.dropped / 2} frames because storage fell behind; both files skip the same frames.")
        if (bands.isEmpty()) notes.put("No signal was present, so there is no spectrum. Make sure the song is playing through the audiophile engine before recording.")
        if (wet.overs > 0) notes.put("${wet.overs} processed samples exceeded full scale and are clamped in the exported WAV.")
        val spec = JSONArray()
        bands.forEach { spec.put(JSONObject().put("hz", round1(it.hz)).put("dryDb", round2(it.dryDb)).put("processedDb", round2(it.processedDb)).put("deltaDb", round2(it.deltaDb))) }
        fun level(m: LevelMeter) = JSONObject().put("peakDbfs", round2(m.peakDbfs)).put("rmsDbfs", round2(m.rmsDbfs))
        val segs = JSONArray()
        segments.forEach { g ->
            val sp = JSONArray()
            g.bands.forEach { sp.put(JSONObject().put("hz", round1(it.hz)).put("dryDb", round2(it.dryDb)).put("processedDb", round2(it.processedDb)).put("deltaDb", round2(it.deltaDb))) }
            segs.put(JSONObject().put("change", g.label).put("startSeconds", round2(g.startSeconds)).put("durationSeconds", round2(g.seconds))
                .put("settings", JSONObject(g.settings.filterValues { it != null }))
                .put("dry", level(g.dry)).put("processed", level(g.processed)).put("rmsChangeDb", round2(g.rmsChangeDb))
                .put("startFrame", g.startFrame).put("endFrame", g.endFrame)
                .put("matchedGainLinear", if (s.options.matchLevel) g.matchedGain else JSONObject.NULL)
                .put("matchedGainDb", if (s.options.matchLevel && g.matchedGain > 0) LevelMeter.toDb(g.matchedGain) else JSONObject.NULL)
                .put("matchLimitedByPeak", s.options.matchLevel && g.dry.sumSquares > 0 && g.processed.sumSquares > 0 && kotlin.math.abs(g.rmsChangeDb + LevelMeter.toDb(g.matchedGain)) > 0.05)
                .put("measurable", g.bands.isNotEmpty()).put("spectrum", sp))
        }
        if (segments.size > 1) notes.put("'segments' measure each stretch between setting changes separately, so the dry-to-processed difference in each one belongs to the settings listed for it. Svaresa (auto) can also change settings; those changes start segments too.")
        return JSONObject()
            .put("recordedAtEpochMs", s.startedAtMs)
            .put("clockStartEpochMs", if (s.firstBlockNanos == 0L) JSONObject.NULL else s.startedAtMs + (s.firstBlockNanos - s.startedAtNanos) / 1_000_000)
            .put("syncFrames", JSONArray(s.syncs.map { it.frame }))
            .put("syncSeconds", JSONArray(s.syncs.map { it.frame.toDouble() / s.rate }))
            .put("syncEpochMs", JSONArray(s.syncs.map { it.epochMs }))
            .put("firstSignalSeconds", firstSignal?.toDouble()?.div(s.rate) ?: JSONObject.NULL)
            .put("firstSignalBlockFrames", 240).put("firstSignalThresholdDbfs", -90)
            .put("durationSeconds", seconds)
            .put("sampleRateHz", s.rate).put("format", "${s.options.wavBits}-bit PCM stereo WAV, sample-aligned").put("wavBits", s.options.wavBits)
            .put("exportDither", if (s.options.wavBits == 16) "TPDF, one LSB" else "none")
            .put("segmentLimitReached", s.ignoredMarkers > 0)
            .put("droppedFrames", s.dropped / 2)
            .put("dry", level(dry)).put("processed", level(wet).put("oversSamples", wet.overs))
            .put("rmsChangeDb", round2(wet.rmsDbfs - dry.rmsDbfs))
            .put("spectrum", spec).put("segments", segs)
            .put("settings", JSONObject(s.meta.filterValues { it != null }))
            .put("engine", JSONObject((stats ?: emptyMap()).filterValues { it != null }))
            .put("notes", notes)
    }

    private fun round1(x: Double) = Math.round(x * 10) / 10.0
    private fun round2(x: Double) = Math.round(x * 100) / 100.0
    private fun abGainReport(gains: List<ProofAbTimeline.RangeGain>) = JSONArray().also { a -> gains.forEach { g ->
        a.put(JSONObject().put("startFrame", g.startFrame).put("endFrame", g.endFrame)
            .put("gainLinear", g.gainLinear).put("gainDb", if (g.gainLinear > 0) LevelMeter.toDb(g.gainLinear) else JSONObject.NULL)
            .put("limitedByPeak", g.limitedByPeak).put("clippedSamples", g.clippedSamples))
    } }

}
