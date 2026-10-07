package app.svan.listening

import app.svan.NativeEngine
import app.svan.EqController
import kotlin.math.*
import org.json.JSONObject

/** Release/JNI regression. Memory only: does not play tones or alter saved controls. */
object ContinuityLab {
    fun verify(fs: Int = app.svan.CaptureService.epoch?.sampleRate ?: app.svan.RatePolicy.SAFE_HZ) {
        fun measure(backing: Double, detail: Double, hz: Double): Double {
            val audio=FloatArray(fs*2)
            repeat(fs){ val x=(.05*sin(2*PI*hz*it/fs)).toFloat();audio[it*2]=x;audio[it*2+1]=-x }
            NativeEngine(fs,2,1,120.0,0,0,false,false).use { e ->
                e.setStereoTuner(0.0,0.0,0.0,0.0,0.0,backing,detail)
                // Include the native limiter's fixed delay when measuring magnitude.
                e.process(audio,audio,fs)
            }
            var sinSum=0.0;var cosSum=0.0
            for(i in fs/2 until fs){sinSum+=audio[2*i]*sin(2*PI*hz*i/fs);cosSum+=audio[2*i]*cos(2*PI*hz*i/fs)}
            return 20*log10(2*hypot(sinSum,cosSum)/(fs/2)/.05)
        }
        val backing=measure(1.0,0.0,1600.0);val spatial=measure(0.0,1.0,8000.0)
        check(backing in 1.8..2.2 && spatial in 1.3..1.7)
        val low=measure(1.0,1.0,60.0);check(abs(low)<.1)
        val audio=FloatArray(fs*2*2)
        repeat(fs*2){audio[it*2]=(.8*sin(2*PI*1600*it/fs)).toFloat();audio[it*2+1]=-audio[it*2]}
        NativeEngine(fs,2,NativeEngine.Quality.EFFICIENT).use {e->
            e.setStereoTuner(1.0,1.0,1.0,1.0,1.0,1.0,1.0)
            e.process(audio,audio,audio.size/2)
        }
        val peak=NativeEngine.nativeReconstructedPeak(audio,fs);check(peak<.93)
        EqController.log("CONTINUITY_LAB_READY "+JSONObject().put("backingDb",backing).put("spatialDb",spatial).put("bassDb",low).put("peak",peak))
    }
}
