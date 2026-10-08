package app.svan.listening

import java.io.File
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class ProofAbTimelineTest {
    private fun wav(rate: Int, bits: Int, frames: Int, amplitude: Double, phase: Double = 0.0): File {
        val file = File.createTempFile("proof-ab-source", ".wav")
        val samples = FloatArray(frames * 2) { i -> (amplitude * sin(2 * PI * 1000 * (i / 2) / rate + phase)).toFloat() }
        val bytes = ByteArray(samples.size * bits / 8)
        ProofWav.encode(samples, samples.size, bytes, bits, null)
        file.outputStream().use { ProofWav.writeHeader(it, rate, frames.toLong(), bits); it.write(bytes) }
        return file
    }

    private fun target() = File.createTempFile("proof-ab-timeline", ".wav")
    private fun payload(file: File) = file.readBytes().copyOfRange(44, file.length().toInt())
    private fun sameFrame(expected: ByteArray, actual: ByteArray, frame: Int, frameBytes: Int, outputFrame: Int = frame) {
        assertArrayEquals("frame $frame", expected.copyOfRange(frame * frameBytes, (frame + 1) * frameBytes),
            actual.copyOfRange(outputFrame * frameBytes, (outputFrame + 1) * frameBytes))
    }
    private fun segment(start: Long, end: Long) = ProofRecorder.Segment("settings", emptyMap(), 0.0, 0.0,
        LevelMeter(), LevelMeter(), emptyList(), start, end)

    @Test fun unmatchedTimelineKeepsSavedPcmExactlyOutsideFadesAtMultipleFormats() {
        for (rate in listOf(44100, 48000, 96000, 192000)) for (bits in listOf(16, 24)) {
            val dry = wav(rate,bits,rate/10,.3); val wet = wav(rate,bits,rate/10,.2,.7); val out = target()
            try {
                val first = rate/50; val second = rate/20; val fade = (rate*.005).roundToInt()
                ProofAbTimeline.render(dry,wet,out,listOf(ProofRecorder.AbSwitch(first.toLong(),true),ProofRecorder.AbSwitch(second.toLong(),false)),emptyList(),0,false)
                val d=payload(dry); val w=payload(wet); val result=payload(out); val width=bits/8*2
                assertEquals(ProofWav.header(dry),ProofWav.header(out))
                for(i in 0 until first) sameFrame(d,result,i,width)
                sameFrame(d,result,first,width) // fade starts on the marked frame, at the current source
                for(i in first+fade until second) sameFrame(w,result,i,width)
                sameFrame(w,result,second,width)
                for(i in second+fade until rate/10) sameFrame(d,result,i,width)
            } finally { dry.delete();wet.delete();out.delete() }
        }
    }

    @Test fun opposedMinusSixDbToneSwitchHasMeasuredBoundAt48k() {
        val amplitude=10.0.pow(-6.0/20); val dry=wav(48000,24,4800,amplitude); val wet=wav(48000,24,4800,-amplitude); val out=target()
        try {
            ProofAbTimeline.render(dry,wet,out,listOf(ProofRecorder.AbSwitch(1013,true)),emptyList(),0,false)
            val pcm=ProofWav.read(out)
            val maxStep=(1000 until 1300).maxOf { abs(pcm[it*2]-pcm[(it-1)*2]) }
            assertTrue("1kHz/-6dBFS opposed switch max step=$maxStep (48kHz bound .085)",maxStep<.085)
        } finally {dry.delete();wet.delete();out.delete()}
    }

    @Test fun plusSixDbAfterMatchesWithinPointZeroFiveDbOverExactUnionRanges() {
        for(bits in listOf(16,24)) {
            val dry=wav(48000,bits,9600,.2);val wet=wav(48000,bits,9600,.2*10.0.pow(6.0/20));val out=target()
            try {
                val ranges=ProofAbTimeline.render(dry,wet,out,listOf(ProofRecorder.AbSwitch(500,true),ProofRecorder.AbSwitch(7000,false)),
                    listOf(segment(0,3000),segment(3000,9600)),0,true)
                assertEquals(listOf(0L,500L,3000L,7000L),ranges.map{it.startFrame})
                ranges.forEach { assertEquals(10.0.pow(-6.0/20),it.gainLinear,.0001); assertFalse(it.limitedByPeak) }
                val d=ProofWav.read(dry);val result=ProofWav.read(out)
                for(range in listOf(1000 until 2800,3500 until 6500)) {
                    val ratio=range.sumOf{result[it*2].toDouble().pow(2)}/range.sumOf{d[it*2].toDouble().pow(2)}
                    assertEquals(0.0,10*log10(ratio),.05)
                }
            } finally {dry.delete();wet.delete();out.delete()}
        }
    }

    @Test fun rapidDuplicateMarksRetargetCurrentAngleAndLastMarkAtAFrameWins() {
        val dry=wav(48000,24,4800,.3);val wet=wav(48000,24,4800,-.3);val out=target()
        try {
            val marks=listOf(ProofRecorder.AbSwitch(1000,true),ProofRecorder.AbSwitch(1010,false),ProofRecorder.AbSwitch(1010,true),
                ProofRecorder.AbSwitch(1050,false),ProofRecorder.AbSwitch(1080,true),ProofRecorder.AbSwitch(1100,true))
            ProofAbTimeline.render(dry,wet,out,marks,emptyList(),0,false)
            val d=ProofWav.read(dry);val w=ProofWav.read(wet);val result=ProofWav.read(out)
            val angleAt1080=(PI/2*50/240)*(1-30.0/240)
            assertEquals((cos(angleAt1080)*d[1080*2]+sin(angleAt1080)*w[1080*2]).toFloat(),result[1080*2],2e-7f)
            sameFrame(payload(wet),payload(out),1320,6) // duplicate After does not restart the fade
            val maxStep=(995 until 1330).maxOf{abs(result[it*2]-result[(it-1)*2])}
            assertTrue("rapid-switch maximum step=$maxStep",maxStep<.055)
        } finally {dry.delete();wet.delete();out.delete()}
    }

    @Test fun cropAtFirstAfterStartsAfterAndSyncBeforeOrAfterUsesAbsoluteFrameMarks() {
        val dry=wav(48000,24,4800,.2);val wet=wav(48000,24,4800,-.3)
        try {
            val marks=listOf(ProofRecorder.AbSwitch(1000,true),ProofRecorder.AbSwitch(2500,false))
            for(start in listOf(500L,1000L,2000L,2700L,4800L)) {
                val out=target()
                try {
                    val ranges=ProofAbTimeline.render(dry,wet,out,marks,listOf(segment(0,1500),segment(1500,4800)),start,false)
                    assertEquals(4800L-start,ProofWav.header(out).frames)
                    assertEquals(0L,ranges.first().startFrame) // report also covers ranges before the crop
                    if(start<4800) {
                        val chosen=if(start in 1000..2499)wet else dry
                        sameFrame(payload(chosen),payload(out),start.toInt(),6,0)
                    }
                } finally {out.delete()}
            }
        } finally {dry.delete();wet.delete()}
    }

    @Test fun peakLimitedMatchingIsReportedSeparatelyFromFadeClipping() {
        for(bits in listOf(16,24)) {
          val dry=wav(48000,bits,4800,.8);val wet=wav(48000,bits,4800,.2);val out=target()
          try {
            // One tall wet sample limits an otherwise +12dB matching request.
            java.io.RandomAccessFile(wet,"rw").use{f->val width=bits/8*2;val bytes=ByteArray(width);ProofWav.encode(floatArrayOf(.99f,.99f),2,bytes,bits,null);f.seek(44+2000*width.toLong());f.write(bytes)}
            val ranges=ProofAbTimeline.render(dry,wet,out,listOf(ProofRecorder.AbSwitch(100,true)),emptyList(),0,true)
            assertTrue(ranges.last().limitedByPeak)
            assertTrue(ranges.last().gainLinear<1.02)
            assertEquals(0L,ranges.sumOf{it.clippedSamples})
          } finally {dry.delete();wet.delete();out.delete()}
        }
    }

    @Test fun rangeGainChangesFadeWhileAfterStaysSelected() {
        val dry=wav(48000,24,4800,.2);val wet=wav(48000,24,4800,.4);val out=target()
        try {
            java.io.RandomAccessFile(dry,"rw").use { f ->
                val pcm=FloatArray((4800-1000)*2){i->(.1*sin(2*PI*1000*(1000+i/2)/48000)).toFloat()}
                val bytes=ByteArray(pcm.size*3);ProofWav.encode(pcm,pcm.size,bytes,24,null);f.seek(44+1000*6L);f.write(bytes)
            }
            ProofAbTimeline.render(dry,wet,out,listOf(ProofRecorder.AbSwitch(0,true)),listOf(segment(0,1000),segment(1000,4800)),0,true)
            val w=ProofWav.read(wet);val result=ProofWav.read(out)
            assertEquals(w[2000]*.5f,result[2000],2e-7f) // use old gain on the boundary sample
            val maxStep=(990 until 1300).maxOf{abs(result[it*2]-result[(it-1)*2])}
            assertTrue("matched range-gain transition maximum step=$maxStep",maxStep<.035)
            for(frame in 1240 until 4800) assertEquals(w[frame*2]*.25f,result[frame*2],2e-7f)
        } finally {dry.delete();wet.delete();out.delete()}
    }

    @Test fun transformedSixteenBitSamplesGetTpdfWhileTwentyFourBitDoesNotAddNoise() {
        for(bits in listOf(16,24)) {
            fun constant(value: Float): File {
                val file=target();val pcm=FloatArray(8192){value};val bytes=ByteArray(pcm.size*bits/8)
                ProofWav.encode(pcm,pcm.size,bytes,bits,null)
                file.outputStream().use{ProofWav.writeHeader(it,48000,4096,bits);it.write(bytes)}
                return file
            }
            val dry=constant(.25f);val wet=constant(.5f);val out=target()
            try {
                ProofAbTimeline.render(dry,wet,out,listOf(ProofRecorder.AbSwitch(0,true)),emptyList(),0,true)
                if(bits==24) assertArrayEquals(payload(dry),payload(out))
                else {
                    val pcm=ProofWav.read(out);val errors=pcm.map{(it-.25f)*32768}
                    assertTrue(errors.all{abs(it)<=1})
                    assertTrue(errors.count{it!=0f} in (pcm.size/10)..(pcm.size*4/10))
                    assertEquals(0.0,errors.sumOf{it.toDouble()}/errors.size,.04)
                }
            } finally {dry.delete();wet.delete();out.delete()}
        }
    }
}
