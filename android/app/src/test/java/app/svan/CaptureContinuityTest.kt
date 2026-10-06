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
}
