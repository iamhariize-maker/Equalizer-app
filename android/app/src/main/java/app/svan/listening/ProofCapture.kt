package app.svan.listening

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.provider.MediaStore
import app.svan.CaptureService
import app.svan.EqController
import app.svan.SvanRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Android glue for [ProofRecorder]: names the session, watches the listener's settings so
 * every change starts a separately measured segment, and on completion draws the charts and
 * publishes everything to shared storage (no storage permission: MediaStore on API 29+).
 */
object ProofCapture {
    /** Where the last finished recording went, e.g. "Music/Svan Proof/2026-10-08_011200". */
    @Volatile var lastLocation: String? = null; private set
    val flash = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val cueNote = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    @Volatile var lastExportNote: String? = null; private set

    private fun snapshot(): Map<String, Any?> {
        val s = CaptureService.epoch?.appliedSettings ?: SvanRepository.settings.value
        val eq = SvanRepository.eq.value
        val guarded = s.effectiveFor(eq)
        val capture = CaptureService.epoch
        val lab = capture?.takeIf { it.labBlock != null }?.let { app.svan.lab.IntegratedLab.captureControls(it.sampleRate) }
        return linkedMapOf(
            "spatialMode" to s.spatialMode.title, "captureRateHz" to CaptureService.epoch?.sampleRate,
            "experimentalBassUnmask" to s.experimentalBassUnmask,
            "bassAttack" to s.bassAttack, "bassSustain" to s.bassSustain, "bassDimension" to s.bassDimension, "bassTube" to s.bassTube,
            "qualityMode" to s.quality.title, "oversampling" to s.quality.oversample,
            "dither" to s.dither.title, "outputBitsIfDithered" to s.outputBits,
            "autoHeadroom" to guarded.autoHeadroom, "gainProtection" to guarded.gainProtection,
            "eqEnabled" to eq.enabled, "eqBandsApplied" to (lab?.gains?.size ?: eq.effectiveBands().size),
            "eqCurve" to (lab?.gains?.indices?.map { i -> org.json.JSONObject().put("stopBin", lab.stops[i]).put("gainDb", lab.gains[i]).toString() }
                ?: eq.effectiveBands().map { it.toJson().toString() }),
            "staticEqMode" to if (capture?.labBlock != null) "capture-lab" else "native-parametric",
            "captureLabBlock" to capture?.labBlock, "captureLabHybrid" to capture?.labHybrid,
            "captureLabInputGainDb" to lab?.inputGainDb,
            "captureLabBassCoefficients" to lab?.coefficients?.toList(),
            "nativeLatencyFrames" to capture?.latencyFrames,
            "vocalTuner" to eq.activeVocal.toJson().toString(),
            "instrumentTuner" to eq.activeInstrument.toJson().toString(),
            "dynamicEq" to eq.dynamicEq,
            "preampDb" to Math.round(eq.effectivePreampDb() * 10) / 10.0,
            "bassCharacter" to Math.round(eq.bassCharacter * 100) / 100.0,
            "headphoneCorrection" to eq.tuning?.takeIf { it.enabled }?.headphone,
        )
    }

    fun start(context: Context, wavBits: Int = 16, matchLevel: Boolean = false, automaticSync: Boolean = false, abMatchLevel: Boolean = true) {
        check(CaptureService.isRunning) { "Start the audiophile engine first" }
        val capture = checkNotNull(CaptureService.epoch) { "Wait for the audiophile engine to finish starting" }
        val app = context.applicationContext
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss_SSS", Locale.US).format(Date())
        val dir = File(app.filesDir, "proof/$stamp").apply { mkdirs() }
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
        val opening = snapshot() + mapOf(
            "svanVersion" to version,
            "device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}",
        )
        lastExportNote = null
        cueNote.value = null
        ProofRecorder.start(dir, capture.sampleRate, opening, { engineStats() },
            ProofRecorder.Options(wavBits, matchLevel, automaticSync, abMatchLevel)) { result -> publish(app, stamp, result) }
        check(CaptureService.epoch?.sampleRate == capture.sampleRate) {
            ProofRecorder.stop()
            "Capture format changed; start a new recording"
        }
        if (automaticSync) flashAndCue(app)
        watchSettings(snapshot())
    }

    fun stop() = ProofRecorder.stop()

    fun sync(context: Context) {
        if (ProofRecorder.markSync() != null) flashAndCue(context.applicationContext)
    }

    private fun flashAndCue(context: Context) {
        flash.value++
        cueNote.value = null
        ProofSpeakerCue.play(context) { cueNote.value = it }
    }

    fun mark(label: String): Boolean {
        val clean = label.trim().take(40)
        return clean.isNotEmpty() && ProofRecorder.mark(clean, snapshot())
    }

