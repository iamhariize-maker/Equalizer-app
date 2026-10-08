package app.svan

/**
 * Honest sample-rate bookkeeping for capture (AQ-05). Pure Kotlin, no Android types, so it is unit-testable.
 *
 * Android reports several different rates and none of them is the original file's rate or the DAC's
 * physical rate: those stay "unknown" unless something measures them. A source app labelled "Ultra HD"
 * is not evidence of a high-rate capture either.
 */
data class RateFacts(
    val requestedHz: Int,
    /** `AudioRecord.getSampleRate()`: the client-side format that was asked for and granted. */
    val captureClientHz: Int?,
    /** `AudioTrack.getSampleRate()`: the client-side output format. */
    val outputClientHz: Int?,
    /** `AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE`: the platform mixer's preferred rate (a hint, not the DAC). */
    val mixerHintHz: Int?,
    /** `AudioDeviceInfo.getSampleRates()` of the routed output; empty means "any rate" or unreported. */
    val deviceReportedHz: List<Int>,
    /** Device-side recording format, if Android exposes it; not the player's original source. */
    val captureDeviceHz: Int? = null,
) {
    /** True when the granted client formats match what was requested; a mismatch must be surfaced, not hidden. */
    val granted: Boolean get() = captureClientHz == requestedHz && outputClientHz == requestedHz

    fun summary(): String = "rates: requested=$requestedHz capture-client=${captureClientHz ?: "?"} output-client=${outputClientHz ?: "?"} " +
        "mixer-hint=${mixerHintHz ?: "?"} device-reported=${if (deviceReportedHz.isEmpty()) "any/unreported" else deviceReportedHz.joinToString("/")} " +
        "record-device=${captureDeviceHz ?: "?"} granted=$granted; original source rate=unknown; DAC rate=unknown"
}

/** Serial negotiation; the opener owns cleanup if a candidate fails. Never releases source mutes. */
object RateNegotiation {
    data class Result<T>(val rate: Int, val value: T, val failures: List<Int>)
    fun <T> open(candidates: List<Int>, opener: (Int) -> T): Result<T> {
        val failures = mutableListOf<Int>()
        val rates = (candidates.filter { it in setOf(44100, 48000, 88200, 96000, 176400, 192000) } + RatePolicy.SAFE_HZ).distinct()
        var cause: Exception? = null
        for (rate in rates) {
            try { return Result(rate, opener(rate), failures.toList()) }
            catch (e: Exception) { failures += rate; cause = e }
        }
        throw IllegalStateException("No capture/output format initialized, including safe 48 kHz", cause)
    }
}

data class CaptureEpoch(val id: Long, val sampleRate: Int, val latencyFrames: Int, val detailed: Boolean,
    val appliedSettings: app.svan.model.AudioSettings)

/**
 * Order in which to try capture/DSP rates. This only proposes candidates: the caller opens them serially
 * with a bounded fallback and keeps 48 kHz as the safe rate. A fixed-ratio resampler is not an
 * asynchronous clock fix, so a candidate that does not run cleanly is dropped, never papered over.
 */
object RatePolicy {
    const val SAFE_HZ = 48000

    enum class Mode {
        /** Default: 48 kHz only. */
        SAFE,
        /** One step up (88.2/96 kHz) only when the routed device reports that exact rate. */
        EVIDENCE_HIGH_RATE,
        /** Also 176.4/192 kHz when reported. Experimental; may fail on many devices. */
        EXPERIMENTAL_192K,
    }

    /** 44.1 kHz family when the platform mixer hint is a multiple of 44100, otherwise 48 kHz family. */
    fun family(mixerHintHz: Int?): Int = if (mixerHintHz != null && mixerHintHz > 0 && mixerHintHz % 44100 == 0) 44100 else 48000

    /** Rates reported by a device: empty/zero entries mean "any rate" and are not evidence. */
    fun reported(deviceRates: List<Int>?): Set<Int> = deviceRates.orEmpty().filter { it > 0 }.toSet()

    fun candidates(mixerHintHz: Int?, deviceRates: List<Int>?, mode: Mode): List<Int> {
        val base = family(mixerHintHz)
        val have = reported(deviceRates)
        val out = LinkedHashSet<Int>()
        if (mode == Mode.EXPERIMENTAL_192K && base * 4 in have) out += base * 4
        if (mode != Mode.SAFE && base * 2 in have) out += base * 2
        if (mode != Mode.SAFE && base != SAFE_HZ && base in have) out += base
        out += SAFE_HZ  // always last: the safe, tested fallback
        return out.toList()
    }
}

/**
 * Monotonic playback-head frame count. `AudioTrack.getPlaybackHeadPosition()` is an `Int` that wraps
 * as an unsigned 32-bit counter (after ~12 hours at 96 kHz); a count that goes backwards, such as after
 * a flush, is ignored rather than taken as 4 billion frames. Call [reset] when the track is rebuilt.
 */
class PlaybackHeadClock {
    private var lastRaw = 0L
    private var total = 0L

    /** Number of times the platform counter moved backwards (flush/restart) since the last [reset]. */
    var backwardEvents = 0
        private set

    fun unwrap(raw: Int): Long {
        val v = raw.toLong() and 0xffffffffL
        val delta = (v - lastRaw) and 0xffffffffL
        if (delta > 0x7fffffffL) { backwardEvents++; lastRaw = v; return total }
        total += delta
        lastRaw = v
        return total
    }

    fun reset() { lastRaw = 0; total = 0; backwardEvents = 0 }
}
