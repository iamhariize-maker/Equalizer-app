package app.svan.model

import app.svan.NativeEngine
import app.svan.NativeEngine.FilterType
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** One parametric band. Frequencies in Hz, gains in dB. */
data class Band(
    val type: FilterType = FilterType.PEAK,
    val freqHz: Double = 1000.0,
    val gainDb: Double = 0.0,
    val q: Double = 1.0,
    val enabled: Boolean = true,
) {
    val hasGain: Boolean get() = type == FilterType.PEAK || type == FilterType.LOW_SHELF || type == FilterType.HIGH_SHELF

    fun toNative() = NativeEngine.Band(type, freqHz, gainDb, q, enabled)

    fun toJson(): JSONObject = JSONObject()
        .put("t", type.name).put("f", freqHz).put("g", gainDb).put("q", q).put("e", enabled)

    companion object {
        fun fromJson(o: JSONObject) = Band(
            FilterType.valueOf(o.optString("t", FilterType.PEAK.name)),
            o.optDouble("f", 1000.0), o.optDouble("g", 0.0), o.optDouble("q", 1.0), o.optBoolean("e", true),
        )
    }
}

enum class EqMode { PARAMETRIC, GRAPHIC }

data class CorrectionCalibration(val amount: Double = 1.0, val maxErrorDb: Double = 0.0,
    val lowHz: Double = 20.0, val highHz: Double = 20000.0, val basis: String = "Published profile",
    val measurementHash: String = "", val targetHash: String = "") {
    fun toJson()=JSONObject().put("amount",amount).put("max",maxErrorDb).put("low",lowHz).put("high",highHz)
        .put("basis",basis).put("measurement",measurementHash).put("target",targetHash)
    companion object {
        fun fromJson(o: JSONObject)=CorrectionCalibration((o.optDouble("amount",1.0).takeIf(Double::isFinite) ?: 1.0).coerceIn(0.0,1.0),o.optDouble("max",0.0),o.optDouble("low",20.0),o.optDouble("high",20000.0),o.optString("basis","Published profile"),o.optString("measurement"),o.optString("target"))
    }
}

/** Headphone correction layer (from AutoEq data), applied under the user's EQ. */
data class Tuning(
    val enabled: Boolean = true,
    val headphone: String,
    val source: String,
    val signature: String,
    val bands: List<Band>,
    val fitRmsDb: Double,
    val bassDb: Double = 0.0,
    val tiltDbPerOct: Double = 0.0,
    /** How to find the data again for re-tuning: "<source>|<form>|<rig>|<name>|<resultPath>". */
    val ref: String = "",
    val calibration: CorrectionCalibration? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("on", enabled).put("hp", headphone).put("src", source).put("ref", ref)
        .put("sig", signature).put("rms", fitRmsDb).put("bass", bassDb).put("tilt", tiltDbPerOct)
        .put("bands", JSONArray().apply { bands.forEach { put(it.toJson()) } }).apply { calibration?.let { put("calibration",it.toJson()) } }

    companion object {
        fun fromJson(o: JSONObject) = Tuning(
            o.optBoolean("on", true), o.getString("hp"), o.optString("src"), o.optString("sig"),
            o.optJSONArray("bands")?.let { a -> List(a.length()) { Band.fromJson(a.getJSONObject(it)) } } ?: emptyList(),
            o.optDouble("rms", 0.0), o.optDouble("bass", 0.0), o.optDouble("tilt", 0.0), o.optString("ref"),
            o.optJSONObject("calibration")?.let { CorrectionCalibration.fromJson(it) },
        )
    }
}

/**
 * Bass tuner. Level and depth are EQ (both engines); feel is the time-domain
 * bass shaper (exact in the audiophile engine, approximated on system effects).
 */
