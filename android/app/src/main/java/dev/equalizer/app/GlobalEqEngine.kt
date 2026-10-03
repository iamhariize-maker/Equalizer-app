package dev.equalizer.app

import android.media.audiofx.DynamicsProcessing
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Engine A: attaches Android's DynamicsProcessing effect to other apps' audio
 * sessions (the Wavelet / Poweramp Equalizer approach).
 *
 * DynamicsProcessing EQ bands only expose (enabled, cutoffFrequency, gain), so a
 * parametric curve is rendered by the native core and sampled into [bandCount]
 * log-spaced bands. Each band covers frequencies up to its cutoff.
 */
class GlobalEqEngine(val bandCount: Int = 64) {

    private val effects = ConcurrentHashMap<Int, DynamicsProcessing>()
    @Volatile private var centersHz: DoubleArray = logSpaced(bandCount, 20.0, 20000.0)
    @Volatile private var gainsDb: DoubleArray = DoubleArray(bandCount)
    @Volatile private var inputGainDb: Float = 0f

    val attachedSessions: Set<Int> get() = effects.keys

    fun attach(sessionId: Int): Boolean {
        if (sessionId <= 0 || effects.containsKey(sessionId)) return false
        return try {
            val dp = DynamicsProcessing(PRIORITY, sessionId, buildConfig())
            applyTo(dp)
            dp.enabled = true
            effects[sessionId] = dp
            Log.i(TAG, "attached to session $sessionId ($bandCount bands)")
            true
        } catch (e: RuntimeException) {
            // UnsupportedOperationException / IllegalStateException on some OEM builds.
            Log.w(TAG, "attach failed for session $sessionId", e)
            false
        }
    }

    fun detach(sessionId: Int) {
        effects.remove(sessionId)?.let {
            it.enabled = false
            it.release()
        }
    }

    fun releaseAll() = effects.keys.toList().forEach(::detach)

    /** Samples [engine]'s parametric curve into the band gains and pushes it to every session. */
    fun applyCurveFrom(engine: NativeEngine) {
        val response = engine.responseDb(centersHz)
        // responseDb already includes the engine's auto-headroom/preamp. Keep a
        // limiter after the EQ as a second line of defence against overs.
        val peak = response.maxOrNull() ?: 0.0
        inputGainDb = (-max(0.0, peak)).toFloat()
        gainsDb = response
        effects.values.forEach(::applyTo)
    }

    private fun buildConfig(): DynamicsProcessing.Config =
        DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            CHANNELS,
            true, bandCount,   // pre-EQ: our curve
            false, 0,          // MBC: unused in the spike
            false, 0,          // post-EQ
            true,              // limiter
        ).build()

    private fun applyTo(dp: DynamicsProcessing) {
        val centers = centersHz
        val gains = gainsDb
        for (i in centers.indices) {
            // Upper edge = geometric midpoint to the next centre.
            val cutoff = if (i + 1 < centers.size) sqrt(centers[i] * centers[i + 1]) else 22000.0
            dp.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, cutoff.toFloat(), gains[i].toFloat()))
        }
        dp.setInputGainAllChannelsTo(inputGainDb)
        dp.setLimiterAllChannelsTo(
            DynamicsProcessing.Limiter(
                true, true, 0,
                1f,    // attack ms
                60f,   // release ms
                10f,   // ratio
                -0.5f, // threshold dBFS
                0f,    // post gain
            ),
        )
    }

    companion object {
        private const val TAG = "GlobalEqEngine"
        private const val CHANNELS = 2
        /** Above the default 0 so a stock/OEM equalizer doesn't override us. */
        private const val PRIORITY = Int.MAX_VALUE

        fun logSpaced(n: Int, lo: Double, hi: Double): DoubleArray =
            DoubleArray(n) { exp(ln(lo) + (ln(hi) - ln(lo)) * it / max(1, n - 1)) }
    }
}
