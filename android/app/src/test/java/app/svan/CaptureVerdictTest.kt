package app.svan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureVerdictTest {
    @Test fun verdictBelongsToOneInstalledVersion() {
        val measured = CaptureVerdictKey.of("com.spotify.music", 890L, 1_000L)
        val updated = CaptureVerdictKey.of("com.spotify.music", 900L, 2_000L)
        assertNotEquals(measured, updated)
        assertEquals(measured, CaptureVerdictKey.of("com.spotify.music", 890L, 1_000L))
    }

    @Test fun unknownVersionFallsBackToThePlainPackage() {
        assertEquals("com.spotify.music", CaptureVerdictKey.of("com.spotify.music", null, null))
        assertEquals("com.spotify.music", CaptureVerdictKey.of("com.spotify.music", 890L, null))
    }

    @Test fun oneSilentPlaybackIsTransientTwoAreNot() {
        assertFalse(SilentStrikes.blocks(1))
        assertTrue(SilentStrikes.blocks(2))
        assertTrue(SilentStrikes.blocks(5))
    }
}