data class BassTuner(
    val amountDb: Double = 0.0,     // -6..+12
    val focusHz: Double = 80.0,     // 40 (deep sub) .. 160 (mid-bass)
    val character: Double = 0.0,    // -1 sustain/boom .. +1 punch/tight
) {
    val isOff: Boolean get() = amountDb == 0.0 && character == 0.0

    /** Upper edge of the band the shaper works on. */
    val crossoverHz: Double get() = (focusHz * 1.8).coerceIn(80.0, 220.0)

    fun bands(): List<Band> = buildList {
        if (amountDb != 0.0) add(Band(FilterType.LOW_SHELF, focusHz, amountDb, 0.71))
        // Precision: clear the upper-bass "mud" region. Bloom: a soft resonance above the focus.
        if (character > 0) add(Band(FilterType.PEAK, 250.0, -2.0 * character, 1.0))
        if (character < 0) add(Band(FilterType.PEAK, (focusHz * 1.3).coerceAtMost(200.0), 2.0 * -character, 1.2))
    }

    fun toJson(): JSONObject = JSONObject().put("amt", amountDb).put("focus", focusHz).put("char", character)

    companion object {
        fun fromJson(o: JSONObject) = BassTuner(o.optDouble("amt", 0.0), o.optDouble("focus", 80.0), o.optDouble("char", 0.0))

        val PRESETS = listOf(
            "Off" to BassTuner(),
            // Starter presets are deliberately restrained; users can add more
            // after listening instead of jumping straight to large boosts.
            "Clean impact" to BassTuner(1.5, 75.0, 0.4),
            "Full impact" to BassTuner(2.5, 68.0, 0.55),
            "Deep & warm" to BassTuner(2.0, 55.0, -0.25),
            "Punchy" to BassTuner(2.0, 85.0, 0.45),
            "Tight & precise" to BassTuner(1.5, 70.0, 0.6),
            "Club rumble" to BassTuner(2.5, 45.0, -0.35),
            "Bass-light fix" to BassTuner(2.0, 110.0, 0.2),
        )
    }
}

