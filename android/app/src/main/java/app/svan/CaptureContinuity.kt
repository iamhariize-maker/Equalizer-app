package app.svan

/** Audio-thread-only, allocation-free envelope used around recorder changes. */
class CaptureFade(private val fadeFrames: Int) {
    private var remaining=fadeFrames
    fun restart() { remaining=fadeFrames }
    fun apply(samples: FloatArray, count: Int, fadeOut: Boolean = false) {
        val frames=count/2
        for(frame in 0 until frames) {
            val startGain=if(remaining>0) 1f-remaining.toFloat()/fadeFrames else 1f
            if(remaining>0)remaining--
            val endGain=if(fadeOut) (frames-1-frame).toFloat()/maxOf(1,frames-1) else 1f
            val gain=startGain*endGain
            samples[2*frame]*=gain;samples[2*frame+1]*=gain
        }
    }
}

/** Grow only on new underruns; never shrink during a song or hide persistent starvation. */
class CaptureBufferRecovery {
    private var previous=0
    private var exhaustedWindows=0
    fun nextSize(underruns: Int, current: Int, capacity: Int, burst: Int): Int {
        val increased=underruns>previous
        previous=underruns
        if(!increased) { exhaustedWindows=0;return current }
        if(current<capacity) { exhaustedWindows=0;return minOf(capacity,maxOf(current+burst,current+current/2)) }
        return if(++exhaustedWindows>=3) -1 else current
    }
}
