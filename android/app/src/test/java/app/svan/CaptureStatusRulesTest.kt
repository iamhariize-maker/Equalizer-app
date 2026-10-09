package app.svan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStatusRulesTest {
    private val now = 10_000_000L

    private fun inputs(
        pkg: String = "com.spotify.music", choice: Boolean = false, manifest: Boolean? = true, heard: Boolean = false,
        optOut: String? = null, optOutAt: Long? = null, owner: String? = null, reason: String? = null, running: Boolean = true,
    ) = AppCaptureInputs(pkg, "Spotify", "9.1.90.2270", choice, manifest, heard, optOut, optOutAt, owner, reason, running)

    private fun standing(i: AppCaptureInputs) = CaptureStatusRules.evaluate(i, now).standing

    @Test fun anAppCarriedByTheEngineIsFull() {
        val s = CaptureStatusRules.evaluate(inputs(owner = "ENGINE_B_MUTED"), now)
        assertEquals(CaptureStanding.FULL, s.standing)
        assertTrue(s.headline.contains("active now"))
    }

    @Test fun directOptOutEvidenceMeansSystemOnlyAndNamesTheVersion() {
        val s = CaptureStatusRules.evaluate(inputs(optOut = "STREAM_NOT_CAPTURABLE", optOutAt = now - 5 * 60_000, owner = "ENGINE_A"), now)
        assertEquals(CaptureStanding.SYSTEM_ONLY_BY_APP, s.standing)
        assertTrue(s.detail.contains("9.1.90.2270"))
        assertTrue(s.detail.contains("5 min ago"))
        assertTrue(s.detail.contains("still apply through system effects"))
    }

    @Test fun aBlockOutranksAnEarlierSuccess() {
        assertEquals(CaptureStanding.SYSTEM_ONLY_BY_APP, standing(inputs(heard = true, optOut = "STREAM_NOT_CAPTURABLE", optOutAt = now)))
    }

    @Test fun aLiveRouteReasonThatIsADirectOptOutCounts() {
        assertEquals(CaptureStanding.SYSTEM_ONLY_BY_APP, standing(inputs(owner = "ENGINE_A", reason = "UID_CAPTURE_DISABLED")))
    }

    @Test fun theManifestAloneCanForbidCapture() {
        val s = CaptureStatusRules.evaluate(inputs(manifest = false), now)
        assertEquals(CaptureStanding.SYSTEM_ONLY_BY_APP, s.standing)
        assertTrue(s.detail.contains("manifest"))
    }

    @Test fun silenceIsNeverReportedAsABlock() {
        for (reason in listOf("SILENT_RECENTLY", "SILENT_AFTER_MUTE", "NO_CAPTURE_DATA", "INCONCLUSIVE", "CAPTURE_SILENCED")) {
            val s = CaptureStatusRules.evaluate(inputs(owner = "ENGINE_A", reason = reason), now)
            assertEquals(reason, CaptureStanding.UNCONFIRMED, s.standing)
            assertTrue(s.detail.contains("not proof"))
        }
    }

    @Test fun checkingIsUnconfirmedAndNotAFailure() {
        assertEquals(CaptureStanding.UNCONFIRMED, standing(inputs(owner = "ENGINE_A", reason = "CHECKING")))
        assertEquals(CaptureStanding.UNCONFIRMED, standing(inputs(owner = "ENGINE_A", reason = "WAITING_FOR_PLAYBACK")))
    }

    @Test fun capturePreviouslyHeardOnThisVersionIsAvailable() {
        val running = CaptureStatusRules.evaluate(inputs(heard = true), now)
        assertEquals(CaptureStanding.FULL, running.standing)
        assertFalse(running.detail.contains("Start the audiophile engine"))
        assertTrue(CaptureStatusRules.evaluate(inputs(heard = true, running = false), now).detail.contains("Start the audiophile engine"))
    }

    @Test fun theListenersChoiceIsRespectedFirst() {
        assertEquals(CaptureStanding.SYSTEM_ONLY_BY_CHOICE, standing(inputs(choice = true, owner = "ENGINE_B_MUTED", manifest = false)))
    }

    @Test fun anUnseenAppIsNotCheckedAndNeverGuessedFromItsName() {
        val s = CaptureStatusRules.evaluate(inputs(), now)
        assertEquals(CaptureStanding.NOT_CHECKED, s.standing)
        assertTrue(s.detail.contains("manifest allows capture"))
        assertTrue(CaptureStatusRules.evaluate(inputs(manifest = null), now).detail.contains("Play something"))
    }

    @Test fun blockedAppsAreListedFirstThenUnconfirmedThenFull() {
        fun st(label: String, s: CaptureStanding) = AppCaptureStatus("p.$label.x", label, null, s, "", "")
        val sorted = listOf(st("b", CaptureStanding.NOT_CHECKED), st("a", CaptureStanding.FULL), st("c", CaptureStanding.SYSTEM_ONLY_BY_APP),
            st("d", CaptureStanding.UNCONFIRMED), st("e", CaptureStanding.SYSTEM_ONLY_BY_CHOICE)).sortedWith(CaptureStatusRules.order)
        assertEquals(listOf("c", "d", "a", "e", "b"), sorted.map { it.label })
    }

    @Test fun timeAgoIsCoarseAndReadable() {
        assertEquals("just now", CaptureStatusRules.ago(30_000))
        assertEquals("5 min ago", CaptureStatusRules.ago(5 * 60_000))
        assertEquals("3 h ago", CaptureStatusRules.ago(3 * 3_600_000L))
        assertEquals("2 days ago", CaptureStatusRules.ago(2 * 86_400_000L))
    }
}