/** Everything the EQ screen edits. */
data class EqState(
    val enabled: Boolean = true,
    val mode: EqMode = EqMode.PARAMETRIC,
    val bands: List<Band> = DEFAULT_BANDS,
    val graphicCount: Int = 10,
    val graphicGains: List<Double> = List(10) { 0.0 },
    val graphicShelfEnds: Boolean = true,
    /** Svaresa owns the EQ band workspace; the manual curve stays stored separately. */
    val smartEqControl: Boolean = false,
    val smartEqMode: EqMode = EqMode.PARAMETRIC,
    val smartGraphicCount: Int = 31,
    val smartEqOffsets: Map<String, Double> = emptyMap(),
    val preampDb: Double = 0.0,
    val presetName: String = "Flat",
    val tuning: Tuning? = null,
    val bass: BassTuner = BassTuner(),
    val vocal: VocalTuner = VocalTuner(),
    val instrument: InstrumentTuner = InstrumentTuner(),
    /** Svaramanas's smart layer (recomputed live, never persisted). */
    val smart: SmartLayer? = null,
    /** Hold-to-compare: the smart layer is skipped while true. */
    val smartBypass: Boolean = false,
) {
    val workspaceMode: EqMode get() = if (smartEqControl) smartEqMode else mode
    val workspaceGraphicCount: Int get() = if (smartEqControl) smartGraphicCount else graphicCount

    /** The smart layer the engines should run right now, if any. */
    val activeSmart: SmartLayer? get() = if (enabled && !smartBypass) smart else null
    /** Protection remains linked during compare/EQ bypass; it is not a tone effect. */
    val smartProtection: Boolean get() = smart?.protectEngine == true
    val dynamicEq: Double get() = activeSmart?.dynamicEq ?: 0.0

    /** Built-in Flat is a complete audible reset, including independent layers. */
    fun withPreset(p: Preset): EqState = if (p.builtIn && p.name == "Flat") EqState(smart = smart, smartBypass = smartBypass) else
        copy(mode = EqMode.PARAMETRIC, bands = p.bands, preampDb = p.preampDb, presetName = p.name, enabled = true)

    /** Bands for system effects: the same layers plus static stand-ins for the vocal tuner. */
    fun systemEffectsBands(): List<Band> = if (!enabled) emptyList() else effectiveBands() + vocal.systemEffectsBands()

    /** Your tuners, with Svaramanas's suggestions added where you left room (yours always win). */
    val activeVocal: VocalTuner get() = if (!enabled) VocalTuner() else activeSmart?.let {
        VocalTuner(maxOf(vocal.intimacy, it.intimacy), vocal.warmth, maxOf(vocal.smoothness, if(it.dynamicEq>0) 0.0 else it.smoothness))
    } ?: vocal
    /** System effects cannot run native dynamic EQ; keep their automatic smoothing. */
    val systemSmoothness: Double get() = if (!enabled) 0.0 else maxOf(vocal.smoothness, activeSmart?.smoothness ?: 0.0)
    val activeInstrument: InstrumentTuner get() = if (!enabled) InstrumentTuner() else activeSmart?.let {
        InstrumentTuner(if (instrument.space != 0.0) instrument.space else it.space, maxOf(instrument.instruments, it.instruments))
    } ?: instrument

    /** The user's own EQ layer (parametric or graphic). */
    fun manualBands(): List<Band> = if (mode == EqMode.PARAMETRIC) bands else GraphicLayout.bands(graphicCount, graphicGains, graphicShelfEnds)

    /** The bands the engines actually run: headphone tuning + your EQ + bass tuner. */
    fun effectiveBands(): List<Band> = if (!enabled) emptyList() else
        (tuning?.takeIf { it.enabled }?.bands ?: emptyList()) + (if (smartEqControl) emptyList() else manualBands()) + bass.bands() +
            (activeSmart?.bands ?: emptyList())

    /**
     * Svaresa's level-evening request for system effects; null when Svaresa is not driving dynamics.
     * Hold-to-compare and the EQ switch give 0.0 (neutral), not null: the compressor stage must stay
     * configured, or every system effect would be torn down and re-created (an audible gap) on each press.
     */
    val levelling: Double? get() = smart?.levelling?.let { if (activeSmart != null) it else 0.0 }

    /** Bass shaper amount the engines should run (0 when the EQ is off). */
    val bassCharacter: Double get() = if (enabled) (bass.character + (activeSmart?.bassCharacter ?: 0.0)).coerceIn(-1.0, 1.0) else 0.0

    /** Your preamp plus Svaramanas's loudness-matching trim. */
    fun effectivePreampDb(): Double = if (enabled) preampDb + (activeSmart?.preampDb ?: 0.0) else 0.0

    fun personalizeSmartBands(bands: List<Band>): List<Band> = bands.map { b ->
        val key = if (smartEqMode == EqMode.GRAPHIC) "g${smartGraphicCount}:${smartBandKey(b)}" else smartBandKey(b)
        val offset = smartEqOffsets[key]?.takeIf { it.isFinite() }?.coerceIn(-3.0, 3.0) ?: 0.0
        b.copy(gainDb = (b.gainDb + offset).coerceIn(-12.0, 12.0))
    }

    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled).put("mode", mode.name)
        .put("bands", JSONArray().apply { bands.forEach { put(it.toJson()) } })
        .put("gCount", graphicCount).put("gShelves", graphicShelfEnds)
        .put("smartEqMode",smartEqMode.name).put("smartGraphicCount",smartGraphicCount)
        .put("smartEqControl", smartEqControl).put("smartEqOffsets", JSONObject(smartEqOffsets))
        .put("gGains", JSONArray().apply { graphicGains.forEach { put(it) } })
        .put("preamp", preampDb).put("preset", presetName)
        .put("bass", bass.toJson()).put("vocal", vocal.toJson()).put("inst", instrument.toJson())
        .apply { tuning?.let { put("tuning", it.toJson()) } }

    companion object {
        fun smartBandKey(b: Band) = "${b.type.name}:${b.freqHz}:${b.q}"

        /** A neutral starting layout: five 0 dB bands to grab and drag. */
        val DEFAULT_BANDS = listOf(
            Band(FilterType.LOW_SHELF, 80.0, 0.0, 0.71),
            Band(FilterType.PEAK, 250.0, 0.0, 1.0),
            Band(FilterType.PEAK, 1000.0, 0.0, 1.0),
            Band(FilterType.PEAK, 4000.0, 0.0, 1.0),
            Band(FilterType.HIGH_SHELF, 10000.0, 0.0, 0.71),
        )

        fun fromJson(o: JSONObject): EqState {
            val bands = o.optJSONArray("bands")?.let { a -> List(a.length()) { Band.fromJson(a.getJSONObject(it)) } } ?: DEFAULT_BANDS
            val count = o.optInt("gCount", 10).takeIf { it in GraphicLayout.COUNTS } ?: 10
            val gains = o.optJSONArray("gGains")?.let { a -> List(a.length()) { a.getDouble(it) } }
                ?.takeIf { it.size == count } ?: List(count) { 0.0 }
            return EqState(
                enabled = o.optBoolean("enabled", true),
                mode = runCatching { EqMode.valueOf(o.getString("mode")) }.getOrDefault(EqMode.PARAMETRIC),
                bands = bands,
                graphicCount = count,
                graphicGains = gains,
                graphicShelfEnds = o.optBoolean("gShelves", false), // retain legacy all-bell sound until converted
                smartEqControl = o.optBoolean("smartEqControl", false),
                smartEqMode = runCatching { EqMode.valueOf(o.optString("smartEqMode","PARAMETRIC")) }.getOrDefault(EqMode.PARAMETRIC),
                smartGraphicCount = o.optInt("smartGraphicCount",31).takeIf { it in GraphicLayout.COUNTS } ?: 31,
                smartEqOffsets = o.optJSONObject("smartEqOffsets")?.let { a ->
                    a.keys().asSequence().associateWith { a.optDouble(it, 0.0).takeIf(Double::isFinite)?.coerceIn(-3.0,3.0) ?: 0.0 }
                } ?: emptyMap(),
                preampDb = o.optDouble("preamp", 0.0),
                presetName = o.optString("preset", "Custom"),
                tuning = o.optJSONObject("tuning")?.let { runCatching { Tuning.fromJson(it) }.getOrNull() },
                bass = o.optJSONObject("bass")?.let { BassTuner.fromJson(it) } ?: BassTuner(),
                vocal = o.optJSONObject("vocal")?.let { VocalTuner.fromJson(it) } ?: VocalTuner(),
                instrument = o.optJSONObject("inst")?.let { InstrumentTuner.fromJson(it) } ?: InstrumentTuner(),
            )
        }
    }
}

