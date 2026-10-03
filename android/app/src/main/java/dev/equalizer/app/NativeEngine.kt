package dev.equalizer.app

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

    data class Band(val type: FilterType, val freqHz: Double, val gainDb: Double, val q: Double)

    private var handle: Long = nativeCreate(sampleRate, channels, quality.ordinal, outputBits)

    val latencyFrames: Int get() = nativeLatency(handle)

    /** [channel] = -1 applies to every channel. */
    fun setBands(bands: List<Band>, channel: Int = -1) {
        nativeSetBands(
            handle, channel,
            IntArray(bands.size) { bands[it].type.ordinal },
            DoubleArray(bands.size) { bands[it].freqHz },
            DoubleArray(bands.size) { bands[it].gainDb },
            DoubleArray(bands.size) { bands[it].q },
        )
    }

    /** AutoEq "ParametricEQ.txt" contents. Returns the number of bands loaded. */
    fun loadParametricPreset(text: String): Int = nativeLoadParametricPreset(handle, text)

    fun setPreampDb(db: Double) = nativeSetPreamp(handle, db)

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

    private companion object {
        init { System.loadLibrary("eqjni") }

        @JvmStatic external fun nativeCreate(sampleRate: Int, channels: Int, quality: Int, outputBits: Int): Long
        @JvmStatic external fun nativeDestroy(handle: Long)
        @JvmStatic external fun nativeSetBands(
            handle: Long, channel: Int, types: IntArray, freqs: DoubleArray, gains: DoubleArray, qs: DoubleArray,
        )
        @JvmStatic external fun nativeLoadParametricPreset(handle: Long, text: String): Int
        @JvmStatic external fun nativeSetPreamp(handle: Long, db: Double)
        @JvmStatic external fun nativeProcess(handle: Long, input: FloatArray, output: FloatArray, frames: Int)
        @JvmStatic external fun nativeResponseDb(handle: Long, channel: Int, freqs: DoubleArray): DoubleArray
        @JvmStatic external fun nativeLatency(handle: Long): Int
    }
}
