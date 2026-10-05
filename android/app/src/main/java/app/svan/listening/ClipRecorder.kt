package app.svan.listening

import app.svan.CaptureService
import app.svan.EqController
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay

/** Opt-in eight-second memory-only tap of already-authorized capture, before DSP. */
object ClipRecorder {
    private class Slot(val audio: FloatArray) {val count=AtomicInteger(0)}
    private val current=AtomicReference<Slot?>(null)
    fun offer(input: FloatArray, samples: Int) {
        val slot=current.get() ?: return
        val start=slot.count.get();val n=minOf(samples-samples%2,slot.audio.size-start)
        if(n<=0)return
        input.copyInto(slot.audio,start,0,n);slot.count.set(start+n)
    }
    suspend fun record(): WavClip {
        check(CaptureService.isRunning) {"Start permitted capture in Hi-Fi, or choose a WAV"}
        val slot=Slot(FloatArray(EqController.SAMPLE_RATE*2*8));check(current.compareAndSet(null,slot)) {"A clip is already being recorded"}
        try {
            repeat(120){if(slot.count.get()==slot.audio.size)return WavClip(EqController.SAMPLE_RATE,slot.audio);delay(100)}
            error("Capture stopped before the clip was complete")
        } finally {current.compareAndSet(slot,null)}
    }
}
