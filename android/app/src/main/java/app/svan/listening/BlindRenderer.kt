package app.svan.listening

import android.content.Context
import app.svan.NativeEngine
import app.svan.model.*
import app.svan.svaramanas.*
import kotlin.math.abs

object BlindRenderer {
    data class Render(val rate: Int,val original: FloatArray,val enhanced: FloatArray,val levels: DoubleArray,val headphone: String,val configuration: String)
    fun render(context: Context,clip: WavClip,eq: EqState,settings: AudioSettings,request: SmartRequest): Render {
        var snapshot=eq
        // The automatic plan hears this exact excerpt, not whichever track was playing earlier.
        if(request.enabled) {
            val heard=NativeEngine(clip.rate,2,NativeEngine.Quality.EFFICIENT).use {engine->
                engine.setAnalysis(true);val scratch=clip.samples.copyOf();engine.process(scratch,scratch,scratch.size/2);engine.analysis()
            }
            val plan=SmartPlan.compute(request,heard,true)
            val ctx=if(request.mode==SmartMode.SVARESA)SvaresaBrain.layer(SvaresaSensors.read(context,request)) else null
            var layer=plan.toLayer(ctx).copy(protectEngine=request.mode==SmartMode.SVARESA,
                dynamicEq=if(request.mode==SmartMode.SVARESA&&request.selectiveEq)request.strength.coerceIn(0.0,1.0) else 0.0)
            if(eq.smartEqControl&&eq.smartEqMode==EqMode.GRAPHIC)layer=layer.copy(bands=NativeEngine.fitGraphic(layer.bands,eq.smartGraphicCount).bands.map {Band(it.type,it.freqHz,it.gainDb,it.q)})
            if(eq.smartEqControl) {
                val bands=eq.personalizeSmartBands(layer.bands);val scale=NativeEngine.overlapScale(bands)
                layer=layer.copy(bands=bands.map {if(it.gainDb>0)it.copy(gainDb=it.gainDb*scale) else it})
            }
            snapshot=eq.copy(smart=layer,smartBypass=false)
        }
        fun engine(state: EqState)=NativeEngine(clip.rate,2,settings.quality.oversample,settings.quality.stopbandDb,
            if(settings.dither==DitherChoice.OFF)0 else settings.outputBits,settings.dither.nativeMode,true,true).also {
            it.setBands(state.effectiveBands().map(Band::toNative));it.setPreampDb(state.effectivePreampDb())
            it.setBassCharacter(state.bassCharacter,state.bass.crossoverHz)
            val v=state.activeVocal;val i=state.activeInstrument;it.setStereoTuner(v.intimacy,v.warmth,v.smoothness,i.space,i.instruments)
            it.setDynamicEq(state.dynamicEq)
        }
        fun process(state: EqState): FloatArray =engine(state).use {e->
            val warm=clip.samples.copyOfRange(0,minOf(clip.samples.size,clip.rate*2));e.process(warm,warm,warm.size/2)
            val padded=clip.samples.copyOf(clip.samples.size+e.latencyFrames*2);e.process(padded,padded,padded.size/2)
            val aligned=padded.copyOfRange(e.latencyFrames*2,padded.size)
            val fade=maxOf(1,clip.rate/200)
            repeat(fade) {frame->val gain=frame.toFloat()/fade;repeat(2){ch->aligned[frame*2+ch]*=gain;aligned[aligned.size-2-frame*2+ch]*=gain}}
            aligned
        }
        val original=process(EqState(bands=emptyList()));val enhanced=process(snapshot)
        val levels=NativeEngine.nativeMatchComparison(original,enhanced,clip.rate)
        check(levels.size==6&&abs(levels[2]-levels[3])<=.1) {"This excerpt is too quiet or could not be matched. Choose another."}
        val description=snapshot.toJson().toString()+settings.toJson().toString()+request.toJson().toString()
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(description.toByteArray()).joinToString("") {"%02x".format(it)}
        return Render(clip.rate,original,enhanced,levels,snapshot.tuning?.headphone ?: "Uncalibrated",hash)
    }
}
