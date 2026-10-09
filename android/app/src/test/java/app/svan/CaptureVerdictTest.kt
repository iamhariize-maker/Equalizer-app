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

    @Test fun migratingOldSilenceBlocksPreservesPositiveMeasurements() {
        val installed = InstalledVersion(890, 1_000)
        val negative = CaptureVerdictKey.of("com.spotify.music", installed.code, installed.updatedMs)
        val positive = CaptureVerdictKey.of("com.amazon.mp3", installed.code, installed.updatedMs)
        val stored = mapOf(negative to "BLOCKED", positive to "CAPTURABLE")
        val discarded = CaptureRecords.discardable(stored, { installed }, CaptureEvidencePolicy::reusableStoredValue)
        assertEquals(setOf(negative), discarded)
        assertEquals(mapOf("com.amazon.mp3" to "CAPTURABLE"), CaptureRecords.current(stored - discarded) { installed })
    }
}
