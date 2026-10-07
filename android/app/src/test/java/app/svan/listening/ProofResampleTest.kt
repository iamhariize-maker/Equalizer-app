package app.svan.listening

import java.io.File
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class ProofResampleTest {
    private fun wav(rate: Int, hz: Double, frames: Int = rate / 5): File {
        val file = File.createTempFile("proof-rate", ".wav")
        val samples = FloatArray(frames * 2) { i -> (0.2 * sin(2 * PI * hz * (i / 2) / rate)).toFloat() * if (i % 2 == 0) 1f else -1f }
        val bytes = ByteArray(samples.size * 3)
        ProofWav.encode(samples, samples.size, bytes, 24, null)
        file.outputStream().use { ProofWav.writeHeader(it, rate, frames.toLong(), 24); it.write(bytes) }
        return file
    }
    @Test fun native48kIsByteIdenticalAndOtherRatesKeepDurationStereoAndToneLevel() {
        for (rate in listOf(44100,48000,88200,96000,176400,192000)) {
            val source = wav(rate, 1000.0); val target = File.createTempFile("proof-aac-input", ".wav")
            try {
                ProofResample.to48k(source, target)
                val h = ProofWav.header(target)
                assertEquals(48000,h.rate); assertEquals(9600L,h.frames)
                if (rate == 48000) assertArrayEquals(source.readBytes(),target.readBytes())
                val pcm = ProofWav.decode(target.readBytes().drop(44).toByteArray(),(h.frames*h.frameBytes).toInt(),h.bits)
                val start=500; val end=9000
                val power=(start until end).sumOf { pcm[it*2].toDouble().pow(2) }/(end-start)
                assertEquals("$rate Hz tone level",0.0,10*log10(power/(0.2*0.2/2)),0.03)
                assertTrue("stereo remains opposed",(start until end).maxOf { abs(pcm[it*2]+pcm[it*2+1]) } < 0.0001)
                val error=(start until end).sumOf { (pcm[it*2]-0.2*sin(2*PI*1000*it/48000)).pow(2) }/(end-start)
                assertTrue("$rate Hz alignment error=$error",sqrt(error)<0.0001)
            } finally { source.delete();target.delete() }
        }
    }
    @Test fun highRateContentAbove48kNyquistIsRejectedInsteadOfAliased() {
        for(rate in listOf(88200,96000,176400,192000)) {
            val source=wav(rate,32000.0);val target=File.createTempFile("proof-alias", ".wav")
            try {
                ProofResample.to48k(source,target)
                val h=ProofWav.header(target);val pcm=ProofWav.decode(target.readBytes().drop(44).toByteArray(),(h.frames*h.frameBytes).toInt(),h.bits)
                val power=(500 until 9000).sumOf { pcm[it*2].toDouble().pow(2) }/8500
                assertTrue("$rate Hz rejection=${10*log10(power/(0.2*0.2/2))}",10*log10(power/(0.2*0.2/2)) < -65)
            } finally {source.delete();target.delete()}
        }
    }
}
