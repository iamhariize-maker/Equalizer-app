package app.svan.svaramanas

import android.content.Context
import app.svan.CaptureService
import app.svan.EqController
import app.svan.NativeEngine
import app.svan.SvanRepository
import app.svan.model.Band
import app.svan.model.SmartLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** "What kind of sound do you want?" Ordinals match eqcore::svaramanas::Feel. */
enum class Feel(val title: String, val line: String) {
    BALANCED("Balanced", "True to the recording"),
    WARM("Warm", "Fuller body, softer top"),
    BRIGHT("Bright", "Open, airy detail"),
    PUNCHY("Punchy", "Tight, hard-hitting low end"),
    SPACIOUS("Spacious", "A wider stage, more air"),
    INTIMATE("Intimate", "The voice close to you"),
}

/** Friendly mode names for the sound guide and the automatic master. */
enum class SmartMode(val plainName: String, val sanskritName: String, val promise: String) {
    GUIDED("Sound guide", "Svaramanas", "You choose the tone and what to bring forward."),
    SVARESA("Auto master", "Svaresa", "Adapts to your volume, output and the hour; corrects what it can measure."),
}

/** Instrument categories. Bits match eqcore::svaramanas::Category. */
enum class Category(val bit: Int, val title: String) {
    VOCALS(1, "Vocals"),
    STRINGS(2, "Strings & orchestra"),
    PIANO(4, "Piano & keys"),
    GUITARS(8, "Guitars"),
    DRUMS(16, "Drums & percussion"),
    BASS(32, "Bass"),
    BRASS(64, "Brass & winds"),
    SYNTH(128, "Synth & electronic"),
    SPACE(256, "Space & ambience"),
    ;

    companion object {
        fun fromMask(mask: Int) = entries.filter { mask and it.bit != 0 }
        const val MAX = 4
    }
}

/** What the listener asked Svaramanas for. [picks] keeps pick order: the first three always win. */
data class SmartRequest(
    val enabled: Boolean = false,
    val mode: SmartMode = SmartMode.GUIDED,
    val feel: Feel = Feel.BALANCED,
    val picks: List<Category> = emptyList(),
    val strength: Double = 1.0,
    /** Svaresa adaptations (see [SvaresaBrain]); each can be switched off. */
    val night: NightMode = NightMode.AUTO,
    val volumeAware: Boolean = true,
    val routeAware: Boolean = true,
    val autoHeadphone: Boolean = true,
    /** Selective dynamic EQ is opt-in since defaults revision 2 (0.5.14). */
    val selectiveEq: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("on", enabled).put("mode", mode.name).put("feel", feel.name).put("strength", strength)
        .put("picks", JSONArray().apply { picks.forEach { put(it.name) } })
        .put("night", night.name).put("volumeAware", volumeAware).put("routeAware", routeAware).put("autoHeadphone", autoHeadphone).put("selectiveEq",selectiveEq).put("defaults", DEFAULTS_REVISION)

    companion object {
        /** 2 (0.5.14): selective dynamic EQ became opt-in; older saves stored the old default, so it starts off once. */
        const val DEFAULTS_REVISION = 2

        fun fromJson(o: JSONObject) = SmartRequest(
            enabled = o.optBoolean("on", false),
            mode = runCatching { SmartMode.valueOf(o.optString("mode", SmartMode.GUIDED.name)) }.getOrDefault(SmartMode.GUIDED),
            feel = runCatching { Feel.valueOf(o.optString("feel", Feel.BALANCED.name)) }.getOrDefault(Feel.BALANCED),
            picks = o.optJSONArray("picks")?.let { a -> List(a.length()) { a.getString(it) } }
                ?.mapNotNull { n -> Category.entries.firstOrNull { it.name == n } } ?: emptyList(),
            strength = o.optDouble("strength", 1.0).coerceIn(0.0, 1.5),
            night = runCatching { NightMode.valueOf(o.optString("night", NightMode.AUTO.name)) }.getOrDefault(NightMode.AUTO),
            volumeAware = o.optBoolean("volumeAware", true),
            routeAware = o.optBoolean("routeAware", true),
            autoHeadphone = o.optBoolean("autoHeadphone", true),
            selectiveEq = o.optInt("defaults", 1) >= DEFAULTS_REVISION && o.optBoolean("selectiveEq", false),
        )
    }
}

