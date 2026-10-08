package app.svan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiagnosticReportTest {
    @Test fun hardwareAddressesAreMaskedToTheirLastByte() {
        val masked = DiagnosticReport.maskHardwareAddresses("- type=8 Buds Air 8 A1:B2:C3:D4:E5:F6\npath=BT(41-22-33-44-55-0a)")
        assertEquals("- type=8 Buds Air 8 **:**:**:**:**:F6\npath=BT(**:**:**:**:**:0a)", masked)
        assertFalse(masked.contains("A1:B2"))
    }

    @Test fun ordinaryNumbersAndVersionsAreLeftAlone() {
        val text = "session=1234 flags=0x800 version 0.5.8 rate=48000 time 12:34:56"
        assertEquals(text, DiagnosticReport.maskHardwareAddresses(text))
    }
}
