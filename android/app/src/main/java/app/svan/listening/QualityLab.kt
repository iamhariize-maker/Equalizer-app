package app.svan.listening

import android.content.Context
import app.svan.NativeEngine
import app.svan.EqController
import app.svan.model.*
import app.svan.svaramanas.*
import java.io.File
import org.json.JSONObject
import kotlin.math.*

/** Offline release regression probe. Never plays test tones or changes the listener's settings. */
object QualityLab {
    fun verify(context: Context, fs: Int = app.svan.CaptureService.epoch?.sampleRate ?: app.svan.RatePolicy.SAFE_HZ) {
        val tone=FloatArray(fs*2)
        repeat(fs){tone[it*2]=(1.35*sin(PI*.5*it+PI*.25)).toFloat();tone[it*2+1]=tone[it*2]}
        val before=NativeEngine.nativeReconstructedPeak(tone,fs)
        NativeEngine(fs,2,NativeEngine.Quality.EFFICIENT).use {it.process(tone,tone,fs)}
        val after=NativeEngine.nativeReconstructedPeak(tone,fs);check(before>1.3&&after<.93)
        val source=FloatArray(fs*2*8)
        repeat(fs*8){source[it*2]=(.2*sin(2*PI*330*it/fs)+.04*sin(2*PI*1000*it/fs)).toFloat();source[it*2+1]=source[it*2]}
        val dynamic=NativeEngine(fs,2,1,120.0,0,0,false,false).use {e->
            e.setDynamicEq(1.0);val copy=source.copyOf();e.process(copy,copy,copy.size/2);e.dynamicReductions()
        };check(dynamic[1]<-1.2&&dynamic.all {it<=0}&&dynamic.sum()>=-3.01)
        val measurement=StringBuilder();val target=StringBuilder()
        repeat(256){val f=20*1000.0.pow(it/255.0);measurement.append("$f ${8*exp(-log2(f/330).pow(2)*2)}\n");target.append("$f 0\n")}
        val calibration=NativeEngine.calibratedTuning(measurement.toString(),target.toString(),false,1.0,64,0.0,0.0) ?: error("Calibration failed")
        check(calibration.fit.rmsErrorDb<.4&&calibration.fit.bands.size==64)
        val tuning=Tuning(true,"Measured test headphone","Synthetic fixture","Flat target",calibration.fit.bands.map {Band(it.type,it.freqHz,it.gainDb,it.q)},calibration.fit.rmsErrorDb)
        val r=BlindRenderer.render(context,WavClip(fs,source),EqState(tuning=tuning,smartEqControl=true),AudioSettings(quality=QualityMode.EFFICIENT),
            SmartRequest(enabled=true,mode=SmartMode.SVARESA,night=NightMode.OFF,volumeAware=false,routeAware=false,autoHeadphone=false,selectiveEq=true))
        check(abs(r.levels[2]-r.levels[3])<.1&&r.levels[4]<=0&&r.levels[5]<=0)
        val data=JSONObject().put("truePeakBefore",before).put("truePeakAfter",after).put("dynamicDb",dynamic[1])
            .put("calibrationRmsDb",calibration.fit.rmsErrorDb).put("matchDb",abs(r.levels[2]-r.levels[3]))
            .put("trimOriginalDb",r.levels[4]).put("trimProcessedDb",r.levels[5]).put("levelOriginal",r.levels[2]).put("levelProcessed",r.levels[3])
        AudioQualityLab.verify()
        File(context.filesDir,"quality-lab.json").writeText(data.toString());EqController.log("QUALITY_LAB_READY $data")
    }
}