/**
 * Svaramanas's smart layer: a few bounded bands, a loudness-matching trim and
 * gentle tuner suggestions (see core/include/eqcore/svaramanas.h).
 */
data class SmartLayer(
    val bands: List<Band>,
    val preampDb: Double,
    val bassCharacter: Double = 0.0,
    val intimacy: Double = 0.0,
    val smoothness: Double = 0.0,
    val space: Double = 0.0,
    val instruments: Double = 0.0,
    /** Svaresa's grounded voicing (docs/SONIC_IDENTITY.md): HF transient restraint and low-mid body, each 0..1. */
    val groundingRestraint: Double = 0.0,
    val groundingBody: Double = 0.0,
    /** Svaresa's level-evening amount 0..1 (system effects' compressor); null = not part of this layer. */
    val levelling: Double? = null,
    val graphicFitRmsDb: Double? = null,
    val graphicFitMaxDb: Double? = null,
    val overlapScale: Double = 1.0,
    val protectEngine: Boolean = false,
    val dynamicEq: Double = 0.0,
)

/**
 * Vocal tuner (centre/mid channel). Exact in the audiophile engine; on system
 * effects warmth/intimacy become a gentle EQ on both channels and smoothness a
 * multiband compressor on the 2.5-6 kHz band.
 */
data class VocalTuner(
    val intimacy: Double = 0.0,   // 0..1
    val warmth: Double = 0.0,     // 0..1
    val smoothness: Double = 0.0, // 0..1
) {
    val isOff: Boolean get() = intimacy == 0.0 && warmth == 0.0 && smoothness == 0.0

    /** Static approximation for system effects (no mid/side there): half strength, both channels. */
    fun systemEffectsBands(): List<Band> = buildList {
        if (warmth > 0) {
            add(Band(FilterType.PEAK, 220.0, 1.5 * warmth, 0.9))
            add(Band(FilterType.HIGH_SHELF, 8000.0, -1.0 * warmth, 0.7))
        }
        if (intimacy > 0) add(Band(FilterType.PEAK, 1200.0, 1.25 * intimacy, 0.6))
    }

    fun toJson(): JSONObject = JSONObject().put("int", intimacy).put("warm", warmth).put("smooth", smoothness)

    companion object {
        fun fromJson(o: JSONObject) = VocalTuner(o.optDouble("int", 0.0), o.optDouble("warm", 0.0), o.optDouble("smooth", 0.0))

        val PRESETS = listOf(
            "Off" to VocalTuner(),
            "Intimate" to VocalTuner(0.7, 0.5, 0.4),
            "Warm & smooth" to VocalTuner(0.3, 0.8, 0.6),
            "Tame shrill" to VocalTuner(0.0, 0.3, 1.0),
            "Up front" to VocalTuner(1.0, 0.2, 0.3),
        )
    }
}

