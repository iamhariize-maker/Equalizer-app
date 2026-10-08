package app.svan

import app.svan.svaramanas.AutoHeadphoneCommit
import app.svan.svaramanas.SmartMode
import app.svan.svaramanas.SmartRequest
import org.junit.Assert.*
import org.junit.Test

class AutoHeadphoneCommitTest {
    private val on = SmartRequest(enabled = true, mode = SmartMode.SVARESA)

    @Test fun delayedCorrectionCannotReplaceAManualChoiceMadeDuringDownload() {
        val before = Any()
        val manualChoice = Any()
        assertTrue(AutoHeadphoneCommit.allowed(on, "A", "A", before, before))
        assertFalse(AutoHeadphoneCommit.allowed(on, "A", "A", before, manualChoice))
        assertFalse(AutoHeadphoneCommit.allowed(on, "A", "A", null, manualChoice))
    }

    @Test fun disablingAutoMasterRecognitionOrChangingModeRejectsOldResult() {
        for (request in listOf(on.copy(enabled = false), on.copy(autoHeadphone = false), on.copy(mode = SmartMode.GUIDED)))
            assertFalse(AutoHeadphoneCommit.allowed(request, "A", "A", null, null))
    }

    @Test fun unplugOrDeviceSwapRejectsOldCorrection() {
        assertFalse(AutoHeadphoneCommit.allowed(on, null, "A", null, null))
        assertFalse(AutoHeadphoneCommit.allowed(on, "B", "A", null, null))
        assertTrue(AutoHeadphoneCommit.allowed(on, "A", "A", null, null))
    }
}
