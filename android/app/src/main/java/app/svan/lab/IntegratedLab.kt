package app.svan.lab

import android.content.Context
import android.media.audiofx.AudioEffect
import android.os.Build
import app.svan.CaptureService
import app.svan.EqController
import app.svan.SessionRouter
import app.svan.SvanRepository
import app.svan.NativeEngine
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

/** Frozen opt-in fits for Engine A and capture's distinct native static-EQ path. */
object IntegratedLab {
    data class State(val busy: Boolean = false, val plan: Planner.Plan? = null, val applied: Boolean = false,
        val message: String = "Fit your current Svan curve, then compare the reference prediction.",
        val capturePlans: Map<Int, CaptureLabControls> = emptyMap(), val frozenCurve: Boolean = false)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    val holdsAutomaticCurve: Boolean get() = mutable.value.let { it.busy || it.applied || it.frozenCurve }
    val hasFrozenReadyFit: Boolean get() = mutable.value.let { it.frozenCurve && !it.busy && it.plan != null }
    private val worker = Executors.newSingleThreadExecutor()
    private val epoch = AtomicLong()
    private var task: Future<*>? = null
    private var signature = ""
    @Volatile private var selectedCapture: Map<Int, CaptureLabControls> = emptyMap()
    private val captureRevision = AtomicLong()
    val captureSelectionId: Long get() = captureRevision.get()
    fun captureControls(rate: Int): CaptureLabControls? = selectedCapture[rate]
    private fun clearCapture() {
        // Fitting while normal processing is active, or repeating Restore, changes
        // no audio controls and must not reset filters or rebuffer the audio epoch.
        if (selectedCapture.isEmpty()) return
        selectedCapture = emptyMap()
        captureRevision.incrementAndGet()
    }
    // Saved JSON deliberately omits live smart layers; a fit must include them in its identity.
    private fun currentSignature() = SvanRepository.eq.value.toString() + SvanRepository.settings.value.copy(
        // Choosing a route does not change the fitted audio. Keep a preselected experiment when
        // switching from system-only to capture, or enabling/disabling a mix-fallback preference.
        engineMode = app.svan.model.EngineMode.SYSTEM_ONLY, wholeMixFallback = false,
    ).toJson().toString()
    fun matchesCurrentSource(): Boolean = signature == currentSignature()

