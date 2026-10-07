package app.svan

import org.junit.Assert.*
import org.junit.Test

class MixFallbackPolicyTest {
    @Test fun hiddenPlayerGetsWholeMixOnlyAfterTheHold() {
        val p = MixFallbackPolicy(holdMs = 3_000)
        assertFalse(p.next(0, true, true, 1, 0))
        assertFalse(p.next(2_999, true, true, 1, 0))
        assertTrue(p.next(3_000, true, true, 1, 0))
    }

    @Test fun aRoutedPlayerSwitchesItOffImmediatelySoNothingIsEqualisedTwice() {
        val p = MixFallbackPolicy(holdMs = 0)
        assertTrue(p.next(0, true, true, 2, 0))
        assertFalse(p.next(1, true, true, 2, 1))
    }

    @Test fun silenceSettingCaptureOrUnknownCountsKeepItOff() {
        val p = MixFallbackPolicy(holdMs = 0)
        assertFalse(p.next(0, true, false, 1, 0))   // nothing actually playing music
        assertFalse(p.next(0, false, true, 1, 0))   // setting off, EQ off or capture running
        assertFalse(p.next(0, true, true, null, 0)) // public count unavailable
        assertFalse(p.next(0, true, true, 0, 0))
    }

    @Test fun gapsBetweenSongsKeepItOnButALongStopReleasesIt() {
        val p = MixFallbackPolicy(holdMs = 0, pauseGraceMs = 30_000)
        assertTrue(p.next(0, true, true, 1, 0))
        assertTrue(p.next(5_000, true, false, 1, 0))   // pause between tracks
        assertTrue(p.next(20_000, true, true, 1, 0))   // next song
        assertTrue(p.next(30_000, true, false, 0, 0))
        assertFalse(p.next(60_001, true, false, 0, 0))
    }

    @Test fun routingOrDisablingIgnoresTheGrace() {
        val p = MixFallbackPolicy(holdMs = 0, pauseGraceMs = 30_000)
        assertTrue(p.next(0, true, true, 1, 0))
        assertFalse(p.next(1, true, false, 1, 1))
        assertTrue(p.next(2, true, true, 1, 0))
        assertFalse(p.next(3, false, false, 0, 0))
    }

    @Test fun anInterruptionRestartsTheHold() {
        val p = MixFallbackPolicy(holdMs = 3_000)
        p.next(0, true, true, 1, 0)
        p.next(2_000, true, true, 1, 1)
        assertFalse(p.next(4_000, true, true, 1, 0))
        assertTrue(p.next(7_000, true, true, 1, 0))
    }
}
