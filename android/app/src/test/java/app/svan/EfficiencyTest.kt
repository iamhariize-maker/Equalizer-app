package app.svan

import app.svan.model.AudioSettings
import app.svan.model.Band
import app.svan.model.EqState
import app.svan.model.SmartLayer
import app.svan.model.QualityMode
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class EfficiencyTest {
    @Test fun idleNeedsPositiveEvidenceAndAWorkingCallback() {
        assertEquals(30_000L, DiscoverySchedule.cadence(true, 0, false, false, true))
        assertEquals(30_000L, DiscoverySchedule.cadence(false, 0, false, false, true))
        assertEquals(5_000L, DiscoverySchedule.cadence(true, null, false, false, true))
        assertEquals(5_000L, DiscoverySchedule.cadence(true, 0, false, false, false))
        assertEquals(1_000L, DiscoverySchedule.cadence(true, 1, false, false, true))
        assertEquals(1_000L, DiscoverySchedule.cadence(true, 0, true, false, true))
        assertEquals(1_000L, DiscoverySchedule.cadence(true, 0, false, true, true))
        assertEquals(5_000L, DiscoverySchedule.cadence(false, 1, true, false, true))
    }

    @Test fun thirtyMinutesIdleHasSixtyWatchdogsButBasicPlaybackStillHas1800() {
        fun count(interval: Long): Int {
            val schedule = DiscoverySchedule(0)
            schedule.interval(0, interval)
            var count = 0
            while (schedule.nextMs() <= 1_800_000) {
                assertTrue(schedule.due(schedule.nextMs()))
                count++
            }
            return count
        }
        assertEquals(60, count(30_000))
        assertEquals(1800, count(1_000))
    }

    @Test fun routeSettlingChecksDoNotDuplicateWatchdogAtSameInstant() {
        val schedule = DiscoverySchedule(0)
        assertTrue(schedule.due(0))
        schedule.recover(0)
        val times = mutableListOf<Long>()
        while (schedule.nextMs() <= 10_000) {
            val at = schedule.nextMs()
            assertTrue(schedule.due(at))
            assertFalse(schedule.due(at))
            times += at
        }
        assertEquals(listOf(350L, 1_000L, 2_500L, 5_000L, 10_000L), times)
    }

    @Test fun callbackStormKeepsEarlyRepairAndHasBoundedSettlingTail() {
        val schedule = DiscoverySchedule(0)
        schedule.due(0)
        schedule.recover(0)
        repeat(300) { schedule.recover(it.toLong()) }
        assertEquals(350L, schedule.nextMs())
        var count = 0
        var last = 0L
        while (schedule.nextMs() <= 10_299) {
            last = schedule.nextMs()
            schedule.due(last)
            count++
        }
        assertEquals(6, count) // five settling checks plus the watchdog before the extended tail
        assertEquals(10_299L, last)
    }

    @Test fun idleToPlayingResumesFastWatchdogWithoutWaitingForIdleDeadline() {
        val schedule = DiscoverySchedule(0)
        schedule.interval(0, 30_000)
        schedule.interval(100, 1_000)
        assertEquals(1_100L, schedule.nextMs())
        schedule.recover(100)
        assertEquals(450L, schedule.nextMs())
    }

    @Test fun delayedWakeConsumesExpiredRecoveryOnce() {
        val schedule = DiscoverySchedule(0)
        schedule.due(0)
        schedule.recover(0)
        assertTrue(schedule.due(60_000))
        assertFalse(schedule.due(60_000))
        assertEquals(65_000L, schedule.nextMs())
    }

    @Test fun immediateEventSatisfiesTheWatchdogWithoutDelayingEarlyRecovery() {
        val schedule = DiscoverySchedule(0)
        schedule.due(0)
        schedule.scanned(4_900)
        schedule.recover(4_900)
        assertFalse(schedule.due(5_000))
        assertEquals(5_250L, schedule.nextMs())
        schedule.scanned(5_100)
        schedule.recover(5_100)
        assertEquals(5_250L, schedule.nextMs())
    }

    @Test fun plannerCoalescesEditsWithoutLosingImmediateOrCompletionBoundary() {
        val gate = PlannerRequestGate()
        assertTrue(gate.request(false))
        assertFalse(gate.takeImmediate())
        repeat(100) { assertFalse(gate.request(it == 35)) }
        assertTrue(gate.complete())
        assertTrue(gate.takeImmediate())
        assertFalse(gate.complete())
        assertTrue(gate.request(true))
        assertTrue(gate.takeImmediate())
        assertFalse(gate.request(false))
        assertTrue(gate.complete())
        assertFalse(gate.takeImmediate())
        assertFalse(gate.complete())
    }

    @Test fun bandUpdatesMatchStableSortedReferenceExactlyIncludingFloatBoundaries() {
        val random = Random(369)
        repeat(500) { iteration ->
            val before = FloatArray(128) { if (random.nextInt(8) == 0) Float.NaN else random.nextDouble(-24.0, 24.0).toFloat() }
            val gains = DoubleArray(128) { i -> when {
                before[i].isNaN() -> random.nextDouble(-18.0, 12.0)
                iteration % 3 == 0 -> before[i].toDouble() + random.nextDouble(-.011, .011)
                else -> random.nextDouble(-18.0, 12.0)
            } }
            val expected = gains.indices.sortedBy { if (before[it].isNaN() || gains[it] < before[it]) 0 else 1 }
                .mapNotNull { i -> gains[i].toFloat().let { if (abs(it - before[i]) < .01f) null else i to it } }
            val sent = before.copyOf()
            val actual = mutableListOf<Pair<Int, Float>>()
            forEachBandUpdate(gains, sent) { i, gain -> actual += i to gain; sent[i] = gain }
            assertEquals(expected, actual)
            expected.forEach { (i, gain) -> assertEquals(gain, sent[i], 0f) }
            val unchanged = mutableListOf<Int>()
            forEachBandUpdate(gains, sent) { i, _ -> unchanged += i }
            assertTrue(unchanged.isEmpty())
        }
    }

    @Test fun curveCacheSeparatesSoundInputsFromUnrelatedSettings() {
        val eq = EqState()
        val settings = AudioSettings()
        val initial = CurveConfiguration.from(eq, settings)
        assertEquals(initial, CurveConfiguration.from(eq.copy(presetName = "Another name"), settings))
        assertEquals(initial, CurveConfiguration.from(eq, settings.copy(quality = QualityMode.EXTREME)))
        assertNotEquals(initial, CurveConfiguration.from(eq.copy(preampDb = -.1), settings))
        assertNotEquals(initial, CurveConfiguration.from(eq.copy(bands = listOf(Band(gainDb = .1))), settings))
        assertNotEquals(initial, CurveConfiguration.from(eq, settings.copy(autoHeadroom = !settings.autoHeadroom)))
        assertNotEquals(initial, CurveConfiguration.from(eq, settings.copy(gainProtection = !settings.gainProtection)))
    }

    @Test fun persistenceIgnoresOnlyLiveSmartAndCompareState() {
        val eq = EqState()
        val live = eq.copy(smart = SmartLayer(bands = emptyList(), preampDb = -1.0), smartBypass = true)
        assertEquals(eq.persistenceState(), live.persistenceState())
        assertEquals(eq.toJson().toString(), live.persistenceState().toJson().toString())
        assertNotEquals(eq.persistenceState(), eq.copy(preampDb = -.1).persistenceState())
        assertNotEquals(eq.persistenceState(), eq.copy(smartEqControl = true).persistenceState())
    }
}
