package app.svan

import org.junit.Assert.*
import org.junit.Test

class SharedOutputHandoffTest {
    @Test fun captureMustAcknowledgeStopBeforeSharedOutputCanAttach() {
        val handoff = SharedOutputHandoff()
        assertFalse(handoff.request(captureActive = true))
        assertTrue(handoff.waiting)
        assertTrue(handoff.captureStopped(serviceEnabled = true))
        assertFalse(handoff.waiting)
        assertFalse(handoff.captureStopped(serviceEnabled = true))
    }

    @Test fun cancelOrDeviceChangeCannotAttachWhenLateStopArrives() {
        val handoff = SharedOutputHandoff()
        handoff.request(captureActive = true)
        handoff.cancel()
        assertFalse(handoff.captureStopped(serviceEnabled = true))
    }

    @Test fun shutdownCancelsPendingRequestEvenIfCaptureFinishesLater() {
        val handoff = SharedOutputHandoff()
        handoff.request(captureActive = true)
        assertFalse(handoff.captureStopped(serviceEnabled = false))
        assertFalse(handoff.captureStopped(serviceEnabled = true))
    }

    @Test fun noCaptureCanAttachImmediatelyAndRepeatedStopCannotReattach() {
        val handoff = SharedOutputHandoff()
        assertTrue(handoff.request(captureActive = false))
        assertFalse(handoff.waiting)
        assertFalse(handoff.captureStopped(serviceEnabled = true))
    }

    @Test fun virtualCaptureDevicesDoNotCancelButPhysicalConnectionsDo() {
        assertFalse(SharedOutputHandoff.physicalOutputChanged(listOf(25)))
        assertFalse(SharedOutputHandoff.physicalOutputChanged(emptyList()))
        assertTrue(SharedOutputHandoff.physicalOutputChanged(listOf(4)))
        assertTrue(SharedOutputHandoff.physicalOutputChanged(listOf(25, 8)))
    }
}