    @Synchronized
    fun fit(context: Context, rate: Int, block: Int, hybrid: Boolean, margin: Double) {
        require(rate == 44100 || rate == 48000)
        require(block in listOf(2048, 4096, 8192))
        require(margin.isFinite() && margin in 3.0..18.0)
        val generation = epoch.incrementAndGet()
        task?.cancel(true)
        val source = currentSignature()
        val sourceEq = SvanRepository.eq.value
        val sourceSettings = SvanRepository.settings.value
        val frequencies = frozenFrequencies(rate)
        // Curve engine uses the declared 48k reference. At 44.1k, retain the requested frequency response;
        // this is a control fit, not a claim about Android's actual output rate.
        val target = EqController.curveEngine.responseDb(frequencies)
        if (target.size != frequencies.size || target.any { !it.isFinite() || it !in -18.0..12.0 }) {
            mutable.value = State(message = "This curve exceeds the Lab's −18 to +12 dB fit range. Reduce extreme cuts/boosts or preamp first.")
            return
        }
        mutable.value = mutable.value.copy(busy = true, applied = false, frozenCurve = true, message = "Fitting the frozen combined curve…")
        val app = context.applicationContext
        task = worker.submit {
            try {
                clearCapture()
                EqController.globalEq.clearLab()
                val model = Planner.Model.read(app.assets.open("lab/models/${rate}_${block}.bin")).reduce(64)
                val table = Planner.EqTable(app.assets.open("lab/eq_coefficients.csv"))
                val referenceEq = AudioEffect.queryEffects().any { it.uuid.toString() == "ce772f20-847d-11df-bb17-0002a5d5c51b" }
                val plan = Planner(table).planResponse(model, { hz -> interpolate(target, hz, rate) }, hybrid && referenceEq, margin)
                // Native capture has real M/S and bass processors. Fit only its own static bands,
                // preamp and headroom, excluding the system engine's stereo stand-in filters.
                val capturePlans = listOf(44100, 48000).associateWith { captureRate ->
                    val captureFrequencies = frozenFrequencies(captureRate)
                    val effective = sourceSettings.effectiveFor(sourceEq)
                    val capturedTarget = NativeEngine(captureRate, 2,
                        oversample = sourceSettings.oversampleAt(captureRate), stopbandDb = sourceSettings.quality.stopbandDb,
                        ditherBits = 0, ditherMode = 0, autoHeadroom = effective.autoHeadroom,
                        gainProtection = effective.gainProtection).use { engine ->
                        engine.setBands(sourceEq.effectiveBands().map { it.toNative() })
                        engine.setPreampDb(sourceEq.effectivePreampDb())
                        engine.responseDb(captureFrequencies)
                    }
                    require(capturedTarget.all { it.isFinite() && it in -18.0..12.0 }) {
                        "Capture's static curve exceeds the Lab fit range"
                    }
                    val captureModel = if (captureRate == rate) model else
                        Planner.Model.read(app.assets.open("lab/models/${captureRate}_${block}.bin")).reduce(64)
                    CaptureLabControls(Planner(table).planResponse(captureModel,
                        { hz -> interpolate(capturedTarget, hz, captureRate) }, hybrid, margin), table)
                }
                if (epoch.get() != generation || Thread.currentThread().isInterrupted) return@submit
                if (source != currentSignature()) {
                    mutable.value = mutable.value.copy(busy = false, frozenCurve = false, message = "Your curve changed during fitting. Fit again when it is settled.")
                    return@submit
                }
                signature = source
                mutable.value = State(plan = plan, capturePlans = capturePlans, frozenCurve = true,
                    message = if (hybrid && !referenceEq) "DP-only fit ready; automatic curve held. This Equalizer has no verified reference model. Apply or Restore to finish the comparison."
                        else "Reference fit ready; automatic curve held while you review it. Apply or Restore to finish the comparison. Device response, latency and peaks remain unmeasured.")
            } catch (e: Exception) {
                if (epoch.get() == generation) mutable.value = mutable.value.copy(busy = false, frozenCurve = false,
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
        if (!app.svan.SystemEqService.isRunning && !CaptureService.isRunning) {
            mutable.value = mutable.value.copy(message = "Start Svan's system equalizer in Hi-Fi before applying fitted controls.")
            return
        }
        if (!SvanRepository.eq.value.enabled) {
            mutable.value = mutable.value.copy(message = "Enable Svan EQ before applying fitted controls.")
            return
        }
        if (signature != currentSignature()) { curveChanged(); return }
        val capturePlans = mutable.value.capturePlans
        val generation = epoch.get()
        mutable.value = mutable.value.copy(busy = true, message = "Applying fitted controls through Svan's shared engine…")
        worker.submit {
            if (signature != currentSignature()) {
                mutable.value = mutable.value.copy(busy = false, frozenCurve = false, message = "The curve or engine changed. Fit again before applying.")
                return@submit
            }
            try {
                EqController.globalEq.applyLab(plan)
                if (generation != epoch.get() || signature != currentSignature()) {
                    EqController.globalEq.clearLab()
                    return@submit
                }
                val failed = SessionRouter.snapshot.filter { it.owner == SessionRouter.Owner.ENGINE_A }
                    .any { !EqController.globalEq.isHealthy(it.sessionId) } ||
                    (app.svan.SharedOutput.status.value.requested && !EqController.globalEq.isHealthy(0))
                if (failed) {
                    clearCapture()
                    EqController.globalEq.clearLab()
                    mutable.value = mutable.value.copy(busy = false, applied = false, frozenCurve = false, message = "The device refused a Lab effect. Normal Svan processing restored.")
                } else {
                    selectedCapture = capturePlans
                    captureRevision.incrementAndGet()
                    mutable.value = mutable.value.copy(busy = false, applied = true,
                        message = "Lab selected for system effects and 44.1/48 kHz capture. The automatic curve is held for this frozen comparison; native dynamic processors remain active. Applying live briefly rebuffers audio. Edit sound or restore to resume automatic curve updates.")
                }
            } catch (e: Exception) {
                clearCapture()
                EqController.globalEq.clearLab()
                mutable.value = mutable.value.copy(busy = false, applied = false, frozenCurve = false, message = "Normal Svan restored: ${e.message}")
            }
        }
    }

    fun restore() {
        epoch.incrementAndGet()
        task?.cancel(true)
        clearCapture()
        mutable.value = mutable.value.copy(busy = false, applied = false, frozenCurve = false, message = "Normal Svan processing restored; automatic curve updates resume.")
        worker.submit {
            EqController.globalEq.clearLab()
        }
    }

    /** Called under the shared effect lock; publish only, avoiding a lock-order inversion. */
    fun curveChanged() {
        epoch.incrementAndGet()
        task?.cancel(true)
        clearCapture()
        mutable.value = State(message = "Svan's curve/settings changed. Normal processing is active; fit again to refresh the experiment.")
    }

    fun export(): String {
        val p = mutable.value.plan ?: error("Fit a curve first")
        val capture = CaptureService.epoch
        return JSONObject().put("schema", "svan-integrated-lab-2")
            .put("fingerprint", Build.FINGERPRINT).put("origin", p.origin)
            .put("assumedRate", p.model.rate).put("assumedBlock", p.model.block)
            .put("applied", mutable.value.applied).put("sourceSettings", signature)
            .put("cutoffsHz", JSONArray(DoubleArray(p.gains.size) { p.cutoff(it) }.toList()))
            .put("gainsDb", JSONArray(p.gains.toList())).put("inputGainDb", p.attenuationDb)
            .put("equalizer60HzDb", p.eq0).put("equalizer230HzDb", p.eq1)
            .put("predictedBassRmsDb", p.rms).put("predictedModulationDb", p.modulationDb)
            .put("samplePeakGuarantee", false).put("truePeakGuarantee", false)
            .put("capture", JSONObject().put("active", capture != null)
                .put("rate", capture?.sampleRate).put("labBlock", capture?.labBlock)
                .put("hybrid", capture?.labHybrid).put("latencyFrames", capture?.latencyFrames)
                .put("realization", "Original native symmetric sqrt-Hann WOLA, N/2 hop; reference bass biquads before WOLA; existing native processors and protection after WOLA")
                .put("staticEqReplaced", capture?.labBlock != null)
                .put("plans", JSONArray(mutable.value.capturePlans.values.map { c ->
                    JSONObject().put("rate", c.rate).put("block", c.block).put("hybrid", c.plan.hybrid)
                        .put("bassRmsDb", c.plan.rms).put("modulationDb", c.plan.modulationDb)
                        .put("inputGainDb", c.inputGainDb).put("stops", JSONArray(c.stops.toList()))
                        .put("frequenciesHz", JSONArray(c.plan.model.frequencies.toList()))
                        .put("predictedDb", JSONArray(c.plan.curve.toList()))
                        .put("gainsDb", JSONArray(c.gains.toList())).put("coefficients", JSONArray(c.coefficients.toList()))
                })))
            .put("routes", JSONArray(SessionRouter.snapshot.map { "${it.pkg}#${it.sessionId}:${it.owner}" }))
            .toString(2)
    }
}