    fun markAb(isAfter: Boolean) = ProofRecorder.markAb(isAfter, snapshot())

    /** Back to the idle button after the user has read the outcome. */
    fun dismiss() { if (!ProofRecorder.isRecording && ProofRecorder.state.value !is ProofRecorder.State.Finishing) ProofRecorder.state.value = ProofRecorder.State.Idle }

    /** A change that stays put for [SETTLE_MS] opens a new measured segment. Knob drags don't. */
    private fun watchSettings(initial: Map<String, Any?>) {
        val recording = ProofRecorder.state.value
        Thread({
            var committed = initial
            var candidate = initial
            var since = System.nanoTime()
            while (ProofRecorder.state.value === recording && ProofRecorder.isRecording) {
                val now = snapshot()
                if (now != candidate) { candidate = now; since = System.nanoTime() }
                else if (candidate != committed && (System.nanoTime() - since) / 1_000_000 >= SETTLE_MS) {
                    ProofRecorder.mark(SettingsDiff.describe(committed, candidate), candidate, recording)
                    committed = candidate
                }
                try { Thread.sleep(100) } catch (_: InterruptedException) { return@Thread }
            }
        }, "svan-proof-watch").apply { isDaemon = true; start() }
    }
    private const val SETTLE_MS = 700L

    private fun engineStats(): Map<String, Any?>? = CaptureService.stats?.let {
        mapOf(
            "inputPeakDbfs" to it.inputPeakDb, "outputPeakDbfs" to it.outputPeakDb,
            "dspLatencyMs" to it.dspLatencyMs, "dspLoadPercent" to it.dspPercent, "underruns" to it.underruns,
            "outputBufferMs" to it.bufferMs, "appliedGainDb" to it.gainDb, "gainProtectionDb" to it.protectionDb,
        ).filterValues { v -> v !is Double || v.isFinite() }
    }

    private fun publish(context: Context, stamp: String, r: ProofRecorder.Result) {
        try {
            val chart = File(r.directory, "svan-proof-chart.png")
            chart.outputStream().use { ProofChart.render(r).compress(Bitmap.CompressFormat.PNG, 100, it) }
            val settingsChart = File(r.directory, "svan-settings-effects.png")
            settingsChart.outputStream().use { ProofChart.renderSettings(r).compress(Bitmap.CompressFormat.PNG, 100, it) }
            val resolver = context.contentResolver
            fun store(file: File, collection: android.net.Uri, folder: String, mime: String) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/Svan Proof/$stamp")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(collection, values) ?: error("Couldn't create ${file.name} in shared storage")
                try {
                    resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                    resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
            }
            val audio = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val downloads = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val where = "Music/Svan Proof/$stamp (audio) · Download/Svan Proof/$stamp (charts, report)"
            val aac = File(r.directory, "svan-processed-output.m4a")
            runCatching { ProofAac.encode(r.processedWav, aac) }.onFailure {
                lastExportNote = "WAVs saved; AAC export failed: ${it.message}"
                r.report.getJSONArray("notes").put(lastExportNote)
            }
            if (aac.isFile && aac.length() > 0) {
                r.report.put("aac", org.json.JSONObject().put("codec", "AAC-LC").put("sampleRateHz", 48000)
                    .put("channels", 2).put("requestedBitrateBps", 256000)
                    .put("note", "Lossy convenience export; encoder delay may affect alignment. Use WAV and the clock for precise sync."))
                store(aac, audio, "Music", "audio/mp4")
            }
            listOfNotNull(r.dryFromSyncWav, r.processedFromSyncWav, r.syncCueWav, r.matchedWav,
                r.abTimelineWav, r.abTimelineFromSyncWav).forEach {
                store(it, audio, "Music", "audio/x-wav")
            }
            r.reportJson.writeText(r.report.toString(2))
            store(r.dryWav, audio, "Music", "audio/x-wav")
            store(r.processedWav, audio, "Music", "audio/x-wav")
            store(chart, downloads, "Download", "image/png")
            store(settingsChart, downloads, "Download", "image/png")
            store(r.reportJson, downloads, "Download", "application/json")
            lastLocation = where
            // Keep the last private result for retry/diagnostics; remove older successful sessions.
            r.directory.parentFile?.listFiles()?.filter { it != r.directory && File(it, ".published").exists() }?.forEach { it.deleteRecursively() }
            File(r.directory, ".published").writeText(where)
            EqController.log("proof: saved ${"%.1f".format(r.seconds)} s, ${r.segments.size} segment(s), dropped=${r.droppedFrames} → $where")
            ProofRecorder.state.value = ProofRecorder.State.Done(r)
        } catch (e: Exception) {
            EqController.log("proof: save failed: $e")
            ProofRecorder.state.value = ProofRecorder.State.Failed("Recorded, but couldn't save to shared storage: ${e.message}. Files remain in Svan's private folder.")
        }
    }
}

