package app.svan.listening

import app.svan.CaptureService
import app.svan.CaptureEpoch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay

/** Opt-in eight-second memory-only tap of already-authorized capture, before DSP. */
object ClipRecorder {
    private class Slot(val epoch: CaptureEpoch, val audio: FloatArray) {
        val count=AtomicInteger(0)
        @Volatile var invalid = false
    }
    private val current=AtomicReference<Slot?>(null)
    fun captureChanged(epoch: CaptureEpoch?) {
        current.get()?.let { if (it.epoch != epoch) it.invalid = true }
    }
    fun offer(input: FloatArray, samples: Int, epoch: CaptureEpoch?) {
        val slot=current.get() ?: return
        if (slot.invalid || slot.epoch != epoch) { slot.invalid = true; return }
        val start=slot.count.get();val n=minOf(samples-samples%2,slot.audio.size-start)
        if(n<=0)return
        input.copyInto(slot.audio,start,0,n);slot.count.set(start+n)
    }
    suspend fun record(): WavClip {
        check(CaptureService.isRunning) {"Start permitted capture in Hi-Fi, or choose a WAV"}
        val epoch = checkNotNull(CaptureService.epoch) { "Wait for capture to finish starting" }
        val slot=Slot(epoch, FloatArray(epoch.sampleRate*2*8));check(current.compareAndSet(null,slot)) {"A clip is already being recorded"}
        try {
            repeat(120){
                check(!slot.invalid && CaptureService.epoch == epoch) { "Capture source, rate, processing mode or live controls changed; record a new clip" }
                if(slot.count.get()==slot.audio.size)return WavClip(epoch.sampleRate,slot.audio,epoch.appliedSettings)
                delay(100)
            }
            error("Capture stopped before the clip was complete")
        } finally {current.compareAndSet(slot,null)}
    }
}
