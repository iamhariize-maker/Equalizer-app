package app.svan
import org.junit.Assert.*
import org.junit.Test
class CaptureContinuityTest {
    @Test fun fadesAreLinkedContinuousAndEndAtExactZero() {
        val fade=CaptureFade(480);val x=FloatArray(2048){1f};fade.apply(x,x.size)
        assertEquals(0f,x[0],0f);assertEquals(1f,x.last(),0f)
        for(i in 1 until x.size/2){assertEquals(x[2*i],x[2*i+1],0f);assertTrue(x[2*i]-x[2*(i-1)]<=1f/480+.000001f)}
        x.fill(1f);fade.apply(x,x.size,true);assertEquals(1f,x[0],0f);assertEquals(0f,x.last(),0f)
        fade.restart();x.fill(1f);fade.apply(x,x.size);assertEquals(0f,x[0],0f)
    }
    @Test fun recoveryGrowsToCapacityThenStopsOnlyForPersistentNewUnderruns() {
        val recovery=CaptureBufferRecovery()
        assertEquals(512,recovery.nextSize(0,512,2048,256))
        assertEquals(768,recovery.nextSize(1,512,2048,256))
        assertEquals(768,recovery.nextSize(1,768,2048,256))
        assertEquals(2048,recovery.nextSize(2,1800,2048,256))
        assertEquals(2048,recovery.nextSize(3,2048,2048,256))
        assertEquals(2048,recovery.nextSize(4,2048,2048,256))
        assertEquals(-1,recovery.nextSize(5,2048,2048,256))
    }
    @Test fun shortSchedulingStallsDoNotExhaustRecoveryAtFasterPolling() {
        val recovery=CaptureBufferRecovery()
        var underruns=0
        repeat(20) {
            repeat(4) { assertEquals(2048,recovery.nextSize(++underruns,2048,2048,256,250)) }
            assertEquals(2048,recovery.nextSize(underruns,2048,2048,256,250))
        }
    }
    @Test fun sustainedStarvationStillFailsOpenAfterSixSecondsAtCapacity() {
        val recovery=CaptureBufferRecovery()
        repeat(23) { assertEquals(2048,recovery.nextSize(it+1,2048,2048,256,250)) }
        assertEquals(-1,recovery.nextSize(24,2048,2048,256,250))
    }
    @Test fun startupAndResetCountersDoNotLookLikeNewPlaybackFailures() {
        val recovery=CaptureBufferRecovery(initialUnderruns=7)
        assertEquals(512,recovery.nextSize(7,512,2048,256,250))
        repeat(20) { assertEquals(2048,recovery.nextSize(8,2048,2048,256,250)) }
        assertEquals(2048,recovery.nextSize(0,2048,2048,256,250))
        assertEquals(768,recovery.nextSize(1,512,2048,256,250))
    }
    @Test fun spatialWorkShedsImmediatelyAndNeedsTenCleanSecondsToReturn() {
        val r=CaptureSpatialRecovery(initialUnderruns=3)
        assertFalse(r.observe(3,250))
        assertTrue(r.observe(4,250))
        repeat(39) { assertTrue(r.observe(4,250)) }
        assertTrue(r.observe(5,250)) // another stall restarts the recovery dwell
        repeat(39) { assertTrue(r.observe(5,250)) }
        assertFalse(r.observe(5,250))
        assertFalse(r.observe(5,250))
    }
}
