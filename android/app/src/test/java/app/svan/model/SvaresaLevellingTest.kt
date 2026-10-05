package app.svan.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SvaresaLevellingTest {
    private val svaresa = SmartLayer(emptyList(), 0.0, levelling = 0.6)

    @Test fun levellingIsAppliedWhileSvaresaDrives() {
        assertEquals(0.6, EqState(smart = svaresa).levelling!!, 1e-9)
    }

    /** null would switch the multiband stage off and re-create every system effect on each hold-to-compare. */
    @Test fun bypassAndEqSwitchKeepTheStageButNeutral() {
        assertEquals(0.0, EqState(smart = svaresa, smartBypass = true).levelling!!, 1e-9)
        assertEquals(0.0, EqState(smart = svaresa, enabled = false).levelling!!, 1e-9)
    }

    @Test fun noSvaresaMeansNoStage() {
        assertNull(EqState().levelling)
        assertNull(EqState(smart = SmartLayer(emptyList(), 0.0)).levelling)
    }
}
