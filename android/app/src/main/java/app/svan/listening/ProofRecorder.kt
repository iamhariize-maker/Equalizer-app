package app.svan.listening

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
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

    class Result(
        val directory: File, val dryWav: File, val processedWav: File, val reportJson: File,
        val seconds: Double, val dry: LevelMeter, val processed: LevelMeter,
        val bands: List<SpectrumBand>, val droppedFrames: Long, val report: JSONObject,
    )

    val state = MutableStateFlow<State>(State.Idle)

    private const val RING_FLOATS = 48000 * 2 * 4   // four seconds of stereo
    private const val MAX_SECONDS = 600             // 10 min ≈ 173 MB per WAV

    private class Session(
        val dir: File, val rate: Int, val meta: Map<String, Any?>, val stats: () -> Map<String, Any?>?,
        val onFinished: (Result) -> Unit, val startedAtMs: Long,
    ) {
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
    fun start(dir: File, rate: Int, meta: Map<String, Any?>, stats: () -> Map<String, Any?>?, onFinished: (Result) -> Unit) {
        check(session == null) { "Already recording" }
        require(rate in 8000..192000)
        dir.mkdirs()
        val s = Session(dir, rate, meta, stats, onFinished, System.currentTimeMillis())
        session = s
        state.value = State.Recording(s.startedAtMs)
        Thread({ writerLoop(s) }, "svan-proof-writer").apply { priority = Thread.NORM_PRIORITY; start() }
    }

    fun stop() { session?.stop?.set(true) }

    // ---- writer thread --------------------------------------------------------------

    private fun writerLoop(s: Session) {
        val dryFile = File(s.dir, "svan-dry-input.wav")
        val wetFile = File(s.dir, "svan-processed-output.wav")
        val dryMeter = LevelMeter(); val wetMeter = LevelMeter()
        val spectrum = SpectrumPair(s.rate)
        var frames = 0L
        var lastStats: Map<String, Any?>? = null
        var lastPoll = 0L
        try {
            BufferedOutputStream(FileOutputStream(dryFile), 1 shl 16).use { dryOut ->
                BufferedOutputStream(FileOutputStream(wetFile), 1 shl 16).use { wetOut ->
                    writeHeader(dryOut, s.rate, 0); writeHeader(wetOut, s.rate, 0)
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
                            for (i in 0 until n) { dryMeter.add(dryChunk[i]); wetMeter.add(wetChunk[i]) }
                            dryOut.write(bytes, 0, pcm24(dryChunk, n, bytes))
                            wetOut.write(bytes, 0, pcm24(wetChunk, n, bytes))
                            spectrum.add(dryChunk, wetChunk, 0, n)
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
            patchHeader(dryFile, s.rate, frames); patchHeader(wetFile, s.rate, frames)
            session = null
            state.value = State.Finishing
            val seconds = frames.toDouble() / s.rate
            val bands = spectrum.bands()
            val report = buildReport(s, seconds, dryMeter, wetMeter, bands, lastStats)
            val reportFile = File(s.dir, "svan-proof-report.json").apply { writeText(report.toString(2)) }
            val result = Result(s.dir, dryFile, wetFile, reportFile, seconds, dryMeter, wetMeter, bands, s.dropped / 2, report)
            s.onFinished(result)
        } catch (e: Throwable) {
            session = null
            state.value = State.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun buildReport(s: Session, seconds: Double, dry: LevelMeter, wet: LevelMeter, bands: List<SpectrumBand>, stats: Map<String, Any?>?): JSONObject {
        val notes = JSONArray()
            .put("Recorded inside Svan's audiophile (capture) engine. Apps handled by system effects are not in these files.")
            .put("'Dry' is what the source app sent, tapped before any Svan processing. 'Processed' is the exact buffer Svan hands to Android's AudioTrack.")
            .put("This is Svan's digital output. It does not include the phone's own mixer, the DAC, Bluetooth encoding or the headphones, and it is not an acoustic measurement.")
            .put("Levels are sample peaks (not true-peak). The spectrum is a Welch-averaged third-octave estimate of the mid (L+R) signal; only the dry-to-processed difference is meaningful.")
        if (s.dropped > 0) notes.put("The recorder dropped ${s.dropped / 2} frames because storage fell behind; both files skip the same frames.")
        if (bands.isEmpty()) notes.put("No signal was present, so there is no spectrum. Make sure the song is playing through the audiophile engine before recording.")
        if (wet.overs > 0) notes.put("${wet.overs} processed samples exceeded full scale and are clamped in the 24-bit WAV.")
        val spec = JSONArray()
        bands.forEach { spec.put(JSONObject().put("hz", round1(it.hz)).put("dryDb", round2(it.dryDb)).put("processedDb", round2(it.processedDb)).put("deltaDb", round2(it.deltaDb))) }
        fun level(m: LevelMeter) = JSONObject().put("peakDbfs", round2(m.peakDbfs)).put("rmsDbfs", round2(m.rmsDbfs))
        return JSONObject()
            .put("recordedAtEpochMs", s.startedAtMs).put("durationSeconds", round2(seconds))
            .put("sampleRateHz", s.rate).put("format", "24-bit PCM stereo WAV, sample-aligned")
            .put("droppedFrames", s.dropped / 2)
            .put("dry", level(dry)).put("processed", level(wet).put("oversSamples", wet.overs))
            .put("rmsChangeDb", round2(wet.rmsDbfs - dry.rmsDbfs))
            .put("spectrum", spec)
            .put("settings", JSONObject(s.meta.filterValues { it != null }))
            .put("engine", JSONObject((stats ?: emptyMap()).filterValues { it != null }))
            .put("notes", notes)
    }

    private fun round1(x: Double) = Math.round(x * 10) / 10.0
    private fun round2(x: Double) = Math.round(x * 100) / 100.0

    // ---- WAV ------------------------------------------------------------------------

    /** Float → little-endian signed 24-bit, clamped. Returns bytes written. */
    internal fun pcm24(src: FloatArray, n: Int, out: ByteArray): Int {
        var o = 0
        for (i in 0 until n) {
            val v = Math.round(src[i].toDouble().coerceIn(-1.0, 1.0) * 8388607.0).toInt()
            out[o++] = v.toByte(); out[o++] = (v shr 8).toByte(); out[o++] = (v shr 16).toByte()
        }
        return o
    }

    internal fun writeHeader(out: java.io.OutputStream, rate: Int, frames: Long) {
        val dataBytes = frames * 6
        fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        out.write("RIFF".toByteArray()); out.write(le32(36 + dataBytes)); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); out.write(le32(16)); out.write(le16(1)); out.write(le16(2))
        out.write(le32(rate.toLong())); out.write(le32(rate * 6L)); out.write(le16(6)); out.write(le16(24))
        out.write("data".toByteArray()); out.write(le32(dataBytes))
    }

    private fun patchHeader(file: File, rate: Int, frames: Long) {
        RandomAccessFile(file, "rw").use { f ->
            val h = java.io.ByteArrayOutputStream(44).also { writeHeader(it, rate, frames) }.toByteArray()
            f.seek(0); f.write(h)
        }
    }
}