/** Instrument amplifier (side channel above ~180 Hz). Audiophile engine only. */
data class InstrumentTuner(
    val space: Double = 0.0,       // -1 caved in .. +1 spacious
    val instruments: Double = 0.0, // 0..1 string/sax presence, body, air
) {
    val isOff: Boolean get() = space == 0.0 && instruments == 0.0

    fun toJson(): JSONObject = JSONObject().put("space", space).put("inst", instruments)

    companion object {
        fun fromJson(o: JSONObject) = InstrumentTuner(o.optDouble("space", 0.0), o.optDouble("inst", 0.0))

        val PRESETS = listOf(
            "Off" to InstrumentTuner(),
            "Concert hall" to InstrumentTuner(0.7, 0.4),
            "Strings & sax" to InstrumentTuner(0.3, 0.9),
            "Intimate stage" to InstrumentTuner(-0.5, 0.3),
            "Wide open" to InstrumentTuner(1.0, 0.6),
        )
    }
}

/** Log-spaced graphic EQ layouts (10 = octave, 31 = third-octave, ...). */
object GraphicLayout {
    val COUNTS = listOf(10, 15, 31, 64)
    private const val LO = 31.25
    private const val HI = 16000.0

    fun centers(n: Int): List<Double> = List(n) { i -> LO * (HI / LO).pow(i / (n - 1).toDouble()) }

    /** Q giving each band a bandwidth equal to the spacing between centres. */
    fun q(n: Int): Double {
        val octaves = ln(HI / LO) / ln(2.0) / (n - 1)
        val r = 2.0.pow(octaves)
        return sqrt(r) / (r - 1)
    }

    fun bands(n: Int, gains: List<Double>, shelfEnds: Boolean = true): List<Band> {
        val q = q(n)
        return centers(n).mapIndexed { i, f ->
            val edge = shelfEnds && (i == 0 || i == n - 1)
            Band(if (!edge) FilterType.PEAK else if (i == 0) FilterType.LOW_SHELF else FilterType.HIGH_SHELF,
                f, gains.getOrElse(i) { 0.0 }, if (edge) 0.71 else q)
        }
    }

    fun label(f: Double): String = if (f >= 1000) {
        val k = f / 1000
        if (k >= 10) "%.0fk".format(k) else "%.1fk".format(k).replace(".0k", "k")
    } else "%.0f".format(f)
}

enum class QualityMode(val title: String, val oversample: Int, val stopbandDb: Double, val detail: String) {
    EFFICIENT("Efficient", 1, 120.0, "1x · lowest DSP cost. Tested high-frequency bell: worst deviation 5.6 dB."),
    HIGH_QUALITY("High quality", 2, 100.0, "2x oversampled EQ · tested high-frequency bell: worst deviation 1.1 dB."),
    AUDIOPHILE("Audiophile", 4, 120.0, "4x oversampled EQ · tested high-frequency bell: worst deviation 0.24 dB. Phone and Bluetooth delay depend on the audio path."),
    EXTREME("Extreme", 8, 140.0, "8x oversampled EQ · tested high-frequency bell: worst deviation 0.06 dB. Heaviest DSP cost; check battery."),
}

enum class DitherChoice(val title: String, val nativeMode: Int, val detail: String) {
    OFF("Off", 0, "Float output without intentional word-length reduction."),
    TPDF("TPDF", 1, "Triangular dither: quantisation error becomes benign, signal-independent noise."),
    SHAPED("Noise-shaped", 2, "TPDF with noise moved up out of the midrange (−17 dB at 1 kHz)."),
}

enum class EngineMode(val title: String, val detail: String) {
    AUTO("Auto", "Audiophile engine for apps that allow capture, system effects for the rest."),
    SYSTEM_ONLY("System effects only", "Android DynamicsProcessing on each app. Avoids capture and replay; gain-per-band EQ."),
}