/** A single portrait image that shows the change: both spectra, the difference, and the numbers. */
object ProofChart {
    private const val W = 1080
    private const val H = 1350
    private val bg = Color.rgb(0x0C, 0x0A, 0x08)
    private val grid = Color.rgb(0x2A, 0x23, 0x19)
    private val text = Color.rgb(0xEE, 0xE6, 0xD8)
    private val muted = Color.rgb(0xA7, 0x9D, 0x8D)
    private val gold = Color.rgb(0xD9, 0xA8, 0x4E)
    private val ash = Color.rgb(0xA3, 0x9A, 0x8B)
    private val ember = Color.rgb(0xB4, 0x55, 0x2E)

    fun render(r: ProofRecorder.Result): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(bg)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun label(s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) {
            p.style = Paint.Style.FILL; p.color = color; p.textSize = size; p.textAlign = align
            p.typeface = if (bold) Typeface.create(Typeface.SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
            c.drawText(s, x, y, p)
        }
        label("Svan · audiophile engine", 60f, 110f, 54f, gold, bold = true)
        label("Measured on this recording · dry input vs processed output", 60f, 160f, 30f, muted)

        // ---- spectrum panel ----
        val l = 130f; val rgt = W - 60f; val top = 230f; val bot = 760f
        val bands = r.bands
        if (bands.isEmpty()) {
            label("No signal was recorded, so there is no spectrum.", l, 480f, 34f, ember)
        } else {
            val lo = bands.minOf { min(it.dryDb, it.processedDb) }; val hi = bands.maxOf { max(it.dryDb, it.processedDb) }
            val yMax = Math.ceil(hi / 10.0) * 10.0; val yMin = max(Math.floor(lo / 10.0) * 10.0, yMax - 80.0)
            fun x(i: Int) = l + (rgt - l) * i / max(1, bands.size - 1)
            fun y(db: Double) = (bot - (bot - top) * ((db - yMin) / (yMax - yMin)).coerceIn(0.0, 1.0)).toFloat()
            p.strokeWidth = 1.5f
            var g = yMin
            while (g <= yMax + 0.01) { p.color = grid; p.style = Paint.Style.STROKE; c.drawLine(l, y(g), rgt, y(g), p); label("%.0f".format(g), l - 12f, y(g) + 10f, 24f, muted, align = Paint.Align.RIGHT); g += 10.0 }
            val ticks = doubleArrayOf(31.25, 125.0, 500.0, 1000.0, 2000.0, 8000.0, 16000.0)
            bands.forEachIndexed { i, b ->
                if (ticks.any { kotlin.math.abs(b.hz - it) / it < 0.07 })
                    label(if (b.hz >= 1000) "%.0fk".format(b.hz / 1000) else "%.0f".format(b.hz), x(i), bot + 36f, 24f, muted, align = Paint.Align.CENTER)
            }
            fun line(sel: (SpectrumBand) -> Double, color: Int, w: Float) {
                val path = Path()
                bands.forEachIndexed { i, b -> if (i == 0) path.moveTo(x(i), y(sel(b))) else path.lineTo(x(i), y(sel(b))) }
                p.style = Paint.Style.STROKE; p.color = color; p.strokeWidth = w; p.strokeJoin = Paint.Join.ROUND; c.drawPath(path, p)
            }
            line({ it.dryDb }, ash, 4f)
            line({ it.processedDb }, gold, 6f)
            label("dry input", l, top - 24f, 28f, ash); label("processed output", l + 200f, top - 24f, 28f, gold)
            label("dB, relative", rgt, top - 24f, 24f, muted, align = Paint.Align.RIGHT)

            // ---- change panel: bars of processed − dry ----
            val dTop = 860f; val dBot = 1100f; val mid = (dTop + dBot) / 2
            val span = max(3.0, Math.ceil(bands.maxOf { kotlin.math.abs(it.deltaDb) }))
            label("Change (processed − dry), dB per third-octave", l, dTop - 28f, 28f, text)
            p.color = grid; p.style = Paint.Style.STROKE; c.drawLine(l, mid, rgt, mid, p)
            label("+%.0f".format(span), l - 12f, dTop + 10f, 22f, muted, align = Paint.Align.RIGHT)
            label("-%.0f".format(span), l - 12f, dBot + 4f, 22f, muted, align = Paint.Align.RIGHT)
            val bw = (rgt - l) / bands.size * 0.7f
            bands.forEachIndexed { i, b ->
                val h = (b.deltaDb / span * (dBot - dTop) / 2).toFloat()
                p.style = Paint.Style.FILL; p.color = if (b.deltaDb >= 0) gold else ash
                c.drawRect(x(i) - bw / 2, min(mid, mid - h), x(i) + bw / 2, max(mid, mid - h), p)
            }
        }

        // ---- numbers ----
        var ty = 1170f
        fun row(k: String, v: String, color: Int = text) { label(k, 60f, ty, 30f, muted); label(v, W - 60f, ty, 30f, color, align = Paint.Align.RIGHT); ty += 44f }
        row("Peak  dry → processed", "%.1f → %.1f dBFS".format(r.dry.peakDbfs, r.processed.peakDbfs), if (r.processed.overs > 0) ember else text)
        row("Average level (RMS)", "%.1f → %.1f dBFS".format(r.dry.rmsDbfs, r.processed.rmsDbfs))
        val e = r.report.optJSONObject("engine")
        if (e != null && e.has("dspLatencyMs")) row("DSP delay · load · underruns", "%.1f ms · %.1f%% · %d".format(e.optDouble("dspLatencyMs"), e.optDouble("dspLoadPercent"), e.optInt("underruns")))
        label("%.0f s · %d Hz · dropped %d frames · Svan's digital output, not a DAC or acoustic measurement".format(r.seconds, r.report.optInt("sampleRateHz"), r.droppedFrames), 60f, H - 40f, 22f, muted)
        return bmp
    }