/** What Svaramanas heard (eqcore::SourceFeatures, packed layout). */
data class Heard(
    val valid: Boolean, val seconds: Double, val loudnessLufs: Double, val peakDbfs: Double, val plrDb: Double,
    val clipsPerSecond: Double, val correlation: Double, val sideToMidDb: Double, val monoLike: Boolean,
    val cutoffHz: Double, val tiltDbPerOct: Double, val mudDb: Double, val boomDb: Double, val harshDb: Double,
    val airDb: Double, val packed: DoubleArray,
) {
    val lossy: Boolean get() = cutoffHz in 1.0..17499.0

    companion object {
        fun from(p: DoubleArray): Heard? = if (p.size < 15) null else Heard(
            p[0] != 0.0, p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8] != 0.0, p[9], p[10], p[11], p[12], p[13], p[14], p,
        )
    }
}

/** A computed plan (eqcore::svaramanas::Plan). */
data class SmartPlan(
    val bands: List<Band>,
    val preampDb: Double,
    val predictedDeltaDb: Double,
    val bassCharacter: Double,
    val intimacy: Double,
    val smoothness: Double,
    val space: Double,
    val instruments: Double,
    val accepted: Int,
    val rejected: Int,
    val conflictWith: Int,
    val notes: List<Int>,
    val groundingRestraint: Double = 0.0,
    val groundingBody: Double = 0.0,
) {
    fun toLayer() = SmartLayer(bands, preampDb, bassCharacter, intimacy, smoothness, space, instruments,
        groundingRestraint = groundingRestraint, groundingBody = groundingBody)

    /** The measured plan plus Svaresa's context layer (quiet listening, output protection, night). */
    fun toLayer(context: ContextLayer?): SmartLayer = if (context == null) toLayer() else SmartLayer(
        bands + context.bands, preampDb + context.preampDb, bassCharacter, intimacy, smoothness, space, instruments,
        groundingRestraint, groundingBody, context.levelling,
    )

    companion object {
        /**
         * [speakerRoute]: output is the phone's own speaker (Svaresa then skips the deep-bass foundation).
         * [taste]: the listener's learned reference targets (packed TasteTarget) or null for the house voicing.
         */
        fun compute(r: SmartRequest, features: DoubleArray?, stereoEngine: Boolean,
                    speakerRoute: Boolean = false, taste: DoubleArray? = null): SmartPlan {
            val raw = NativeEngine.nativeSvaramanasPlan(
                features, r.feel.ordinal, IntArray(r.picks.size) { r.picks[it].bit }, r.strength, stereoEngine,
                r.mode == SmartMode.SVARESA, speakerRoute, taste,
            )
            var i = 12
            val nNotes = raw[11].toInt()
            val notes = List(nNotes) { raw[i + it].toInt() }
            i += nNotes
            val nBands = raw[i].toInt()
            i += 1
            val bands = List(nBands) { k ->
                val o = i + 4 * k
                Band(NativeEngine.FilterType.entries[raw[o].toInt()], raw[o + 1], raw[o + 2], raw[o + 3])
            }
            val g = i + 4 * nBands  // grounded voicing is appended after the bands
            return SmartPlan(
                bands, raw[0], raw[1], raw[2], raw[3], raw[5], raw[6], raw[7],
                raw[8].toInt(), raw[9].toInt(), raw[10].toInt(), notes,
                raw.getOrElse(g) { 0.0 }, raw.getOrElse(g + 1) { 0.0 },
            )
        }
    }
}

/**
 * Svaramanas — the tonal police with an audiophile's heart (docs/SMART.md).
 *
 * Holds the listener's request, computes the smart layer (static on system
 * effects; adaptive while the audiophile engine lets it listen) and hands it to
 * [SvanRepository], so both engines run it. Adaptive updates slew at most
 * 0.5 dB per band every 3 s: the sound drifts, it never jumps.
 */
