package app.svan

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CaptureRecoveryTest {
    @Test fun bufferedPreMuteAudioCannotImmediatelyConfirmTheMuteTap() {
        assertTrue(CaptureEvidencePolicy.tapSettled("unmuted", 0))
        assertFalse(CaptureEvidencePolicy.tapSettled("muted", 199_999_999))
        assertTrue(CaptureEvidencePolicy.tapSettled("muted", 200_000_000))
        // The warmup only opens the evidence window. A separate finite signal is still required.
        assertFalse(CaptureEvidencePolicy.hasSignal(floatArrayOf(0f), 1))
    }

    @Test fun aRecorderWithNoFramesFailsOpenOnElapsedTimeRatherThanFrameCount() {
        val watchdog = CaptureNoDataWatchdog(timeoutNs = 100)
        assertFalse(watchdog.observe(0, 1_000))
        assertFalse(watchdog.observe(0, 1_099))
        assertTrue(watchdog.observe(0, 1_100))
        // Actual zero-valued PCM still consists of delivered samples: it is a different failure path.
        assertFalse(watchdog.observe(512, 1_101))
        assertFalse(watchdog.observe(0, 2_000))
        watchdog.reset() // new source
        assertFalse(watchdog.observe(0, 3_000))
        assertFalse(watchdog.observe(0, 3_099))
    }

    @Test fun aUidWideOptOutIsRecognisedEvenWhenPlayerAttributesAllowCapture() {
        val policy = CaptureUidPolicyReport.parse("""
            AudioPolicyManager:
              AllowedCapturePolicies:
                - uid=10123 flag_mask=0x400
                - uid=10124 flag_mask=0x1400
                - uid=10125 flag_mask=0
              Audio patches:
                - uid=10126 flag_mask=0x400
        """.trimIndent())!!
        assertTrue(policy.blocks(10123))
        assertTrue(policy.blocks(10124))
        assertFalse(policy.blocks(10125))
        assertFalse(policy.blocks(10126))
        assertEquals(3, policy.policies.size)
    }

    @Test fun missingOrUnreadableUidPolicyIsUnknownRatherThanAnOptOut() {
        assertNull(CaptureUidPolicyReport.parse("Permission Denial: can't dump AudioPolicyService"))
        assertNull(CaptureUidPolicyReport.parse("history: uid=10123 flag_mask=0x1400"))
        val empty = CaptureUidPolicyReport.parse("AllowedCapturePolicies:\n\nAudio patches:")!!
        assertTrue(empty.policies.isEmpty())
        assertFalse(empty.blocks(10123))
    }

    @Test fun policyBitsAreScopedToTheirUidAndDoNotConfuseOtherAudioFlags() {
        val policy = CaptureUidPolicyReport.parse("AllowedCapturePolicies:\n - uid=10123 flag_mask=0x4\n - uid=10124 flag_mask=4096")!!
        assertFalse(policy.blocks(10123))
        assertTrue(policy.blocks(10124))
        assertFalse(policy.blocks(10125))
    }

    @Test fun silenceAndLegacyBlocksCannotLockAnAppUntilAnUpdate() {
        assertFalse(CaptureEvidencePolicy.reusableStoredValue("BLOCKED"))
        assertFalse(CaptureEvidencePolicy.reusableStoredValue(null))
        assertTrue(CaptureEvidencePolicy.reusableStoredValue("CAPTURABLE"))
    }

    @Test fun installedManifestPolicyFollowsAndroidDefaultAndExplicitOverrides() {
        assertFalse(CaptureEvidencePolicy.manifestAllows(28, null))
        assertTrue(CaptureEvidencePolicy.manifestAllows(29, null))
        assertFalse(CaptureEvidencePolicy.manifestAllows(36, false))
        assertTrue(CaptureEvidencePolicy.manifestAllows(28, true))
    }

    @Test fun corruptSamplesAndEmptyReadsCannotAdmitASource() {
        assertFalse(CaptureEvidencePolicy.hasSignal(floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, 0f), 3))
        assertFalse(CaptureEvidencePolicy.hasSignal(floatArrayOf(0.1f), 0))
        assertFalse(CaptureEvidencePolicy.hasSignal(floatArrayOf(0f, 0f, 0.1f), 2))
        assertTrue(CaptureEvidencePolicy.hasSignal(floatArrayOf(0f, -0.00001f), 2))
    }

    @Test fun idleMainRecordMustCloseBeforeAProbeCanOpen() {
        val gate = CaptureRecorderGate()
        val main = gate.tryAcquire("main")!!
        assertNull(gate.tryAcquire("probe"))
        main.close()
        val probe = gate.tryAcquire("probe")!!
        assertNull(gate.tryAcquire("main"))
        probe.close()
        assertNotNull(gate.tryAcquire("main"))
    }

    @Test fun aStaleCloseCannotReleaseAnotherRecordersLease() {
        val gate = CaptureRecorderGate()
        val first = gate.tryAcquire("main")!!
        first.close()
        val probe = gate.tryAcquire("probe")!!
        first.close()
        assertNull(gate.tryAcquire("main"))
        probe.close()
        assertNotNull(gate.tryAcquire("main"))
    }

    @Test fun pendingProbeCanBeCancelledWithoutOpeningOrStealingAMainRecorder() {
        val gate = CaptureRecorderGate()
        val main = gate.tryAcquire("main")!!
        try {
            gate.await("probe", active = { false })
            fail("cancelled probe opened a recorder")
        } catch (_: ProbeCancelled) { }
        assertNull(gate.tryAcquire("probe"))
        main.close()
    }

    @Test fun aTimedOutProbeCannotStealTheMainLease() {
        val gate = CaptureRecorderGate()
        val main = gate.tryAcquire("main")!!
        try {
            gate.await("probe", timeoutMs = 0, active = { true })
            fail("occupied recorder was stolen")
        } catch (_: IllegalStateException) { }
        assertEquals("main", gate.currentPurpose)
        main.close()
        assertNotNull(gate.tryAcquire("probe"))
    }

    @Test fun aWaitingProbeAcquiresOnlyAfterTheMainRecorderReleases() {
        val gate = CaptureRecorderGate()
        val main = gate.tryAcquire("main")!!
        val attempted = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val waiting = worker.submit<CaptureRecorderGate.Lease> {
                gate.await("probe", active = { attempted.countDown(); true })
            }
            assertTrue(attempted.await(1, TimeUnit.SECONDS))
            assertFalse(waiting.isDone)
            main.close()
            val probe = waiting.get(1, TimeUnit.SECONDS)
            assertNull(gate.tryAcquire("main"))
            probe.close()
        } finally { main.close(); worker.shutdownNow() }
    }
}