    /** One row per setting change: what the change was, and the measured dry → processed difference while it was active. */
    fun renderSettings(r: ProofRecorder.Result): Bitmap {
        val segs = r.segments
        val rowH = 190
        val h = 230 + segs.size * rowH + 130
        val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(bg)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun label(s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) {
            p.style = Paint.Style.FILL; p.color = color; p.textSize = size; p.textAlign = align
            p.typeface = if (bold) Typeface.create(Typeface.SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
            c.drawText(s, x, y, p)
        }
        fun fit(s: String, size: Float, width: Float): String {
            p.textSize = size; p.typeface = Typeface.SANS_SERIF
            return if (p.measureText(s) <= width) s else p.breakText(s, true, width - p.measureText("…"), null).let { s.substring(0, it) + "…" }
        }
        label("What each setting did", 60f, 100f, 52f, gold, bold = true)
        label("Same song, measured separately for each stretch · dry input vs processed output", 60f, 150f, 26f, muted)
        val span = max(3.0, Math.ceil(segs.flatMap { it.bands }.maxOfOrNull { kotlin.math.abs(it.deltaDb) } ?: 3.0))
        label("bars: change per third-octave band, 31 Hz → 20 kHz, scale ±%.0f dB".format(span), 60f, 190f, 24f, muted)
        segs.forEachIndexed { n, g ->
            val top = 230f + n * rowH
            p.style = Paint.Style.STROKE; p.color = grid; p.strokeWidth = 1.5f; c.drawLine(60f, top, W - 60f, top, p)
            label("%d:%02d".format((g.startSeconds / 60).toInt(), (g.startSeconds % 60).toInt()), 60f, top + 44f, 30f, gold, bold = true)
            label(fit(g.label, 30f, W - 260f), 170f, top + 44f, 30f, text)
            val dRms = g.rmsChangeDb
            label("%+.1f dB".format(dRms), W - 60f, top + 100f, 38f, text, bold = true, align = Paint.Align.RIGHT)
            label("RMS change", W - 60f, top + 134f, 22f, muted, align = Paint.Align.RIGHT)
            label("peak %.1f dBFS".format(g.processed.peakDbfs), W - 60f, top + 166f, 22f, muted, align = Paint.Align.RIGHT)
            if (g.bands.isEmpty()) {
                label(if (maxOf(g.dry.rmsDbfs, g.processed.rmsDbfs) > -90) "too short for a spectrum" else "no signal above -90 dBFS", 170f, top + 110f, 26f, ember)
                return@forEachIndexed
            }
            val bx = 60f; val bw = W - 120f - 250f; val mid = top + 120f; val half = 50f
            p.color = grid; c.drawLine(bx, mid, bx + bw, mid, p)
            val slot = bw / g.bands.size
            g.bands.forEachIndexed { i, b ->
                val bh = (b.deltaDb / span * half).toFloat()
                p.style = Paint.Style.FILL; p.color = if (b.deltaDb >= 0) gold else ash
                c.drawRect(bx + i * slot + slot * 0.15f, min(mid, mid - bh), bx + (i + 1) * slot - slot * 0.15f, max(mid, mid - bh), p)
            }

        }
        label("Louder often sounds better. Judge segments at matched RMS level.", 60f, h - 76f, 26f, text)
        label("Svan's digital output, not a DAC or acoustic measurement. Auto changes also start a stretch.", 60f, h - 40f, 22f, muted)
        return bmp
    }
}
