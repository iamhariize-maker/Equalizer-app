package app.svan.model

import app.svan.RatePolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AudioQualitySettingsTest {
    @Test fun oldSettingsMigrateToSafeFastAndNoExperiment() {
        val settings = AudioSettings.fromJson(JSONObject("{\"quality\":\"AUDIOPHILE\"}"))
        assertEquals(SpatialMode.FAST, settings.spatialMode)
        assertEquals(RatePolicy.Mode.SAFE, settings.captureRateMode)
        assertFalse(settings.experimentalBassUnmask)
    }
    @Test fun newSettingsRoundTripAndRequireCaptureRestart() {
        val settings = AudioSettings(spatialMode = SpatialMode.DETAILED, captureRateMode = RatePolicy.Mode.EXPERIMENTAL_192K)
        assertEquals(settings, AudioSettings.fromJson(settings.toJson()))
        assertFalse(settings.sameCaptureFormat(settings.copy(spatialMode = SpatialMode.FAST)))
        assertFalse(settings.sameCaptureFormat(settings.copy(captureRateMode = RatePolicy.Mode.SAFE)))
        assertTrue(settings.sameCaptureFormat(settings.copy(gainProtection = false)))
    }
    @Test fun corruptEnumsHaveSafeFallback() {
        val settings = AudioSettings.fromJson(JSONObject("{\"spatialMode\":\"surround\",\"captureRateMode\":\"999k\"}"))
        assertEquals(SpatialMode.FAST, settings.spatialMode)
        assertEquals(RatePolicy.Mode.SAFE, settings.captureRateMode)
    }
    @Test fun internalRateTargetIsPreservedAcrossBothFamilies() {
        for (base in listOf(44100, 48000)) {
            val audiophile = AudioSettings(quality = QualityMode.AUDIOPHILE)
            assertEquals(4, audiophile.oversampleAt(base))
            assertEquals(2, audiophile.oversampleAt(base * 2))
            assertEquals(1, audiophile.oversampleAt(base * 4))
            assertEquals(2, AudioSettings(quality = QualityMode.EXTREME).oversampleAt(base * 4))
        }
    }
    @Test fun liveControlsDoNotUndoFallbackOrApplyPendingFormatChanges() {
        val applied = AudioSettings(quality = QualityMode.EFFICIENT)
        val requested = applied.copy(quality = QualityMode.EXTREME, outputBits = 16,
            dither = DitherChoice.SHAPED, spatialMode = SpatialMode.DETAILED,
            captureRateMode = RatePolicy.Mode.EXPERIMENTAL_192K, autoHeadroom = false,
            gainProtection = false, experimentalBassUnmask = true)
        assertEquals(applied.copy(autoHeadroom = false, gainProtection = false,
            experimentalBassUnmask = true), applied.withLiveCaptureControls(requested))
    }
    @Test fun recordingMetadataChangesOnlyForAppliedNotPendingControls() {
        val applied = AudioSettings(spatialMode = SpatialMode.DETAILED,
            captureRateMode = RatePolicy.Mode.EVIDENCE_HIGH_RATE)
        val epoch = app.svan.CaptureEpoch(7, 96000, 2048, true, applied)
        val pending = applied.copy(spatialMode = SpatialMode.FAST, quality = QualityMode.EXTREME)
        assertEquals(epoch, epoch.copy(appliedSettings = applied.withLiveCaptureControls(pending)))
        val changed = epoch.copy(appliedSettings = applied.withLiveCaptureControls(
            pending.copy(experimentalBassUnmask = true)))
        assertNotEquals(epoch, changed) // a mixed-setting recording must be invalidated
        assertEquals(SpatialMode.DETAILED, changed.appliedSettings.spatialMode)
        assertEquals(QualityMode.AUDIOPHILE, changed.appliedSettings.quality)
        assertTrue(changed.appliedSettings.experimentalBassUnmask) // retained on UID reopen
    }
}
