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
) {
    fun toJson(): JSONObject = JSONObject().put("on", enabled).put("mode", mode.name).put("feel", feel.name).put("strength", strength)
        .put("picks", JSONArray().apply { picks.forEach { put(it.name) } })
        .put("night", night.name).put("volumeAware", volumeAware).put("routeAware", routeAware).put("autoHeadphone", autoHeadphone)

    companion object {
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
) {
    fun toLayer() = SmartLayer(bands, preampDb, bassCharacter, intimacy, smoothness, space, instruments)

    /** The measured plan plus Svaresa's context layer (quiet listening, output protection, night). */
    fun toLayer(context: ContextLayer?): SmartLayer = if (context == null) toLayer() else SmartLayer(
        bands + context.bands, preampDb + context.preampDb, bassCharacter, intimacy, smoothness, space, instruments, context.levelling,
    )

    companion object {
        fun compute(r: SmartRequest, features: DoubleArray?, stereoEngine: Boolean): SmartPlan {
            val raw = NativeEngine.nativeSvaramanasPlan(
                features, r.feel.ordinal, IntArray(r.picks.size) { r.picks[it].bit }, r.strength, stereoEngine,
                r.mode == SmartMode.SVARESA,
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
            return SmartPlan(
                bands, raw[0], raw[1], raw[2], raw[3], raw[5], raw[6], raw[7],
                raw[8].toInt(), raw[9].toInt(), raw[10].toInt(), notes,
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

    fun setBubble(context: Context, on: Boolean) {
        _bubble.value = on
        if (initialized) prefs.edit().putBoolean("bubble", on).apply()
        if (on) SvaramanasBubbleService.start(context) else SvaramanasBubbleService.stop(context)
    }

    const val UPDATE_MS = 3000L
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
            initialized = true
        }
        scope.launch {
            recompute(immediate = true)
            while (true) {
                delay(UPDATE_MS)
                if (_request.value.enabled) recompute(immediate = false)
            }
        }
    }

    /** UI thread. */
    fun update(transform: (SmartRequest) -> SmartRequest) {
        val next = transform(_request.value)
        _request.value = next
        if (initialized) prefs.edit().putString("request", next.toJson().toString()).apply()
        recompute(immediate = true)
    }

    /** Hold-to-compare / bubble long-press: hear the music without the smart layer. UI thread. */
    fun setBypass(on: Boolean) {
        if (SvanRepository.eq.value.smartBypass != on) SvanRepository.update { it.copy(smartBypass = on) }
    }

    /** Main thread. */
    private fun recompute(immediate: Boolean) {
        val r = _request.value
        if (!r.enabled) {
            _plan.value = null
            _context.value = null
            _listening.value = CaptureService.isRunning
            if (SvanRepository.eq.value.smart != null) SvanRepository.update { it.copy(smart = null, smartBypass = false) }
            if (immediate) EqController.curveEngine.responseDb(doubleArrayOf(63.0, 1000.0)).let { c ->
                EqController.log("svaramanas: resting response@63Hz=%.2f dB response@1kHz=%.2f dB".format(c[0], c[1]))
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
        val p = SmartPlan.compute(r, packed?.takeIf { heard?.valid == true }, engineB)
        if (r.mode == SmartMode.SVARESA) AutoHeadphone.check(appContext, r)
        val ctx = if (r.mode == SmartMode.SVARESA) SvaresaBrain.layer(SvaresaSensors.read(appContext, r)) else null
        _context.value = ctx
        val target = p.toLayer(ctx)
        val prev = SvanRepository.eq.value.smart
        val drifted = if (immediate || prev == null) target else slew(prev, target)
        // Match the bands actually applied after slewing, including quiet/night
        // context. Separate preamp estimates cannot account for stacked filters.
        val bands = DoubleArray(drifted.bands.size * 5) { i ->
            val b = drifted.bands[i / 5]
            when (i % 5) { 0 -> b.type.ordinal.toDouble(); 1 -> b.freqHz; 2 -> b.gainDb; 3 -> b.q; else -> if (b.enabled) 1.0 else 0.0 }
        }
        val delta = NativeEngine.nativeSmartLoudnessDelta(bands, packed?.takeIf { heard?.valid == true },
            drifted.intimacy, drifted.space, drifted.instruments)
        val next = drifted.copy(preampDb = (-delta).coerceIn(-18.0, 1.5))
        val applied = p.copy(preampDb = next.preampDb, predictedDeltaDb = delta,
            notes = if (abs(delta) > 0.05 && 30 !in p.notes) p.notes + 30 else p.notes)
        _plan.value = applied
        if (next != prev) SvanRepository.update { it.copy(smart = next) }
        if (immediate) logPlan(applied, heard)
    }

    private var heardLogs = 0

    private fun slew(from: SmartLayer, to: SmartLayer): SmartLayer {
        val same = from.bands.size == to.bands.size && from.bands.zip(to.bands).all { (a, b) -> a.type == b.type && a.freqHz == b.freqHz && a.q == b.q }
        if (!same) return to
        fun step(a: Double, b: Double, limit: Double) = if (abs(b - a) <= limit) b else a + limit * Math.signum(b - a)
        // The measured plan drifts slowly (0.5 dB / 3 s). The context layer (the last bands) answers a volume
        // change or a route switch within a few seconds, since the listener caused it.
        val contextStart = to.bands.size - ContextLayer.BAND_COUNT
        val hasContext = to.levelling != null && contextStart >= 0
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
