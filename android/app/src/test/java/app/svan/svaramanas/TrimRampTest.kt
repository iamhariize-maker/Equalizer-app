package app.svan.svaramanas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner decision D4: the listener's level, never quieter, up to +0.1 dB louder, and no steps. */
class TrimRampTest {
    private val dt = TrimRamp.TICK_MS / 1000.0

    @Test fun attenuationRampsInAtTheRateWithoutOvershoot() {
        var applied = 0.0
        val target = -6.0
        var ticks = 0
        while (applied != target) {
            val next = TrimRamp.step(applied, target, dt)
            assertTrue("never faster than rate x dt", applied - next <= TrimRamp.RATE_DB_PER_S * dt + 1e-12)
            assertTrue("never overshoots", next >= target)
            applied = next
            ticks++
        }
        assertTrue("6 dB settles within 12 s, took ${ticks * dt} s", ticks * dt <= 12.0)
    }

    @Test fun removingAttenuationIsImmediateSoTheLevelIsNeverQuieter() {
        assertEquals(-1.0, TrimRamp.step(-4.0, -1.0, dt), 0.0)
        assertEquals(0.5, TrimRamp.step(-4.0, 0.5, 0.0), 0.0)
    }

    @Test fun duringTheRampTheLevelIsOnlyEverLouderThanTheTarget() {
        var applied = -1.0
        repeat(40) { applied = TrimRamp.step(applied, -3.0, dt); assertTrue(applied >= -3.0) }
    }

    @Test fun theTargetBlendsTowardTheMeasurementAndCarriesTheBias() {
        assertEquals(0.0, TrimRamp.blendWeight(false, 30.0), 0.0)
        assertEquals(0.0, TrimRamp.blendWeight(true, 3.0), 0.0)
        assertEquals(0.5, TrimRamp.blendWeight(true, 8.0), 1e-12)
        assertEquals(1.0, TrimRamp.blendWeight(true, 30.0), 0.0)
        assertEquals(-0.99 + TrimRamp.BIAS_DB, TrimRamp.target(-0.99, null, 0.0), 1e-12)
        assertEquals(-3.02 + TrimRamp.BIAS_DB, TrimRamp.target(-0.99, -3.02, 1.0), 1e-12)
        assertEquals((-0.99 - 3.02) / 2 + TrimRamp.BIAS_DB, TrimRamp.target(-0.99, -3.02, 0.5), 1e-12)
    }

    @Test fun systemEffectsGetNoTrimUnlessTheListenerOptsIn() {
        assertEquals(0.0, TrimRamp.target(-2.0, null, 0.0, estimateAllowed = false), 0.0)
        assertEquals(-2.0 + TrimRamp.BIAS_DB, TrimRamp.target(-2.0, null, 0.0, estimateAllowed = true), 1e-12)
    }

    @Test fun badValuesNeverReachTheEngine() {
        assertEquals(0.0, TrimRamp.target(Double.NaN, null, 0.0), 0.0)
        assertEquals(-2.0, TrimRamp.step(-2.0, Double.NaN, dt), 0.0)
        assertEquals(TrimRamp.MIN_DB, TrimRamp.target(-40.0, null, 0.0), 0.0)
    }
}
