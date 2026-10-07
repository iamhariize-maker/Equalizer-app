package app.svan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureFormatTest {
    @Test
    fun safeModeIsAlways48k() {
        assertEquals(listOf(48000), RatePolicy.candidates(48000, listOf(48000, 96000, 192000), RatePolicy.Mode.SAFE))
        assertEquals(listOf(48000), RatePolicy.candidates(44100, listOf(44100, 88200), RatePolicy.Mode.SAFE))
    }

    @Test
    fun highRateNeedsExactDeviceEvidence() {
        assertEquals(listOf(96000, 48000), RatePolicy.candidates(48000, listOf(48000, 96000), RatePolicy.Mode.EVIDENCE_HIGH_RATE))
        // 192 kHz is not offered unless the mode allows it AND the device reports it.
        assertEquals(listOf(96000, 48000), RatePolicy.candidates(48000, listOf(48000, 96000, 192000), RatePolicy.Mode.EVIDENCE_HIGH_RATE))
        assertEquals(listOf(192000, 96000, 48000), RatePolicy.candidates(48000, listOf(48000, 96000, 192000), RatePolicy.Mode.EXPERIMENTAL_192K))
        assertEquals(listOf(48000), RatePolicy.candidates(48000, listOf(48000), RatePolicy.Mode.EXPERIMENTAL_192K))
    }

    @Test
    fun familyFollowsTheMixerHint() {
        assertEquals(44100, RatePolicy.family(44100))
        assertEquals(44100, RatePolicy.family(88200))
        assertEquals(48000, RatePolicy.family(48000))
        assertEquals(48000, RatePolicy.family(null))
        assertEquals(48000, RatePolicy.family(0))
        assertEquals(listOf(88200, 44100, 48000), RatePolicy.candidates(44100, listOf(44100, 88200), RatePolicy.Mode.EVIDENCE_HIGH_RATE))
        // 44.1 family but only 48k-family rates reported: nothing in the 44.1 family is proven, so stay safe.
        assertEquals(listOf(48000), RatePolicy.candidates(44100, listOf(48000, 96000), RatePolicy.Mode.EVIDENCE_HIGH_RATE))
    }

    @Test
    fun emptyOrMisleadingCapabilityListsGiveNoEvidence() {
        for (list in listOf(null, emptyList(), listOf(0), listOf(0, -1))) {
            assertEquals(listOf(48000), RatePolicy.candidates(48000, list, RatePolicy.Mode.EXPERIMENTAL_192K))
            assertEquals(listOf(48000), RatePolicy.candidates(44100, list, RatePolicy.Mode.EVIDENCE_HIGH_RATE))
        }
    }

    @Test
    fun rateFactsNeverClaimTheSourceOrDacRate() {
        val facts = RateFacts(48000, 48000, 48000, 48000, emptyList())
        assertTrue(facts.granted)
        val s = facts.summary()
        assertTrue(s.contains("original source rate=unknown") && s.contains("DAC rate=unknown"))
        assertTrue(s.contains("any/unreported"))
        assertFalse(RateFacts(96000, 48000, 96000, null, listOf(96000)).granted)  // granted client rate differs: surfaced
        assertFalse(RateFacts(96000, null, null, null, listOf(96000)).granted)
    }

    @Test fun negotiationTriesSerialCandidatesThenSafeAndDoesNotRetouchSuccess() {
        val attempts = mutableListOf<Int>()
        val result = RateNegotiation.open(listOf(192000, 96000, 48000)) { rate ->
            attempts += rate
            if (rate > 48000) error("HAL rejected")
            "opened"
        }
        assertEquals(listOf(192000, 96000, 48000), attempts)
        assertEquals(48000, result.rate)
        assertEquals(listOf(192000, 96000), result.failures)
        assertEquals("opened", result.value)
        attempts.clear()
        assertEquals(96000, RateNegotiation.open(listOf(96000, 48000)) { attempts += it; true }.rate)
        assertEquals(listOf(96000), attempts)
    }

    @Test fun negotiationBoundsInvalidDuplicatedCandidatesAndPropagatesTotalFailure() {
        val attempts = mutableListOf<Int>()
        try {
            RateNegotiation.open(listOf(0, 192000, 192000, -1)) { rate -> attempts += rate; error("failed") }
            org.junit.Assert.fail("must fail open at caller if every rate fails")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("safe 48 kHz"))
        }
        assertEquals(listOf(192000, 48000), attempts)
    }

    @Test
    fun playbackHeadSurvivesTheUnsigned32BitWrap() {
        val clock = PlaybackHeadClock()
        assertEquals(1000L, clock.unwrap(1000))
        // Successive observations must be less than half a counter apart; otherwise
        // forward motion and a backwards reset are indistinguishable.
        assertEquals(0x70000000L, clock.unwrap(0x70000000))
        assertEquals(0xe0000000L, clock.unwrap(0xe0000000.toInt()))
        assertEquals(0xfffffff0L, clock.unwrap(0xfffffff0.toInt()))   // large unsigned value
        assertEquals(0x100000010L, clock.unwrap(0x10))                // wrapped past 2^32: keeps counting
        assertEquals(0x100000110L, clock.unwrap(0x110))
    }

    @Test
    fun playbackHeadIgnoresBackwardsMovesAndResets() {
        val clock = PlaybackHeadClock()
        clock.unwrap(48000)
        assertEquals(48000L, clock.unwrap(100))         // flush: not 4 billion frames
        assertEquals(1, clock.backwardEvents)
        assertEquals(48100L, clock.unwrap(200))         // resumes counting from the new origin
        clock.reset()
        assertEquals(0, clock.backwardEvents)
        assertEquals(500L, clock.unwrap(500))
    }
}
