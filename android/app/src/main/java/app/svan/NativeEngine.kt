package app.svan

/** Kotlin handle to the shared C++ DSP engine (core/). Not thread-confined, but
 *  [process] must only ever be called from one audio thread. */
class NativeEngine(
    val sampleRate: Int,
    val channels: Int,
    quality: Quality,
    outputBits: Int = 24,
) : AutoCloseable {

    /** Ordinal values must match eqcore::QualityMode. */
    enum class Quality { EFFICIENT, HIGH_QUALITY, AUDIOPHILE, EXTREME }

    /** Ordinal values must match typeFromInt() in jni_bridge.cpp. */
    enum class FilterType { PEAK, LOW_SHELF, HIGH_SHELF, LOW_PASS, HIGH_PASS, BAND_PASS, NOTCH, ALL_PASS }

    data class Band(
        val type: FilterType,
        val freqHz: Double,
        val gainDb: Double,
        val q: Double,
        val enabled: Boolean = true,
    )

    private var handle: Long = nativeCreate(sampleRate, channels, quality.ordinal, outputBits)

    /** Fully custom configuration (Audiophile settings screen). */
    constructor(
        sampleRate: Int,
        channels: Int,
        oversample: Int,
        stopbandDb: Double,
        ditherBits: Int,
        ditherMode: Int,
        autoHeadroom: Boolean,
        gainProtection: Boolean,
        spatialResidual: Boolean = false,
    ) : this(sampleRate, channels, Quality.EFFICIENT) {
        nativeDestroy(handle)
        handle = nativeCreateDetailed(sampleRate, channels, oversample, stopbandDb, ditherBits, ditherMode, autoHeadroom, gainProtection, spatialResidual && channels == 2)
    }

    val latencyFrames: Int get() = nativeLatency(handle)

    /** [channel] = -1 applies to every channel. */
    fun setBands(bands: List<Band>, channel: Int = -1) {
        nativeSetBands(
            handle, channel,
            IntArray(bands.size) { bands[it].type.ordinal },
            DoubleArray(bands.size) { bands[it].freqHz },
            DoubleArray(bands.size) { bands[it].gainDb },
            DoubleArray(bands.size) { bands[it].q },
            BooleanArray(bands.size) { bands[it].enabled },
        )
    }

    /** The bands' own response in dB (no preamp/headroom) — what the UI draws. */
    fun curveDb(freqsHz: DoubleArray, channel: Int = 0): DoubleArray = nativeCurveDb(handle, channel, freqsHz)

    /** Bass character: -1 sustain .. 0 off .. +1 punch (Engine B only; exact). */
    fun setBassCharacter(character: Double, crossoverHz: Double) = nativeSetBassCharacter(handle, character, crossoverHz)
    fun setBassResolve(resolve: Double) = nativeSetBassResolve(handle, resolve)
    /** Selective bass unmasking 0..1; default 0 (off). Not wired to any setting yet. */
    fun setBassUnmask(amount: Double) = nativeSetBassUnmask(handle, amount)
    fun bassUnmaskDiagnostics(): DoubleArray = nativeBassUnmaskDiagnostics(handle)

    /** Vocal tuner (mid) + instrument amplifier (side). Stereo engines only. */
    fun setStereoTuner(intimacy: Double, warmth: Double, smoothness: Double, space: Double, instruments: Double, backingVocals: Double = 0.0, spatialDetail: Double = 0.0) =
        nativeSetStereoTuner(handle, intimacy, warmth, smoothness, space, instruments, backingVocals, spatialDetail)

    /** Preamp minus auto headroom currently applied, in dB. */
    val appliedGainDb: Double get() = nativeAppliedGainDb(handle)

    /** Attenuation Automatic Gain Protection has applied so far (<= 0 dB). */
    val gainProtectionDb: Double get() = nativeGainProtectionDb(handle)

    /** AutoEq "ParametricEQ.txt" contents. Returns the number of bands loaded. */
    fun loadParametricPreset(text: String): Int = nativeLoadParametricPreset(handle, text)

    fun setAutoHeadroom(enabled: Boolean) = nativeSetAutoHeadroom(handle, enabled)
    fun setGainProtection(enabled: Boolean) = nativeSetGainProtection(handle, enabled)
    fun resetGainProtection() = nativeResetGainProtection(handle)

    fun setPreampDb(db: Double) = nativeSetPreamp(handle, db)

    /** Svaramanas listening: the engine analyses its *input* (the source app's audio). */
    fun setDynamicEq(amount: Double) = nativeSetDynamicEq(handle,amount)
    fun dynamicReductions(): DoubleArray = nativeDynamicReductions(handle)

    fun setAnalysis(on: Boolean) = nativeSetAnalysis(handle, on)

    /** Long-term features of the input, packed (see eqcore::SourceFeatures). */
    fun analysis(): DoubleArray = nativeAnalysis(handle)

    /** Interleaved float frames; [input] and [output] may be the same array. */
    fun process(input: FloatArray, output: FloatArray, frames: Int) =
        nativeProcess(handle, input, output, frames)

    /** Total curve response in dB (incl. preamp/auto-headroom) at each frequency. */
    fun responseDb(freqsHz: DoubleArray, channel: Int = 0): DoubleArray =
        nativeResponseDb(handle, channel, freqsHz)

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    companion object {
        init { System.loadLibrary("eqjni") }

        /** Parses AutoEq/Equalizer APO "ParametricEQ.txt" text. Returns (preampDb, bands). */
        fun parseParametric(text: String): Pair<Double, List<Band>> {
            val raw = nativeParseParametric(text)
            val bands = (1 until raw.size step 5).map { i ->
                Band(FilterType.entries[raw[i].toInt()], raw[i + 1], raw[i + 2], raw[i + 3], raw[i + 4] != 0.0)
            }
            return raw[0] to bands
        }

        data class CalibrationFit(val fit: Fit, val lowHz: Double, val highHz: Double)
        fun calibratedTuning(measurement: String,target: String,published: Boolean,amount: Double,bands: Int,bass: Double,tilt: Double): CalibrationFit? {
            val raw=nativeCalibratedTuning(measurement,target,published,amount,bands,bass,tilt)
            if(raw.size<4)return null
            return CalibrationFit(Fit((4 until raw.size step 3).map { Band(FilterType.PEAK,raw[it],raw[it+1],raw[it+2]) },raw[0],raw[1]),raw[2],raw[3])
        }
        @JvmStatic external fun nativeSetDynamicEq(handle: Long, amount: Double)
        @JvmStatic external fun nativeDynamicReductions(handle: Long): DoubleArray
        @JvmStatic external fun nativeCalibratedTuning(measurement: String,target: String,published: Boolean,amount: Double,bands: Int,bass: Double,tilt: Double): DoubleArray
        @JvmStatic external fun nativeMatchComparison(a: FloatArray,b: FloatArray,fs: Int): DoubleArray
        @JvmStatic external fun nativeReconstructedPeak(audio: FloatArray,fs: Int): Double
        @JvmStatic external fun nativeCreate(sampleRate: Int, channels: Int, quality: Int, outputBits: Int): Long
        @JvmStatic external fun nativeDestroy(handle: Long)
        @JvmStatic external fun nativeSetBands(
            handle: Long, channel: Int, types: IntArray, freqs: DoubleArray, gains: DoubleArray, qs: DoubleArray,
            enabled: BooleanArray,
        )
        @JvmStatic external fun nativeCreateCustom(
            sampleRate: Int, channels: Int, oversample: Int, stopbandDb: Double, ditherBits: Int, ditherMode: Int,
            autoHeadroom: Boolean, gainProtection: Boolean,
        ): Long
        @JvmStatic external fun nativeCreateDetailed(
            sampleRate: Int, channels: Int, oversample: Int, stopbandDb: Double, ditherBits: Int, ditherMode: Int,
            autoHeadroom: Boolean, gainProtection: Boolean, spatialResidual: Boolean,
        ): Long
        @JvmStatic external fun nativeBassUnmaskDiagnostics(handle: Long): DoubleArray
        @JvmStatic external fun nativePolicyRulesJson(): String
        @JvmStatic external fun nativeCurveDb(handle: Long, channel: Int, freqs: DoubleArray): DoubleArray
        @JvmStatic external fun nativeAppliedGainDb(handle: Long): Double
        @JvmStatic external fun nativeGainProtectionDb(handle: Long): Double
        @JvmStatic external fun nativeParseParametric(text: String): DoubleArray
        @JvmStatic external fun nativeSetBassCharacter(handle: Long, character: Double, crossoverHz: Double)
        @JvmStatic external fun nativeSetBassResolve(handle: Long, resolve: Double)
        @JvmStatic external fun nativeSetBassUnmask(handle: Long, amount: Double)
        @JvmStatic external fun nativeSetStereoTuner(
            handle: Long, intimacy: Double, warmth: Double, smoothness: Double, space: Double, instruments: Double,
            backingVocals: Double, spatialDetail: Double,
        )
        @JvmStatic external fun nativeComputeTuning(measurement: String, target: String, bassDb: Double, tilt: Double, bands: Int): DoubleArray
        @JvmStatic external fun nativeFitCorrection(text: String, bassDb: Double, tilt: Double, bands: Int): DoubleArray

        data class Fit(val bands: List<Band>, val rmsErrorDb: Double, val maxErrorDb: Double)

        private fun unpackFit(raw: DoubleArray): Fit? {
            if (raw.size < 2) return null
            val bands = (2 until raw.size step 3).map { i -> Band(FilterType.PEAK, raw[i], raw[i + 1], raw[i + 2]) }
            return Fit(bands, raw[0], raw[1])
        }

        /** Headphone correction to [target] from [measurement] (CSV/Squiglink text), fitted with [bands] bells. */
        fun computeTuning(measurement: String, target: String, bassDb: Double, tilt: Double, bands: Int): Fit? =
            unpackFit(nativeComputeTuning(measurement, target, bassDb, tilt, bands))

        /** Fits a ready-made correction (AutoEq GraphicEQ or a dB curve). */
        fun fitCorrection(text: String, bassDb: Double, tilt: Double, bands: Int): Fit? =
            unpackFit(nativeFitCorrection(text, bassDb, tilt, bands))
        @JvmStatic external fun nativeLoadParametricPreset(handle: Long, text: String): Int
        @JvmStatic external fun nativeSetPreamp(handle: Long, db: Double)
        @JvmStatic external fun nativeSetAutoHeadroom(handle: Long, enabled: Boolean)
        @JvmStatic external fun nativeSetGainProtection(handle: Long, enabled: Boolean)
        @JvmStatic external fun nativeResetGainProtection(handle: Long)
        @JvmStatic external fun nativeProcess(handle: Long, input: FloatArray, output: FloatArray, frames: Int)
        @JvmStatic external fun nativeResponseDb(handle: Long, channel: Int, freqs: DoubleArray): DoubleArray
        @JvmStatic external fun nativeLatency(handle: Long): Int
        @JvmStatic external fun nativeSetAnalysis(handle: Long, on: Boolean)
        @JvmStatic external fun nativeAnalysis(handle: Long): DoubleArray
        fun fitGraphic(bands: List<app.svan.model.Band>, count: Int): Fit {
            val raw = nativeFitGraphic(DoubleArray(bands.size * 5) { i ->
                val b = bands[i / 5]
                when (i % 5) { 0 -> b.type.ordinal.toDouble(); 1 -> b.freqHz; 2 -> b.gainDb; 3 -> b.q; else -> if (b.enabled) 1.0 else 0.0 }
            }, count)
            return Fit((2 until raw.size step 4).map { i -> Band(FilterType.entries[raw[i].toInt()], raw[i+1], raw[i+2], raw[i+3]) }, raw[0], raw[1])
        }
        fun overlapScale(bands: List<app.svan.model.Band>): Double = nativeOverlapScale(DoubleArray(bands.size*5) { i ->
            val b=bands[i/5]
            when(i%5) { 0 -> b.type.ordinal.toDouble(); 1 -> b.freqHz; 2 -> b.gainDb; 3 -> b.q; else -> if(b.enabled) 1.0 else 0.0 }
        })
        @JvmStatic external fun nativeOverlapScale(bands: DoubleArray): Double
        @JvmStatic external fun nativeFitGraphic(bands: DoubleArray, count: Int): DoubleArray
        @JvmStatic external fun nativeSmartLoudnessDelta(
            bands: DoubleArray, features: DoubleArray?, intimacy: Double, space: Double, instruments: Double,
        ): Double
        @JvmStatic external fun nativeSvaramanasPlan(
            features: DoubleArray?, feel: Int, order: IntArray, strength: Double, stereoEngine: Boolean,
            svaresaMode: Boolean,
        ): DoubleArray
    }
}
