package app.svan.svaramanas

import kotlin.math.abs

/**
 * The Svaresa loudness trim, moved without steps (owner decision D4: the listener's level, never quieter, up to
 * +0.1 dB louder). "Lag, don't lead": a trim that adds attenuation ramps in at [RATE_DB_PER_S], so the level is at most
 * briefly louder; a trim that removes attenuation applies at once, so the level is never quieter. Pure; JVM-tested.
 */
object TrimRamp {
    const val RATE_DB_PER_S = 1.0
    const val TICK_MS = 100L
    /** Deliberately slightly above neutral. */
    const val BIAS_DB = 0.05
    const val MIN_DB = -18.0
    const val MAX_DB = 1.5

    fun step(applied: Double, target: Double, dtSec: Double, ratePerSec: Double = RATE_DB_PER_S): Double {
        if (!target.isFinite()) return applied
        if (!applied.isFinite() || target >= applied) return target
        val limit = ratePerSec * dtSec.coerceIn(0.0, 1.0)
        return if (applied - target <= limit) target else applied - limit
    }

    /** Weight of the measured estimate: 0 until the analysis is valid (3 s), then rising to 1 over the next 10 s. */
    fun blendWeight(valid: Boolean, heardSeconds: Double): Double =
        if (!valid || !heardSeconds.isFinite()) 0.0 else ((heardSeconds - 3.0) / 10.0).coerceIn(0.0, 1.0)

    /**
     * The trim to aim for. [estimate] is −predicted(pink reference); [measured] is −predicted(heard spectrum) on the
     * audiophile engine, or null. With [estimateAllowed] false (system effects without the opt-in) there is no trim.
     */
    fun target(estimate: Double, measured: Double?, weight: Double, estimateAllowed: Boolean = true): Double {
        val raw = when {
            measured != null && measured.isFinite() -> (1.0 - weight) * estimate + weight * measured
            estimateAllowed -> estimate
            else -> return 0.0
        }
        return if (raw.isFinite()) (raw + BIAS_DB).coerceIn(MIN_DB, MAX_DB) else 0.0
    }

    fun settled(applied: Double, target: Double): Boolean = abs(applied - target) < 1e-9
}
