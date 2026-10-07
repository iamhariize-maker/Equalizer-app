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
 * Android glue for [ProofRecorder]: names the session, snapshots the settings, and on
 * completion draws a share-ready chart and publishes everything to shared storage
 * (no storage permission: MediaStore on API 29+).
 */
object ProofCapture {
    /** Where the last finished recording went, e.g. "Music/Svan Proof/2026-10-08_0112". */
    @Volatile var lastLocation: String? = null; private set

    fun start(context: Context) {
        check(CaptureService.isRunning) { "Start the audiophile engine first" }
        val app = context.applicationContext
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        val dir = File(app.filesDir, "proof/$stamp")
        val s = SvanRepository.settings.value
        val eq = SvanRepository.eq.value
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
        val meta = linkedMapOf<String, Any?>(
            "svanVersion" to version,
            "qualityMode" to s.quality.title, "oversampling" to s.quality.oversample,
            "dither" to s.dither.title, "outputBitsIfDithered" to s.outputBits,
            "autoHeadroom" to s.effectiveFor(eq).autoHeadroom, "gainProtection" to s.effectiveFor(eq).gainProtection,
            "eqEnabled" to eq.enabled, "eqBandsApplied" to eq.effectiveBands().size,
            "preampDb" to eq.effectivePreampDb(), "bassCharacter" to eq.bassCharacter,
            "headphoneCorrection" to eq.tuning?.takeIf { it.enabled }?.headphone,
            "device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}",
        )
        ProofRecorder.start(dir, EqController.SAMPLE_RATE, meta, ::engineStats) { result -> publish(app, stamp, result) }
    }

    fun stop() = ProofRecorder.stop()

    /** Back to the idle button after the user has read the outcome. */
    fun dismiss() { if (!ProofRecorder.isRecording) ProofRecorder.state.value = ProofRecorder.State.Idle }

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
            store(r.dryWav, audio, "Music", "audio/x-wav")
            store(r.processedWav, audio, "Music", "audio/x-wav")
            store(chart, downloads, "Download", "image/png")
            store(r.reportJson, downloads, "Download", "application/json")
            lastLocation = "Music/Svan Proof/$stamp (WAVs) · Download/Svan Proof/$stamp (chart, report)"
            r.directory.deleteRecursively()
            EqController.log("proof: saved ${"%.1f".format(r.seconds)} s, dropped=${r.droppedFrames} → $lastLocation")
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
}