/** Svan processing settings. */
data class AudioSettings(
    val engineMode: EngineMode = EngineMode.SYSTEM_ONLY,
    val quality: QualityMode = QualityMode.AUDIOPHILE,
    val outputBits: Int = 24,
    val dither: DitherChoice = DitherChoice.OFF,
    val autoHeadroom: Boolean = true,
    val gainProtection: Boolean = true,
    val systemBands: Int = 128,
    val systemFrameMs: Int = 80,
) {
    /** Auto master may add protection, but never rewrites the listener's saved choices. */
    fun effectiveFor(eq: EqState): AudioSettings = if (eq.smartProtection)
        copy(autoHeadroom=true,gainProtection=true) else this

    fun sameCaptureFormat(other: AudioSettings): Boolean = quality==other.quality &&
        outputBits==other.outputBits && dither==other.dither

    fun toJson(): JSONObject = JSONObject()
        .put("engine", engineMode.name).put("quality", quality.name).put("bits", outputBits)
        .put("dither", dither.name).put("headroom", autoHeadroom).put("agp", gainProtection).put("sysBands", systemBands).put("sysFrameMs", systemFrameMs)

    companion object {
        fun fromJson(o: JSONObject) = AudioSettings(
            engineMode = runCatching { EngineMode.valueOf(o.getString("engine")) }.getOrDefault(EngineMode.SYSTEM_ONLY),
            quality = runCatching { QualityMode.valueOf(o.getString("quality")) }.getOrDefault(QualityMode.AUDIOPHILE),
            outputBits = o.optInt("bits", 24).takeIf { it == 16 || it == 24 } ?: 24,
            dither = runCatching { DitherChoice.valueOf(o.getString("dither")) }.getOrDefault(DitherChoice.OFF),
            autoHeadroom = o.optBoolean("headroom", true),
            gainProtection = o.optBoolean("agp", true),
            systemFrameMs = o.optInt("sysFrameMs", 80).takeIf { it in listOf(10, 40, 80) } ?: 80,
            systemBands = o.optInt("sysBands", 128).takeIf { it in listOf(64, 128, 256) } ?: 128,
        )
    }
}

data class Preset(val name: String, val preampDb: Double, val bands: List<Band>, val builtIn: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("preamp", preampDb)
        .put("bands", JSONArray().apply { bands.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject) = Preset(
            o.getString("name"), o.optDouble("preamp", 0.0),
            o.optJSONArray("bands")?.let { a -> List(a.length()) { Band.fromJson(a.getJSONObject(it)) } } ?: emptyList(),
        )

        val BUILT_IN = listOf(
            Preset("Flat", 0.0, EqState.DEFAULT_BANDS, true),
            Preset("Warm bass", 0.0, listOf(
                Band(FilterType.LOW_SHELF, 105.0, 5.0, 0.71), Band(FilterType.PEAK, 300.0, -1.0, 1.0),
                Band(FilterType.PEAK, 3000.0, 0.0, 1.0), Band(FilterType.HIGH_SHELF, 9000.0, -1.0, 0.71),
            ), true),
            Preset("Harman-style tilt", 0.0, listOf(
                Band(FilterType.LOW_SHELF, 105.0, 4.0, 0.71), Band(FilterType.PEAK, 2800.0, 1.5, 1.4),
                Band(FilterType.HIGH_SHELF, 10000.0, -2.5, 0.71),
            ), true),
            Preset("Vocal presence", 0.0, listOf(
                Band(FilterType.HIGH_PASS, 40.0, 0.0, 0.71), Band(FilterType.PEAK, 250.0, -1.5, 1.2),
                Band(FilterType.PEAK, 2500.0, 3.0, 1.0), Band(FilterType.PEAK, 6500.0, -1.5, 3.0),
            ), true),
            Preset("Air & sparkle", 0.0, listOf(
                Band(FilterType.PEAK, 4000.0, -1.0, 1.4), Band(FilterType.HIGH_SHELF, 12000.0, 4.0, 0.71),
            ), true),
            Preset("Loudness (low volume)", 0.0, listOf(
                Band(FilterType.LOW_SHELF, 80.0, 7.0, 0.71), Band(FilterType.PEAK, 3000.0, -1.0, 1.0),
                Band(FilterType.HIGH_SHELF, 12000.0, 4.0, 0.71),
            ), true),
        )
    }
}
