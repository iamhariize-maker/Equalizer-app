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

/** Grow on new underruns; a short scheduling stall must not stop capture.
 * The fail-open deadline is elapsed audio time, independent of polling frequency. */
class CaptureBufferRecovery(initialUnderruns: Int = 0) {
    private var previous=initialUnderruns
    private var exhaustedMs=0L
    fun nextSize(underruns: Int, current: Int, capacity: Int, burst: Int, windowMs: Int = 2000): Int {
        val increased=underruns>previous
        previous=underruns
        if(!increased) { exhaustedMs=0;return current }
        if(current<capacity) { exhaustedMs=0;return minOf(capacity,maxOf(current+burst,current+current/2)) }
        exhaustedMs+=windowMs.coerceAtLeast(0)
        return if(exhaustedMs>=6000) -1 else current
    }
}

/** Shed spatial FFT work without reopening audio; recover only after a quiet interval. */
class CaptureSpatialRecovery(initialUnderruns: Int = 0) {
    private var previous=initialUnderruns
    private var healthyMs=0L
    var limited=false
        private set
    fun observe(underruns: Int, windowMs: Int): Boolean {
        if (underruns>previous) { limited=true;healthyMs=0 }
        else if (limited) {
            healthyMs+=windowMs.coerceAtLeast(0)
            if (healthyMs>=10000) { limited=false;healthyMs=0 }
        }
        previous=underruns
        return limited
    }
}
