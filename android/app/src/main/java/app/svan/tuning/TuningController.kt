package app.svan.tuning

import android.content.Context
import app.svan.NativeEngine
import app.svan.model.Band
import app.svan.model.Tuning
import app.svan.model.CorrectionCalibration
import app.svan.SvanRepository
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Turns (headphone, signature, taste) into a dense-band [Tuning]. Network + DSP off the main thread. */
object TuningController {

    data class Request(
        val entry: AutoEqSource.Entry,
        val signature: Signature,
        val bassDb: Double,
        val tiltDbPerOct: Double,
        val bandCount: Int,
        val customTarget: String? = null,      // CUSTOM signature
        val customMeasurement: String? = null, // imported headphone (not in AutoEq)
        val amount: Double = 1.0,
    )

    suspend fun build(context: Context, r: Request): Result<Tuning> = withContext(Dispatchers.IO) {
        runCatching {
            val ref=listOf(r.entry.source,r.entry.form,r.entry.rig ?: "",r.entry.name,r.entry.resultPath).joinToString("|")
            val previous=SvanRepository.eq.value.tuning?.takeIf { it.ref==ref }
            val cache=File(context.filesDir,"calibration").apply {mkdirs()}
            fun cached(hash: String?): String? = hash?.takeIf {it.matches(Regex("[a-f0-9]{64}"))}?.let {File(cache,"$it.txt").takeIf(File::isFile)?.readText()}
            fun save(text: String): String {
                require(text.length<=1_000_000) { "Curve is too large" }
                val hash=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {"%02x".format(it)}
                File(cache,"$hash.txt").writeText(text);return hash
            }
            val published=r.signature==Signature.PUBLISHED
            val measurement=if(published) AutoEqSource.publishedCorrection(context,r.entry) else
                r.customMeasurement ?: cached(previous?.calibration?.takeIf {it.basis=="Measurement-derived target"}?.measurementHash) ?: AutoEqSource.measurement(context,r.entry)
            val target=if(published) "" else when(r.signature) {
                Signature.CUSTOM -> r.customTarget ?: cached(previous?.takeIf {it.signature==r.signature.title}?.calibration?.targetHash) ?: error("Import a target curve first")
                else -> AutoEqSource.target(context,r.signature.targetFile(r.entry) ?: error("${r.signature.title} isn't available for this headphone"))
            }
            require(measurement.length<=1_000_000&&target.length<=1_000_000) {"Curve is too large"}
            val calibrated=NativeEngine.calibratedTuning(measurement,target,published,r.amount,r.bandCount,r.bassDb,r.tiltDbPerOct)
                ?: error("Correction needs valid data covering at least 30 Hz–10 kHz and a compatible target")
            val fit=calibrated.fit
            val calibration=CorrectionCalibration(r.amount.coerceIn(0.0,1.0),fit.maxErrorDb,calibrated.lowHz,calibrated.highHz,
                if(published) "Published profile" else "Measurement-derived target",save(measurement),if(published) "" else save(target))
            Tuning(
                enabled = true,
                headphone = r.entry.name,
                source = r.entry.label,
                signature = r.signature.title,
                bands = fit.bands.map { Band(it.type, it.freqHz, it.gainDb, it.q) },
                fitRmsDb = fit.rmsErrorDb,
                bassDb = r.bassDb,
                tiltDbPerOct = r.tiltDbPerOct,
                ref = ref,
                calibration=calibration,
            )
        }
    }

    fun entryFromRef(ref: String): AutoEqSource.Entry? {
        val p = ref.split('|')
        if (p.size != 5) return null
        return AutoEqSource.Entry(p[3], p[0], p[1], p[2].ifEmpty { null }, p[4])
    }
}
