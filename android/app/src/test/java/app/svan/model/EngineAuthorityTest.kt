package app.svan.model

import org.junit.Assert.*
import org.junit.Test

class EngineAuthorityTest {
    private val manual=AudioSettings(autoHeadroom=false,gainProtection=false)
    private val automatic=EqState(smart=SmartLayer(emptyList(),0.0,protectEngine=true))
    @Test fun masterAddsProtectionWithoutChangingManualChoicesOrQuality() {
        val effective=manual.effectiveFor(automatic)
        assertTrue(effective.autoHeadroom);assertTrue(effective.gainProtection)
        assertFalse(manual.autoHeadroom);assertFalse(manual.gainProtection)
        assertTrue(effective.sameCaptureFormat(manual))
        assertEquals(manual,manual.effectiveFor(automatic.copy(smart=null)))
    }
    @Test fun protectionStaysLinkedAcrossCompareAndEqBypassButGuideDoesNotClaimIt() {
        assertTrue(manual.effectiveFor(automatic.copy(smartBypass=true)).gainProtection)
        assertTrue(manual.effectiveFor(automatic.copy(enabled=false)).autoHeadroom)
        assertEquals(manual,manual.effectiveFor(automatic.copy(smart=SmartLayer(emptyList(),0.0))))
    }
    @Test fun onlyActualCaptureFormatChangesRequireARebuild() {
        assertTrue(manual.sameCaptureFormat(manual.copy(autoHeadroom=true,gainProtection=true,systemBands=64,systemFrameMs=10)))
        assertFalse(manual.sameCaptureFormat(manual.copy(quality=QualityMode.EXTREME)))
        assertFalse(manual.sameCaptureFormat(manual.copy(dither=DitherChoice.TPDF)))
        assertFalse(manual.sameCaptureFormat(manual.copy(outputBits=16)))
    }
}
