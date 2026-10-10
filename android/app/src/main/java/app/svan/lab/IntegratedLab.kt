package app.svan.lab

import android.content.Context
import android.media.audiofx.AudioEffect
import android.os.Build
import app.svan.CaptureService
import app.svan.EqController
import app.svan.SessionRouter
import app.svan.SvanRepository
import app.svan.lab.core.Planner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

/** One frozen, opt-in Engine A experiment. Detection and effect ownership remain Svan's. */
object IntegratedLab {
    data class State(val busy: Boolean = false, val plan: Planner.Plan? = null, val applied: Boolean = false,
        val message: String = "Fit your current Svan curve, then compare the reference prediction.")
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private val worker = Executors.newSingleThreadExecutor()
    private val epoch = AtomicLong()
    private var task: Future<*>? = null
    private var signature = ""
    private fun currentSignature() = SvanRepository.eq.value.toJson().toString() + SvanRepository.settings.value.toJson().toString()

    @Synchronized
    fun fit(context: Context, rate: Int, block: Int, hybrid: Boolean, margin: Double) {
        require(rate == 44100 || rate == 48000)
        require(block in listOf(2048, 4096, 8192))
        require(margin.isFinite() && margin in 3.0..18.0)
        val generation = epoch.incrementAndGet()
        task?.cancel(true)
        val source = currentSignature()
        val frequencies = frozenFrequencies(rate)
        // Curve engine uses the declared 48k reference. At 44.1k, retain the requested frequency response;
        // this is a control fit, not a claim about Android's actual output rate.
        val target = EqController.curveEngine.responseDb(frequencies)
        if (target.size != frequencies.size || target.any { !it.isFinite() || it !in -18.0..12.0 }) {
            mutable.value = State(message = "This curve exceeds the Lab's −18 to +12 dB fit range. Reduce extreme cuts/boosts or preamp first.")
            return
        }
        mutable.value = mutable.value.copy(busy = true, applied = false, message = "Fitting the frozen combined curve…")
        val app = context.applicationContext
        task = worker.submit {
            try {
                EqController.globalEq.clearLab()
                val model = Planner.Model.read(app.assets.open("lab/models/${rate}_${block}.bin")).reduce(64)
                val table = Planner.EqTable(app.assets.open("lab/eq_coefficients.csv"))
                val referenceEq = AudioEffect.queryEffects().any { it.uuid.toString() == "ce772f20-847d-11df-bb17-0002a5d5c51b" }
                val plan = Planner(table).planResponse(model, { hz -> interpolate(target, hz, rate) }, hybrid && referenceEq, margin)
                if (epoch.get() != generation || Thread.currentThread().isInterrupted) return@submit
                if (source != currentSignature()) {
                    mutable.value = mutable.value.copy(busy = false, message = "Your curve changed during fitting. Fit again when it is settled.")
                    return@submit
                }
                signature = source
                mutable.value = State(plan = plan,
                    message = if (hybrid && !referenceEq) "DP-only fit ready. This Equalizer has no verified reference model."
                        else "Reference fit ready. Device response, latency and peaks remain unmeasured.")
            } catch (e: Exception) {
                if (epoch.get() == generation) mutable.value = mutable.value.copy(busy = false,
                    message = "Fit unavailable: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private const val FIRST_FREQUENCY = .01
    internal fun frozenFrequencies(rate: Int, count: Int = 4097): DoubleArray {
        require(count >= 3)
        return DoubleArray(count) { i -> if (i == 0) 0.0 else
            FIRST_FREQUENCY * (rate * .5 / FIRST_FREQUENCY).pow((i - 1.0) / (count - 2)) }
    }

    internal fun interpolate(values: DoubleArray, hz: Double, rate: Int): Double {
        val x = if (hz <= FIRST_FREQUENCY) (hz / FIRST_FREQUENCY).coerceAtLeast(0.0) else
            (1 + ln(hz / FIRST_FREQUENCY) / ln(rate * .5 / FIRST_FREQUENCY) * (values.size - 2))
                .coerceAtMost(values.lastIndex.toDouble())
        val low = floor(x).toInt()
        val high = (low + 1).coerceAtMost(values.lastIndex)
        return values[low] + (values[high] - values[low]) * (x - low)
    }

    fun apply() {
        if (mutable.value.busy) return
        val plan = mutable.value.plan ?: return
        if (!app.svan.SystemEqService.isRunning) {
            mutable.value = mutable.value.copy(message = "Start Svan's system equalizer in Hi-Fi before applying fitted controls.")
            return
        }
        if (!SvanRepository.eq.value.enabled) {
            mutable.value = mutable.value.copy(message = "Enable Svan EQ before applying fitted controls.")
            return
        }
        if (signature != currentSignature()) { curveChanged(); return }
        if (CaptureService.isRunning) {
            mutable.value = mutable.value.copy(message = "Stop the audiophile capture engine in Hi-Fi before applying this system-effects experiment.")
            return
        }
        mutable.value = mutable.value.copy(busy = true, message = "Applying fitted controls through Svan's shared engine…")
        worker.submit {
            if (signature != currentSignature() || CaptureService.isRunning) {
                mutable.value = mutable.value.copy(busy = false, message = "The curve or engine changed. Fit again before applying.")
                return@submit
            }
            try {
                EqController.globalEq.applyLab(plan)
                val failed = SessionRouter.snapshot.filter { it.owner == SessionRouter.Owner.ENGINE_A }
                    .any { !EqController.globalEq.isHealthy(it.sessionId) } ||
                    (app.svan.SharedOutput.status.value.requested && !EqController.globalEq.isHealthy(0))
                if (failed) {
                    EqController.globalEq.clearLab()
                    mutable.value = mutable.value.copy(busy = false, applied = false, message = "The device refused a Lab effect. Normal Svan processing restored.")
                } else mutable.value = mutable.value.copy(busy = false, applied = true,
                    message = "Lab controls selected for Engine A. New sessions use Svan's existing detection. A curve/settings edit restores normal Svan.")
            } catch (e: Exception) {
                EqController.globalEq.clearLab()
                mutable.value = mutable.value.copy(busy = false, applied = false, message = "Normal Svan restored: ${e.message}")
            }
        }
    }

    fun restore() {
        epoch.incrementAndGet()
        task?.cancel(true)
        worker.submit {
            EqController.globalEq.clearLab()
            mutable.value = mutable.value.copy(busy = false, applied = false, message = "Normal Svan processing restored.")
        }
    }

    /** Called under the shared effect lock; publish only, avoiding a lock-order inversion. */
    fun curveChanged() {
        epoch.incrementAndGet()
        task?.cancel(true)
        mutable.value = State(message = "Svan's curve/settings changed. Normal processing is active; fit again to refresh the experiment.")
    }

    fun export(): String {
        val p = mutable.value.plan ?: error("Fit a curve first")
        return JSONObject().put("schema", "svan-integrated-lab-1")
            .put("fingerprint", Build.FINGERPRINT).put("origin", p.origin)
            .put("assumedRate", p.model.rate).put("assumedBlock", p.model.block)
            .put("applied", mutable.value.applied).put("sourceSettings", signature)
            .put("cutoffsHz", JSONArray(DoubleArray(p.gains.size) { p.cutoff(it) }.toList()))
            .put("gainsDb", JSONArray(p.gains.toList())).put("inputGainDb", p.attenuationDb)
            .put("equalizer60HzDb", p.eq0).put("equalizer230HzDb", p.eq1)
            .put("predictedBassRmsDb", p.rms).put("predictedModulationDb", p.modulationDb)
            .put("samplePeakGuarantee", false).put("truePeakGuarantee", false)
            .put("routes", JSONArray(SessionRouter.snapshot.map { "${it.pkg}#${it.sessionId}:${it.owner}" }))
            .toString(2)
    }
}
