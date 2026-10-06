package app.svan

import org.junit.Assert.*
import org.junit.Test

class DetectionFallbackTest {
    @Test fun recognitionWithoutAnAudioSessionCannotClaimProcessingOrCapturePeaks() {
        val players = mergeRecognizedPlayers(emptyList(), listOf(RecognizedPlayer("music", true))) { "Music" }
        val player = players.single()
        assertEquals("music", player.key)
        assertFalse(player.attachable)
        assertNull(player.engine)
        val state = WorkingState.derive(1, players, false)
        assertEquals(WorkingKind.UNREACHABLE, state.kind)
        assertFalse(state.showsCapturePeaks(true))
        assertNotNull(CapturePolicy.startupBlock(false, emptyList(), 99))
    }

    @Test fun pausedRecognizedPlayerNeverInventsActivePlayback() {
        val players = mergeRecognizedPlayers(emptyList(), listOf(RecognizedPlayer("music", false))) { it }
        assertEquals(WorkingKind.IDLE, WorkingState.derive(0, players, false).kind)
    }

    @Test fun recognitionNeverReplacesARouteOrCaptureVerdict() {
        val routed = WorkingPlayer("music", "Music", true, true, UiEngine.SYSTEM_EFFECTS)
        val players = mergeRecognizedPlayers(listOf(routed), listOf(RecognizedPlayer("music", true), RecognizedPlayer("other", true))) { it }
        assertEquals(routed, players.first())
        assertEquals(2, players.size)
        assertNull(players.last().engine)
    }

    @Test fun oemFailuresOfferSpecificActionsAndABasicFallback() {
        for (maker in listOf("OnePlus", "OPPO", "Xiaomi", "vivo", "TECNO", "unknown")) {
            val advice = detectionOemAdvice(maker)
            assertTrue(advice.contains("Basic detection still works"))
            assertTrue(advice.contains("retry"))
            assertTrue(advice.contains("optional player recognition"))
        }
        assertTrue(detectionOemAdvice("OnePlus").contains("Disable permission monitoring"))
        assertTrue(detectionOemAdvice("POCO").contains("USB debugging (Security settings)"))
        assertTrue(detectionOemAdvice("vivo").contains("Funtouch"))
    }
}
