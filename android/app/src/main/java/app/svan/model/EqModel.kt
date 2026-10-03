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
) {
    fun toJson(): JSONObject = JSONObject().put("on", enabled).put("hp", headphone).put("src", source).put("ref", ref)
        .put("sig", signature).put("rms", fitRmsDb).put("bass", bassDb).put("tilt", tiltDbPerOct)
        .put("bands", JSONArray().apply { bands.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject) = Tuning(
            o.optBoolean("on", true), o.getString("hp"), o.optString("src"), o.optString("sig"),
            o.optJSONArray("bands")?.let { a -> List(a.length()) { Band.fromJson(a.getJSONObject(it)) } } ?: emptyList(),
            o.optDouble("rms", 0.0), o.optDouble("bass", 0.0), o.optDouble("tilt", 0.0), o.optString("ref"),
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
            "Deep & warm" to BassTuner(6.0, 55.0, -0.4),
            "Punchy" to BassTuner(4.0, 85.0, 0.7),
            "Tight & precise" to BassTuner(2.0, 70.0, 1.0),
            "Club rumble" to BassTuner(9.0, 45.0, -0.8),
            "Bass-light fix" to BassTuner(5.0, 110.0, 0.3),
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
    val preampDb: Double = 0.0,
    val presetName: String = "Flat",
    val tuning: Tuning? = null,
    val bass: BassTuner = BassTuner(),
) {
    /** The user's own EQ layer (parametric or graphic). */
    fun manualBands(): List<Band> = if (mode == EqMode.PARAMETRIC) bands else GraphicLayout.bands(graphicCount, graphicGains)

    /** The bands the engines actually run: headphone tuning + your EQ + bass tuner. */
    fun effectiveBands(): List<Band> = if (!enabled) emptyList() else
        (tuning?.takeIf { it.enabled }?.bands ?: emptyList()) + manualBands() + bass.bands()

    /** Bass shaper amount the engines should run (0 when the EQ is off). */
    val bassCharacter: Double get() = if (enabled) bass.character else 0.0

    fun effectivePreampDb(): Double = if (enabled) preampDb else 0.0

    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled).put("mode", mode.name)
        .put("bands", JSONArray().apply { bands.forEach { put(it.toJson()) } })
        .put("gCount", graphicCount)
        .put("gGains", JSONArray().apply { graphicGains.forEach { put(it) } })
        .put("preamp", preampDb).put("preset", presetName)
        .put("bass", bass.toJson())
        .apply { tuning?.let { put("tuning", it.toJson()) } }

    companion object {
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
                preampDb = o.optDouble("preamp", 0.0),
                presetName = o.optString("preset", "Custom"),
                tuning = o.optJSONObject("tuning")?.let { runCatching { Tuning.fromJson(it) }.getOrNull() },
                bass = o.optJSONObject("bass")?.let { BassTuner.fromJson(it) } ?: BassTuner(),
            )
        }
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

    fun bands(n: Int, gains: List<Double>): List<Band> {
        val q = q(n)
        return centers(n).mapIndexed { i, f -> Band(FilterType.PEAK, f, gains.getOrElse(i) { 0.0 }, q) }
    }

    fun label(f: Double): String = if (f >= 1000) {
        val k = f / 1000
        if (k >= 10) "%.0fk".format(k) else "%.1fk".format(k).replace(".0k", "k")
    } else "%.0f".format(f)
}

enum class QualityMode(val title: String, val oversample: Int, val stopbandDb: Double, val detail: String) {
    EFFICIENT("Efficient", 1, 120.0, "1x · lowest CPU and latency. High bands bend near 20 kHz (5.6 dB error at 16 kHz)."),
    HIGH_QUALITY("High quality", 2, 100.0, "2x oversampled EQ · 1.1 dB error at 16 kHz · ~0.8 ms latency."),
    AUDIOPHILE("Audiophile", 4, 120.0, "4x oversampled EQ · 0.24 dB error at 16 kHz · 120 dB image rejection · ~1.1 ms."),
    EXTREME("Extreme", 8, 140.0, "8x oversampled EQ · 0.06 dB error · 140 dB filters. Heaviest CPU — check battery."),
}

enum class DitherChoice(val title: String, val nativeMode: Int, val detail: String) {
    OFF("Off", 0, "Plain rounding to the output word length."),
    TPDF("TPDF", 1, "Triangular dither: quantisation error becomes benign, signal-independent noise."),
    SHAPED("Noise-shaped", 2, "TPDF with noise moved up out of the midrange (−17 dB at 1 kHz)."),
}

enum class EngineMode(val title: String, val detail: String) {
    AUTO("Auto", "Audiophile engine for apps that allow capture, system effects for the rest."),
    SYSTEM_ONLY("System effects only", "Android DynamicsProcessing on each app. Lowest latency and battery; gain-per-band only."),
}

/** Audiophile settings (Neutron-style). */
data class AudioSettings(
    val engineMode: EngineMode = EngineMode.AUTO,
    val quality: QualityMode = QualityMode.AUDIOPHILE,
    val outputBits: Int = 24,
    val dither: DitherChoice = DitherChoice.TPDF,
    val autoHeadroom: Boolean = true,
    val gainProtection: Boolean = true,
    val systemBands: Int = 128,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("engine", engineMode.name).put("quality", quality.name).put("bits", outputBits)
        .put("dither", dither.name).put("headroom", autoHeadroom).put("agp", gainProtection).put("sysBands", systemBands)

    companion object {
        fun fromJson(o: JSONObject) = AudioSettings(
            engineMode = runCatching { EngineMode.valueOf(o.getString("engine")) }.getOrDefault(EngineMode.AUTO),
            quality = runCatching { QualityMode.valueOf(o.getString("quality")) }.getOrDefault(QualityMode.AUDIOPHILE),
            outputBits = o.optInt("bits", 24).takeIf { it == 16 || it == 24 } ?: 24,
            dither = runCatching { DitherChoice.valueOf(o.getString("dither")) }.getOrDefault(DitherChoice.TPDF),
            autoHeadroom = o.optBoolean("headroom", true),
            gainProtection = o.optBoolean("agp", true),
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
