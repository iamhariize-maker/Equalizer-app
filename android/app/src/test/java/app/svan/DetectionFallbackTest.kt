package app.svan

import org.junit.Assert.*
import org.junit.Test

class DetectionFallbackTest {
    @Test fun anonymousPlaybackNeverInventsAProcessedPlayer() {
        val state = WorkingState.derive(1, emptyList(), false)
        assertEquals(WorkingKind.UNREACHABLE, state.kind)
        assertFalse(state.showsCapturePeaks(true))
        assertNotNull(CapturePolicy.startupBlock(false, emptyList(), 99))
    }

    @Test fun oemFailuresOfferSpecificActionsAndABasicFallback() {
        for (maker in listOf("OnePlus", "OPPO", "Xiaomi", "vivo", "TECNO", "unknown")) {
            val advice = detectionOemAdvice(maker)
            assertTrue(advice.contains("Basic detection still works"))
            assertTrue(advice.contains("retry"))
            // Play Protect flags notification access in sideloaded apps as a fraud risk.
            assertFalse(advice.contains("player recognition"))
        }
        assertTrue(detectionOemAdvice("OnePlus").contains("Disable permission monitoring"))
        assertTrue(detectionOemAdvice("POCO").contains("USB debugging (Security settings)"))
        assertTrue(detectionOemAdvice("vivo").contains("Funtouch"))
    }
}
