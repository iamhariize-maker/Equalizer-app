package app.svan

import android.media.audiofx.DynamicsProcessing
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
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
class GlobalEqEngine(bandCount: Int = 128) {

    @Volatile var bandCount: Int = bandCount
        private set

    private val effects = ConcurrentHashMap<Int, DynamicsProcessing>()
    private val lastSent = ConcurrentHashMap<DynamicsProcessing, FloatArray>()
    @Volatile private var centersHz: DoubleArray = logSpaced(bandCount, 20.0, 20000.0)
    @Volatile private var gainsDb: DoubleArray = DoubleArray(bandCount)
    @Volatile private var inputGainDb: Float = 0f
    @Volatile private var bassCharacter: Double = 0.0
    @Volatile private var bassCrossoverHz: Double = 120.0
    /** MBC only exists in effects created while a bass feel was set (config is fixed at creation). */
    @Volatile private var mbcInUse = false

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
            EqController.log("system effects: attach failed for session $sessionId: $e")
            false
        }
    }

    fun detach(sessionId: Int) {
        effects.remove(sessionId)?.let {
            lastSent.remove(it)
            it.enabled = false
            it.release()
        }
    }

    fun releaseAll() = effects.keys.toList().forEach(::detach)

    /** Changes the band count; attached sessions are re-created with the new layout. */
    @Synchronized
    fun reconfigure(bands: Int) {
        if (bands == bandCount) return
        val sessions = effects.keys.toList()
        sessions.forEach(::detach)
        bandCount = bands
        centersHz = logSpaced(bands, 20.0, 20000.0)
        gainsDb = DoubleArray(bands)
        sessions.forEach { attach(it) }
    }

    /** Samples [engine]'s parametric curve into the band gains and pushes it to every session. */
    fun applyCurveFrom(engine: NativeEngine) {
        val response = engine.responseDb(centersHz)
        if (response.size != bandCount) return // raced with reconfigure(); the next update fixes it
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
            mbcInUse, if (mbcInUse) 2 else 0, // MBC: bass feel (see setBassDynamics)
            false, 0,          // post-EQ
            true,              // limiter
        ).build()

    /**
     * Bass feel on system effects: an approximation of the audiophile engine's
     * shaper using DynamicsProcessing's multiband compressor on the bass band.
     *  punch   -> downward expander (quiet tails decay faster: tighter)
     *  sustain -> slow compressor + make-up gain (tails held up: more bloom)
     */
    @Synchronized
    fun setBassDynamics(character: Double, crossoverHz: Double) {
        val needMbc = character != 0.0
        bassCharacter = character
        bassCrossoverHz = crossoverHz
        if (needMbc != mbcInUse) {
            mbcInUse = needMbc
            EqController.log("system effects: bass dynamics ${if (needMbc) "on" else "off"} (${effects.size} session(s) re-created)")
            val sessions = effects.keys.toList()
            sessions.forEach(::detach)
            sessions.forEach { attach(it) }
        } else {
            effects.values.forEach(::applyMbc)
        }
    }

    private fun applyMbc(dp: DynamicsProcessing) {
        if (!mbcInUse) return
        val c = bassCharacter.toFloat()
        val bass = if (c > 0) {
            DynamicsProcessing.MbcBand(true, bassCrossoverHz.toFloat(), 1f, 60f, 1f, 0f, 0f,
                -45f + 15f * c, 1f + 2f * c, 0f, 0f)
        } else {
            val s = -c
            DynamicsProcessing.MbcBand(true, bassCrossoverHz.toFloat(), 15f, 300f + 300f * s, 1f + 3f * s, -30f, 6f,
                -90f, 1f, 0f, 4f * s)
        }
        val rest = DynamicsProcessing.MbcBand(true, 20000f, 1f, 60f, 1f, 0f, 0f, -90f, 1f, 0f, 0f)
        dp.setMbcBandAllChannelsTo(0, bass)
        dp.setMbcBandAllChannelsTo(1, rest)
    }

    /**
     * Every band update is a binder call (measured on a TECNO LH7n: ~2 ms per
     * band, ~250 ms for all 128). Only push bands whose gain actually changed,
     * so dragging one control costs a handful of calls, not the whole curve.
     */
    private fun applyTo(dp: DynamicsProcessing) {
        val centers = centersHz
        val gains = gainsDb
        val sent = lastSent.getOrPut(dp) { FloatArray(centers.size) { Float.NaN } }
        for (i in centers.indices) {
            val g = gains[i].toFloat()
            if (abs(g - sent[i]) < 0.01f) continue
            // Upper edge = geometric midpoint to the next centre.
            val cutoff = if (i + 1 < centers.size) sqrt(centers[i] * centers[i + 1]) else 22000.0
            dp.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, cutoff.toFloat(), g))
            sent[i] = g
        }
        dp.setInputGainAllChannelsTo(inputGainDb)
        applyMbc(dp)
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
