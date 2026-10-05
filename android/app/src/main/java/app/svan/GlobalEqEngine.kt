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

    @Volatile var frameDurationMs: Int = 80
        private set

    private val effects = ConcurrentHashMap<Int, DynamicsProcessing>()
    private val lastSent = ConcurrentHashMap<DynamicsProcessing, FloatArray>()
    @Volatile private var centersHz: DoubleArray = logSpaced(bandCount, 20.0, 20000.0)
    @Volatile private var gainsDb: DoubleArray = DoubleArray(bandCount)
    @Volatile private var protection = true
    @Volatile private var eqEnabled = true
    @Volatile private var inputGainDb: Float = 0f
    @Volatile private var bassCharacter: Double = 0.0
    @Volatile private var bassCrossoverHz: Double = 120.0
    @Volatile private var deharsh: Double = 0.0
    /** MBC only exists in effects created while a bass feel was set (config is fixed at creation). */
    @Volatile private var mbcInUse = false

    val attachedSessions: Set<Int> get() = effects.keys

    @Synchronized
    fun isHealthy(sessionId: Int): Boolean = effects[sessionId]?.let { dp ->
        runCatching { dp.hasControl() && dp.enabled }.getOrDefault(false)
    } ?: false

    @Synchronized
    fun attach(sessionId: Int): Boolean {
        if (sessionId <= 0) return false
        if (isHealthy(sessionId)) return true
        // An output reconnect can invalidate an effect while its old session
        // remains in our map. Re-create it instead of reporting false success.
        detach(sessionId)
        var candidate: DynamicsProcessing? = null
        return try {
            val dp = DynamicsProcessing(PRIORITY, sessionId, buildConfig())
            candidate = dp
            applyTo(dp)
            dp.enabled = true
            check(dp.hasControl() && dp.enabled) { "Android did not enable the session effect" }
            effects[sessionId] = dp
            Log.i(TAG, "attached to session $sessionId ($bandCount bands)")
            true
        } catch (e: RuntimeException) {
            candidate?.let { lastSent.remove(it); runCatching { it.release() } }
            // UnsupportedOperationException / IllegalStateException on some OEM builds.
            Log.w(TAG, "attach failed for session $sessionId", e)
            EqController.log("system effects: attach failed for session $sessionId: $e")
            false
        }
    }

    @Synchronized
    fun detach(sessionId: Int) {
        effects.remove(sessionId)?.let {
            lastSent.remove(it)
            runCatching { it.enabled = false }
            runCatching { it.release() }
        }
    }

    @Synchronized
    fun releaseAll() = effects.keys.toList().forEach(::detach)

    /** Changes the band count; attached sessions are re-created with the new layout. */
    @Synchronized
    fun reconfigure(bands: Int, frameMs: Int = frameDurationMs) {
        if (bands == bandCount && frameMs == frameDurationMs) return
        val sessions = effects.keys.toList()
        sessions.forEach(::detach)
        bandCount = bands
        frameDurationMs = frameMs
        centersHz = logSpaced(bands, 20.0, 20000.0)
        gainsDb = DoubleArray(bands)
        sessions.forEach { attach(it) }
    }

    /** Samples [engine]'s parametric curve into the band gains and pushes it to every session. */
    @Synchronized
    fun applyCurveFrom(engine: NativeEngine, gainProtection: Boolean = true, enabled: Boolean = true) {
        protection = gainProtection
        eqEnabled = enabled
        val response = engine.responseDb(centersHz)
        if (response.size != bandCount) return // raced with reconfigure(); the next update fixes it
        // The native response already contains preamp and chosen headroom.
        // Avoid a second automatic attenuation when headroom is disabled.
        inputGainDb = 0f
        gainsDb = response
        forEachEffect(::applyTo)
    }

    private fun forEachEffect(apply: (DynamicsProcessing) -> Unit) {
        effects.entries.toList().forEach { (sid, dp) ->
            try { apply(dp) } catch (e: RuntimeException) {
                // Players can close a session while binder updates are in flight.
                // One dead effect must not kill the repository's update collector.
                EqController.log("system effects: update failed for session $sid: $e")
                detach(sid)
            }
        }
    }

    private fun buildConfig(): DynamicsProcessing.Config =
        DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            CHANNELS,
            true, bandCount,   // pre-EQ: our curve
            mbcInUse, if (mbcInUse) 4 else 0, // MBC: bass feel + vocal smoothness (see setDynamics)
            false, 0,          // post-EQ
            true,              // limiter
        ).setPreferredFrameDuration(frameDurationMs.toFloat()).build()

    /**
     * Dynamics on system effects, via DynamicsProcessing's multiband compressor
     * (an approximation of the audiophile engine's processors):
     *  band 0 (bass)      punch -> downward expander (tails decay faster: tighter)
     *                     sustain -> slow compressor + make-up gain (tails held up)
     *  band 2 (2.5-6 kHz) vocal smoothness -> fast compressor on the shrill band
     *  bands 1, 3         neutral
     */
    @Synchronized
    fun setDynamics(character: Double, crossoverHz: Double, smoothness: Double) {
        val needMbc = character != 0.0 || smoothness > 0.0
        bassCharacter = character
        bassCrossoverHz = crossoverHz
        deharsh = smoothness
        if (needMbc != mbcInUse) {
            mbcInUse = needMbc
            EqController.log("system effects: dynamics ${if (needMbc) "on" else "off"} (${effects.size} session(s) re-created)")
            val sessions = effects.keys.toList()
            sessions.forEach(::detach)
            sessions.forEach { attach(it) }
        } else {
            forEachEffect(::applyMbc)
        }
    }

    private fun applyMbc(dp: DynamicsProcessing) {
        if (!mbcInUse) return
        val c = bassCharacter.toFloat()
        val xo = bassCrossoverHz.toFloat().coerceAtMost(2000f)
        val bass = when {
            c > 0 -> DynamicsProcessing.MbcBand(true, xo, 1f, 60f, 1f, 0f, 0f, -45f + 15f * c, 1f + 2f * c, 0f, 0f)
            c < 0 -> {
                val s = -c
                DynamicsProcessing.MbcBand(true, xo, 15f, 300f + 300f * s, 1f + 3f * s, -30f, 6f, -90f, 1f, 0f, 4f * s)
            }
            else -> neutral(xo)
        }
        val d = deharsh.toFloat()
        val harsh = if (d > 0) DynamicsProcessing.MbcBand(true, 6000f, 2f, 80f, 1f + 3f * d, -24f - 8f * d, 6f, -90f, 1f, 0f, 0f)
        else neutral(6000f)
        dp.setMbcBandAllChannelsTo(0, bass)
        dp.setMbcBandAllChannelsTo(1, neutral(2500f))
        dp.setMbcBandAllChannelsTo(2, harsh)
        dp.setMbcBandAllChannelsTo(3, neutral(20000f))
    }

    private fun neutral(cutoff: Float) = DynamicsProcessing.MbcBand(true, cutoff, 1f, 60f, 1f, 0f, 0f, -90f, 1f, 0f, 0f)

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
                true, protection && eqEnabled, 0,
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
