package app.svan.listening
import org.junit.Test
import org.junit.Assert.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import app.svan.model.*
import app.svan.svaramanas.SmartRequest

class ListeningTest {
    private fun wav(format: Int=1,bits: Int=16,fs: Int=16000): ByteArray {
        val size=fs*5*bits/8
        val b=ByteBuffer.allocate(44+size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36+size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(format.toShort()).putShort(1)
            .putInt(fs).putInt(fs*bits/8).putShort((bits/8).toShort()).putShort(bits.toShort()).put("data".toByteArray()).putInt(size)
        return b.array()
    }
    @Test fun monoPcmIsDecodedIntoAlignedStereo(){val bytes=wav();bytes[45]=16;val clip=WavClip.decode(bytes);assertEquals(16000,clip.rate);assertEquals(160000,clip.samples.size);assertEquals(.125f,clip.samples[0],1e-6f);assertEquals(clip.samples[0],clip.samples[1],0f)}
    @Test fun pcm24AndFloatAreDecoded(){val bytes=wav(bits=24);bytes[46]=(-128).toByte();assertEquals(-1f,WavClip.decode(bytes).samples[0],0f);val floats=wav(3,32);ByteBuffer.wrap(floats).order(ByteOrder.LITTLE_ENDIAN).putFloat(44,.25f);assertEquals(.25f,WavClip.decode(floats).samples[0],0f)}
    @Test fun bothRateFamiliesAnd192kRetainCorrectDurationAndMetadata() {
        for (fs in listOf(44100, 48000, 88200, 96000, 176400, 192000)) {
            val clip = WavClip.decode(wav(3, 32, fs))
            assertEquals(fs, clip.rate)
            assertEquals(fs * 5 * 2, clip.samples.size)
            assertNull(clip.captureSettings)
        }
    }
    @Test fun truncatedAndNonFiniteClipsAreRejected(){assertThrows(IllegalArgumentException::class.java){WavClip.decode(wav().copyOf(100))};val f=wav(3,32);ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN).putFloat(44,Float.NaN);assertThrows(IllegalArgumentException::class.java){WavClip.decode(f)}}
    @Test fun hiddenAssignmentsMapVotesCorrectly(){for(isA in listOf(false,true)){val round=BlindRound(isA);assertEquals(isA,round.enhancedFor(true));assertEquals(!isA,round.enhancedFor(false));assertEquals(isA,round.result(1));assertEquals(!isA,round.result(2));assertNull(round.result(0))}}
    @Test fun calibrationAndSelectiveChoiceSurviveRestart(){val c=CorrectionCalibration(.6,.5,20.0,20000.0,"Measurement-derived target","test","target");val tuning=Tuning(true,"Fixture","Measured","Flat",emptyList(),.1,calibration=c);assertEquals(c,Tuning.fromJson(tuning.toJson()).calibration);val request=SmartRequest(selectiveEq=false);assertFalse(SmartRequest.fromJson(request.toJson()).selectiveEq)}
    @Test fun automaticDynamicEqBypassesAndPreservesManualSmoothness(){
        val smart=SmartLayer(emptyList(),0.0,smoothness=.5,dynamicEq=1.0)
        val e=EqState(smart=smart,vocal=VocalTuner(smoothness=.3))
        assertEquals(1.0,e.dynamicEq,0.0);assertEquals(.3,e.activeVocal.smoothness,0.0)
        assertEquals(.5,e.systemSmoothness,0.0) // native replacement must not remove Engine A's smoother
        assertEquals(.3,e.copy(smartBypass=true).systemSmoothness,0.0)
        assertEquals(0.0,e.copy(enabled=false).systemSmoothness,0.0)
        assertEquals(0.0,e.copy(smartBypass=true).dynamicEq,0.0);assertEquals(0.0,e.copy(enabled=false).dynamicEq,0.0)
    }
}