object Svaramanas {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // Planning (native plan, graphic fit, loudness match) runs on one background thread, in order, so a
    // slider drag never waits for it. Writes to the shared EQ state and the curve engine go back to Main.
    private val planner = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "svaramanas-planner").apply { isDaemon = true }
    }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val plannerGate = app.svan.PlannerRequestGate()
    private fun requestRecompute(immediate: Boolean) {
        if (app.svan.lab.IntegratedLab.holdsAutomaticCurve) {
            if (!immediate) {
                _listening.value = CaptureService.isRunning
                _heard.value = if (CaptureService.isRunning) CaptureService.analysis()?.let(Heard::from) else null
                return // keep the fitted curve frozen while source meters continue
            }
            app.svan.lab.IntegratedLab.restore() // explicit sound edits resume normal adaptation
        }
        app.svan.EfficiencyMetrics.plannerRequest()
        if (!plannerGate.request(immediate)) return
        planner.execute {
            do {
                val urgent = plannerGate.takeImmediate()
                app.svan.EfficiencyMetrics.plannerRun()
                runCatching { recompute(urgent) }.onFailure { EqController.log("svaramanas: plan failed: $it") }
            } while (plannerGate.complete())
        }
    }
    private lateinit var prefs: android.content.SharedPreferences
    @Volatile private var initialized = false

    private val _request = MutableStateFlow(SmartRequest())
    val request: StateFlow<SmartRequest> = _request.asStateFlow()

    private val _heard = MutableStateFlow<Heard?>(null)
    val heard: StateFlow<Heard?> = _heard.asStateFlow()

    private val _plan = MutableStateFlow<SmartPlan?>(null)
    val plan: StateFlow<SmartPlan?> = _plan.asStateFlow()

    private val _listening = MutableStateFlow(false)
    /** True while the audiophile engine lets Svaramanas hear the music itself. */
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private val _context = MutableStateFlow<ContextLayer?>(null)
    /** Svaresa's current quiet-listening / output / night adaptation, or null when it is not driving. */
    val context: StateFlow<ContextLayer?> = _context.asStateFlow()

    private lateinit var appContext: Context

    private val _bubble = MutableStateFlow(false)
    /** The floating bubble over other apps (needs "Display over other apps"). */
    val bubble: StateFlow<Boolean> = _bubble.asStateFlow()

    private val _taste = MutableStateFlow<DoubleArray?>(null)
    private val _tasteTracks = MutableStateFlow(0)
    /** How many reference tracks Svaresa has learned the listener's sound from (0 = house voicing). */
    val tasteTracks: StateFlow<Int> = _tasteTracks.asStateFlow()

    /** The learned taste for renderers that must match the live plan (e.g. the release quality checks). */
    fun currentTaste(): DoubleArray? = _taste.value

    /** The live learned taste (packed), so the UI can tell which saved signature, if any, is in use. */
    val taste: StateFlow<DoubleArray?> = _taste.asStateFlow()

    private val _signatures = MutableStateFlow(TuningSignatures.empty())
    /** The nine saved tuning signature slots; null = empty slot. */
    val signatures: StateFlow<List<TuningSignature?>> = _signatures.asStateFlow()

    private fun setTaste(packed: DoubleArray?) {
        _taste.value = packed?.takeIf { it.size >= TASTE_PACKED && it[0] != 0.0 && it[1] >= 1.0 }
        _tasteTracks.value = _taste.value?.get(1)?.toInt() ?: 0
    }

    /**
     * "Learn this sound": adds the music Svaramanas is hearing right now to the listener's reference taste.
     * Only measured features are kept (on this phone), never audio. Returns a line for the UI.
     */
    fun learnFromCurrent(): String {
        val h = _heard.value
        if (!_listening.value || h == null || !h.valid) return "I need to hear the music first: play it with Hi-Fi on."
        if (h.seconds < TASTE_MIN_SECONDS) return "Let it play a little longer (%.0f of %.0f s heard).".format(h.seconds, TASTE_MIN_SECONDS)
        val before = _tasteTracks.value
        val next = NativeEngine.nativeTasteLearn(_taste.value, h.packed)
        if (next.size < TASTE_PACKED || next[1].toInt() <= before) return "That did not teach me anything new; try again in a few seconds."
        setTaste(next)
        if (initialized) prefs.edit().putString("taste", JSONArray().apply { next.forEach { put(it) } }.toString()).apply()
        EqController.log("svaramanas taste: learned tracks=%d tilt=%.2f bass=%.2f sharp=%.3f side=%.1f plr=%.1f"
            .format(next[1].toInt(), next[2], next[3], next[4], next[5], next[6]))
        requestRecompute(immediate = true)
        return if (before == 0) "Learned. Svaresa now aims for this sound instead of its house voicing."
        else "Learned. Your sound now blends ${next[1].toInt()} reference tracks."
    }

    /** Back to the built-in house voicing. UI thread. */
    fun forgetTaste() {
        setTaste(null)
        if (initialized) prefs.edit().remove("taste").apply()
        requestRecompute(immediate = true)
    }

    private fun setSignatures(next: List<TuningSignature?>) {
        _signatures.value = next
        if (initialized) prefs.edit().putString("signatures", TuningSignatures.toJson(next).toString()).apply()
    }

    /**
     * Saves the sound Svaresa currently aims for into [slot] (0..8), replacing what was there.
     * Features only; nothing about the music itself is kept. UI thread. Returns a line for the UI.
     */
    fun saveSignature(slot: Int, name: String? = null): String {
        if (slot !in 0 until TuningSignatures.SLOTS) return "Pick one of the nine slots."
        val current = _taste.value
        if (!TuningSignatures.isValid(current)) return "Nothing to save yet: tap Learn this sound while a reference track plays."
        val previous = _signatures.value[slot]
        val label = TuningSignatures.cleanName(name ?: previous?.name, slot)
        setSignatures(TuningSignatures.put(_signatures.value, slot, TuningSignature(label, current!!, System.currentTimeMillis())))
        EqController.log("svaramanas signature: saved slot ${slot + 1} \"$label\" tracks=${current[1].toInt()}")
        return if (previous == null) "Saved as \"$label\"." else "Replaced \"${previous.name}\" with your current sound."
    }

    /** Makes a saved signature the sound Svaresa aims for. UI thread. */
    fun useSignature(slot: Int): String {
        val saved = _signatures.value.getOrNull(slot) ?: return "That slot is empty."
        setTaste(saved.packed)
        if (initialized) prefs.edit().putString("taste", JSONArray().apply { saved.packed.forEach { put(it) } }.toString()).apply()
        EqController.log("svaramanas signature: using slot ${slot + 1} \"${saved.name}\" tracks=${saved.tracks}")
        requestRecompute(immediate = true)
        return "Using \"${saved.name}\", learned from ${saved.tracks} reference track${if (saved.tracks == 1) "" else "s"}."
    }

    /** UI thread. */
    fun renameSignature(slot: Int, name: String): String {
        val saved = _signatures.value.getOrNull(slot) ?: return "That slot is empty."
        setSignatures(TuningSignatures.rename(_signatures.value, slot, name))
        return "Renamed to \"${_signatures.value[slot]?.name ?: saved.name}\"."
    }

    /** Empties a slot. The sound currently in use is not changed. UI thread. */
    fun deleteSignature(slot: Int): String {
        val saved = _signatures.value.getOrNull(slot) ?: return "That slot is already empty."
        setSignatures(TuningSignatures.remove(_signatures.value, slot))
        return "Deleted \"${saved.name}\". The sound in use is unchanged."
    }

    /** Settings restore: replaces all nine slots with an already validated list. */
    fun restoreSignatures(list: List<TuningSignature?>) {
        setSignatures(List(TuningSignatures.SLOTS) { list.getOrNull(it) })
    }

    fun setBubble(context: Context, on: Boolean) {
        _bubble.value = on
        if (initialized) prefs.edit().putBoolean("bubble", on).apply()
        if (on) SvaramanasBubbleService.start(context) else SvaramanasBubbleService.stop(context)
    }

    const val UPDATE_MS = 3000L
    private const val TASTE_PACKED = 7
    private const val TASTE_MIN_SECONDS = 20.0
    private const val SLEW_DB = 0.5
    private const val CONTEXT_SLEW_DB = 2.0

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            prefs = context.applicationContext.getSharedPreferences("svaramanas", Context.MODE_PRIVATE)
            prefs.getString("request", null)?.let { s -> runCatching { _request.value = SmartRequest.fromJson(JSONObject(s)) } }
            _bubble.value = prefs.getBoolean("bubble", false)
            prefs.getString("taste", null)?.let { s -> runCatching { JSONArray(s).let { a -> setTaste(DoubleArray(a.length()) { a.getDouble(it) }) } } }
            _signatures.value = TuningSignatures.fromJson(prefs.getString("signatures", null))
            initialized = true
        }
        scope.launch {
            requestRecompute(immediate = true)
            _request.map { it.enabled }.distinctUntilChanged().collectLatest { enabled ->
                if (enabled) while (isActive) {
                    delay(UPDATE_MS)
                    requestRecompute(immediate = false)
                }
            }
        }
    }

    /** UI thread. */
    fun update(transform: (SmartRequest) -> SmartRequest) {
        val next = transform(_request.value)
        if (next.enabled && next.mode == SmartMode.SVARESA && !SvanRepository.eq.value.smartEqControl)
            SvanRepository.update { it.copy(smartEqControl=true) }
        _request.value = next
        if (initialized) prefs.edit().putString("request", next.toJson().toString()).apply()
        requestRecompute(immediate = true)
    }

    /** Hold-to-compare / bubble long-press: hear the music without the smart layer. UI thread. */
    fun setBypass(on: Boolean) {
        if (SvanRepository.eq.value.smartBypass != on) SvanRepository.update { it.copy(smartBypass = on) }
    }

    fun refreshEq() { if (initialized) requestRecompute(immediate = true) }
    private var fitInput: List<Band>? = null
    private var fitCount = 0
    private var cachedFit: NativeEngine.Companion.Fit? = null
    private var guardInput: List<Band>? = null
    private var guardScale = 1.0

    /** Planner thread only (see [requestRecompute]). */
    private fun recompute(immediate: Boolean) {
        if (app.svan.lab.IntegratedLab.holdsAutomaticCurve) return
        val r = _request.value
        if (!r.enabled) {
            _plan.value = null
            _context.value = null
            _listening.value = CaptureService.isRunning
            trimTarget = 0.0
            main.post {
                trimApplied = 0.0
                if (app.svan.lab.IntegratedLab.holdsAutomaticCurve) return@post
                if (SvanRepository.eq.value.smart != null || SvanRepository.eq.value.smartEqControl) SvanRepository.update { it.copy(smart = null, smartBypass = false, smartEqControl=false) }
                if (immediate) EqController.curveEngine.responseDb(doubleArrayOf(63.0, 1000.0)).let { c ->
                    EqController.log("svaramanas: resting response@63Hz=%.2f dB response@1kHz=%.2f dB".format(c[0], c[1]))
                }
            }
            return
        }
        val engineB = CaptureService.isRunning
        val packed = if (engineB) CaptureService.analysis() else null
        val heard = packed?.let(Heard::from)
        if (heard?.valid == true && (++heardLogs % 10 == 1)) {
            EqController.log("svaramanas heard: valid=true seconds=%.0f loudness=%.1f LUFS plr=%.1f ceiling=%.0f Hz mud=%.1f harsh=%.1f corr=%.2f"
                .format(heard.seconds, heard.loudnessLufs, heard.plrDb, heard.cutoffHz, heard.mudDb, heard.harshDb, heard.correlation))
        }
        _heard.value = heard
        _listening.value = engineB
        // Features only count once enough music was heard; before that the plan is static.
        val sensors = if (r.mode == SmartMode.SVARESA) SvaresaSensors.read(appContext, r) else null
        val speaker = sensors != null && sensors.routeAware && sensors.route == RouteKind.SPEAKER
        val p = SmartPlan.compute(r, packed?.takeIf { heard?.valid == true }, engineB, speaker,
            if (r.mode == SmartMode.SVARESA) _taste.value else null)
        if (r.mode == SmartMode.SVARESA) AutoHeadphone.check(appContext, r)
        val ctx = sensors?.let(SvaresaBrain::layer)
        _context.value = ctx
        val eq = SvanRepository.eq.value
        var target = p.toLayer(ctx).copy(protectEngine=r.mode==SmartMode.SVARESA,dynamicEq=if(r.mode==SmartMode.SVARESA&&r.selectiveEq) r.strength.coerceIn(0.0,1.0) else 0.0)
        if (eq.smartEqControl && eq.smartEqMode == app.svan.model.EqMode.GRAPHIC) {
            if (fitInput != target.bands || fitCount != eq.smartGraphicCount) {
                fitInput=target.bands; fitCount=eq.smartGraphicCount
                cachedFit=NativeEngine.fitGraphic(target.bands,eq.smartGraphicCount)
            }
            val fit=cachedFit!!
            target=target.copy(bands=fit.bands.map { Band(it.type,it.freqHz,it.gainDb,it.q,it.enabled) },
                graphicFitRmsDb=fit.rmsErrorDb, graphicFitMaxDb=fit.maxErrorDb)
        }
        if (eq.smartEqControl) {
            val personalized=eq.personalizeSmartBands(target.bands)
            if (guardInput != personalized) {
                guardInput=personalized
                guardScale=NativeEngine.overlapScale(personalized)
            }
            target=target.copy(bands=personalized.map { if(it.gainDb>0) it.copy(gainDb=it.gainDb*guardScale) else it },overlapScale=guardScale)
        }
        val prev = SvanRepository.eq.value.smart
        var drifted = if (immediate || prev == null) target else slew(prev, target)
        if (eq.smartEqControl && drifted.bands != target.bands) {
            val scale=NativeEngine.overlapScale(drifted.bands)
            drifted=drifted.copy(bands=drifted.bands.map { if(it.gainDb>0) it.copy(gainDb=it.gainDb*scale) else it },overlapScale=minOf(target.overlapScale,scale))
        }
        // Match the bands actually applied after slewing, including quiet/night
        // context. Separate preamp estimates cannot account for stacked filters.
        val bands = DoubleArray(drifted.bands.size * 5) { i ->
            val b = drifted.bands[i / 5]
            when (i % 5) { 0 -> b.type.ordinal.toDouble(); 1 -> b.freqHz; 2 -> b.gainDb; 3 -> b.q; else -> if (b.enabled) 1.0 else 0.0 }
        }
        val measured = packed?.takeIf { heard?.valid == true }
        val delta = NativeEngine.nativeSmartLoudnessDelta(bands, measured, drifted.intimacy, drifted.space, drifted.instruments)
        // The loudness trim (D4): blended from the pink-reference estimate toward the heard spectrum, then ramped by
        // [TrimRamp] so it never steps. Engine A has only the estimate, and uses it only if the listener opted in.
        val estimate = if (measured == null) delta
            else NativeEngine.nativeSmartLoudnessDelta(bands, null, drifted.intimacy, drifted.space, drifted.instruments)
        trimTarget = TrimRamp.target(-estimate, if (engineB && measured != null) -delta else null,
            TrimRamp.blendWeight(heard?.valid == true, heard?.seconds ?: 0.0),
            estimateAllowed = engineB || SvanRepository.settings.value.estimatedTrimOnSystemEffects)
        val next = drifted.copy(preampDb = trimApplied)
        val applied = p.copy(bands = if (!eq.smartEqControl || eq.smartEqMode == app.svan.model.EqMode.PARAMETRIC) next.bands.take(p.bands.size) else p.bands, preampDb = next.preampDb, predictedDeltaDb = delta,
            notes = if (abs(delta) > 0.05 && 30 !in p.notes) p.notes + 30 else p.notes)
        _plan.value = applied
        // Apply and log on Main: SvanRepository.update and the curve engine belong to the UI thread.
        main.post {
            if (app.svan.lab.IntegratedLab.holdsAutomaticCurve) return@post
            // The trim is read here, on Main, so a ramp tick between recompute and this post never steps back.
            if (next != prev) SvanRepository.update { it.copy(smart = next.copy(preampDb = trimApplied)) }
            startTrimRamp()
            if (immediate) logPlan(applied, heard)
        }
    }

    /** The trim the ramp aims for (any thread) and the trim applied (written on Main only). See [TrimRamp]. */
    @Volatile private var trimTarget = 0.0
    @Volatile private var trimApplied = 0.0
    private var trimTicking = false
    private var lastTrimTickMs = 0L
    private val trimTick = object : Runnable {
        override fun run() {
            val now = android.os.SystemClock.elapsedRealtime()
            val stepped = TrimRamp.step(trimApplied, trimTarget, (now - lastTrimTickMs) / 1000.0)
            lastTrimTickMs = now
            if (stepped != trimApplied) {
                trimApplied = stepped
                if (!app.svan.lab.IntegratedLab.holdsAutomaticCurve)
                    SvanRepository.update { s -> s.smart?.let { s.copy(smart = it.copy(preampDb = stepped)) } ?: s }
            }
            if (TrimRamp.settled(trimApplied, trimTarget)) trimTicking = false else main.postDelayed(this, TrimRamp.TICK_MS)
        }
    }

    /** Main thread. */
    private fun startTrimRamp() {
        if (trimTicking) return
        trimTicking = true
        lastTrimTickMs = android.os.SystemClock.elapsedRealtime()
        main.post(trimTick)
    }

    /** The applied trim, for the Lab readout. */
    val appliedTrimDb: Double get() = trimApplied

    private var heardLogs = 0

    private fun slew(from: SmartLayer, to: SmartLayer): SmartLayer {
        val same = from.bands.size == to.bands.size && from.bands.zip(to.bands).all { (a, b) -> a.type == b.type && a.freqHz == b.freqHz && a.q == b.q }
        if (!same) return to
        fun step(a: Double, b: Double, limit: Double) = if (abs(b - a) <= limit) b else a + limit * Math.signum(b - a)
        // The measured plan drifts slowly (0.5 dB / 3 s). The context layer (the last bands) answers a volume
        // change or a route switch within a few seconds, since the listener caused it.
        val contextStart = to.bands.size - ContextLayer.BAND_COUNT
        val hasContext = to.levelling != null && contextStart >= 0 && to.graphicFitRmsDb == null
        return to.copy(
            bands = from.bands.zip(to.bands).mapIndexed { i, (a, b) ->
                b.copy(gainDb = step(a.gainDb, b.gainDb, if (hasContext && i >= contextStart) CONTEXT_SLEW_DB else SLEW_DB))
            },
            preampDb = step(from.preampDb, to.preampDb, if (hasContext) CONTEXT_SLEW_DB else SLEW_DB),
        )
    }

    private fun logPlan(p: SmartPlan, heard: Heard?) {
        val curves = EqController.curveEngine.responseDb(doubleArrayOf(63.0, 1000.0))
        val c = _context.value
        EqController.log(
            "svaramanas plan: mode=${_request.value.mode} feel=${_request.value.feel} picks=${_request.value.picks} bands=${p.bands.count { it.gainDb != 0.0 }} " +
                "preamp=%.2f predicted=%.2f notes=${p.notes} heard=${heard?.valid ?: false} ".format(p.preampDb, p.predictedDeltaDb) +
                (c?.let { "context: bass=%+.2f treble=%+.2f night=%.2f levelling=%.2f ".format(it.bassLiftDb, it.trebleLiftDb, it.nightAmount, it.levelling) } ?: "") +
                "response@63Hz=%.2f dB response@1kHz=%.2f dB".format(curves[0], curves[1]),
        )
    }

    /** Svaramanas speaks: plain-language lines for what it heard and did. */
    fun explain(p: SmartPlan?, h: Heard?, r: SmartRequest, listening: Boolean, ctx: ContextLayer? = null): List<String> {
        if (!r.enabled) return listOf("Resting. Turn me on and tell me what you'd like to hear.")
        if (p == null) return emptyList()
        fun gainAt(f: Double) = p.bands.filter { it.freqHz == f }.sumOf { it.gainDb }
        val out = mutableListOf<String>()
        if (r.mode == SmartMode.SVARESA) {
            out += "Headroom and overload protection stay active across the engine. Your manual protection choices return when Auto master is off."
            ctx?.reasons?.let { out += it }
            when {
                !listening -> out += "Output, volume and night adaptation are live. Measured tone corrections also need Hi-Fi and a player that allows audio capture; your chosen EQ and tuners still work."
                h?.valid != true -> out += "Auto master is listening for a few seconds before making any change."
                else -> out += "Auto master is checking the full stereo mix and correcting measured masking, harshness and tonal imbalance."
            }
        } else if (!listening) out += "On system effects I shape by your choices. Turn on Hi-Fi and I'll listen to the music itself."
        else if (h?.valid != true) out += "Listening… give me a few seconds of music and I'll fine-tune."
        for (n in p.notes) when (n) {
            1 -> out += "Shaping toward a ${r.feel.title.lowercase()} sound."
            2 -> out += "Bringing forward ${Category.fromMask(p.accepted).joinToString { it.title.lowercase() }}."
            10 -> out += "The low-mids were crowding the mix: eased %.1f dB around 300 Hz.".format(-gainAt(300.0))
            11 -> out += "The bass was booming: tightened %.1f dB around 90 Hz.".format(-gainAt(90.0))
            12 -> out += "The upper mids were turning shrill: smoothed %.1f dB at 3.5 kHz and held back presence lifts.".format(-gainAt(3500.0))
            13 -> out += "The top end was dull for a full-range file, so I opened the air a little."
            17 -> out += "The mix is thinner/brighter than a healthy balance: eased the top and restored body."
            18 -> out += "The mix is darker/heavier than a healthy balance: opened the top and relieved the low-mid body."
            19 -> out += "Grounding: easing top-end spikes (%.0f%%) and adding low-mid weight (%.0f%%) so the voice and rhythm keep their body.".format(p.groundingRestraint * 100, p.groundingBody * 100)
            24 -> out += "House voicing: foundation %+.1f dB below 65 Hz, warmth %+.1f dB at 180 Hz, top %+.1f dB above 8.5 kHz, level-matched.".format(gainAt(65.0), gainAt(180.0), gainAt(8500.0))
            25 -> out += "This master is heavily limited, so I gave the bass a little of its punch back."
            26 -> out += "The mix is narrow, so I opened a little side ambience for atmosphere (mono files are never widened)."
            27 -> out += "Aiming for your learned sound (${tasteTracks.value} reference track${if (tasteTracks.value == 1) "" else "s"}) instead of the house voicing."
            14 -> out += "This stream stops near %.1f kHz (lossy). I won't lift anything near that ceiling; it would only amplify codec artefacts.".format((h?.cutoffHz ?: 0.0) / 1000)
            15 -> out += "This master is heavily limited or clipping, so I halved every lift. More would only distort."
            16 -> out += "This track has no real stereo, so I skipped widening."
            20 -> out += "Your picks asked for a lot, so I fit them into a 6 dB emphasis budget."
            21 -> out += "${Category.fromMask(p.rejected).joinToString { it.title }} clashes with ${Category.fromMask(p.conflictWith).joinToString { it.title }} in the same range. I kept your first picks."
            22 -> out += "Overlapping picks share their range; the later one yields."
            30 -> out += (if (h?.valid == true) "Source-based level trim (%+.1f dB), including instrument focus and intimacy." else "Estimated level trim (%+.1f dB) against a reference spectrum; live matching needs captured audio.").format(p.preampDb)
        }
        if (r.mode == SmartMode.SVARESA && h?.valid != true && listening) out += "No automatic change yet; I need a capturable music session first."
        else if (out.isEmpty() || (p.notes.none { it in 10..18 } && h?.valid == true)) {
            out += if (r.mode == SmartMode.SVARESA) "No measured mix correction needed. Output, volume and night settings remain active." else "The mix sounds healthy. Nothing to police."
        }
        return out
    }
}
