package app.svan.tuning

import android.content.Context
import app.svan.NativeEngine
import app.svan.model.Band
import app.svan.model.Tuning
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
    )

    suspend fun build(context: Context, r: Request): Result<Tuning> = withContext(Dispatchers.IO) {
        runCatching {
            val fit = when {
                r.signature == Signature.PUBLISHED ->
                    NativeEngine.fitCorrection(AutoEqSource.publishedCorrection(context, r.entry), r.bassDb, r.tiltDbPerOct, r.bandCount)
                else -> {
                    val measurement = r.customMeasurement ?: AutoEqSource.measurement(context, r.entry)
                    val target = when (r.signature) {
                        Signature.CUSTOM -> r.customTarget ?: error("Import a target curve first")
                        else -> AutoEqSource.target(context, r.signature.targetFile(r.entry) ?: error("${r.signature.title} isn't available for this headphone"))
                    }
                    NativeEngine.computeTuning(measurement, target, r.bassDb, r.tiltDbPerOct, r.bandCount)
                }
            } ?: error("Couldn't read the curve data")
            Tuning(
                enabled = true,
                headphone = r.entry.name,
                source = r.entry.label,
                signature = r.signature.title,
                bands = fit.bands.map { Band(it.type, it.freqHz, it.gainDb, it.q) },
                fitRmsDb = fit.rmsErrorDb,
                bassDb = r.bassDb,
                tiltDbPerOct = r.tiltDbPerOct,
                ref = listOf(r.entry.source, r.entry.form, r.entry.rig ?: "", r.entry.name, r.entry.resultPath).joinToString("|"),
            )
        }
    }

    fun entryFromRef(ref: String): AutoEqSource.Entry? {
        val p = ref.split('|')
        if (p.size != 5) return null
        return AutoEqSource.Entry(p[3], p[0], p[1], p[2].ifEmpty { null }, p[4])
    }
}
