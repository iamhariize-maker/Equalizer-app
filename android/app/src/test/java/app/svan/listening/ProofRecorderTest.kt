package app.svan.listening

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProofRecorderTest {
    private val rate = 48000

    private fun tone(frames: Int, hz: Double, amp: Double, from: Int = 0) = FloatArray(frames * 2).also {
        for (i in 0 until frames) { val v = (amp * sin(2 * PI * hz * (from + i) / rate)).toFloat(); it[i * 2] = v; it[i * 2 + 1] = v }
    }

    private fun record(seconds: Int, process: (FloatArray) -> Unit): ProofRecorder.Result {
        val dir = File.createTempFile("proof", "").apply { delete() }
        val done = CountDownLatch(1); var result: ProofRecorder.Result? = null
        ProofRecorder.start(dir, rate, mapOf("quality" to "test"), { mapOf("dspLatencyMs" to 1.1) }) { result = it; done.countDown() }
        val block = 240; var at = 0 // divides the sample rate exactly
        repeat(rate * seconds / block) {
            val buf = tone(block, 1000.0, 0.25, at); at += block
            ProofRecorder.offerDry(buf, buf.size)
            process(buf)
            ProofRecorder.commitWet(buf, buf.size)
            if (it % 40 == 0) Thread.sleep(2) // the real loop is paced by the hardware
        }
        ProofRecorder.stop()
        assertTrue(done.await(20, TimeUnit.SECONDS))
        return result!!
    }

    @Test fun measuresAKnownSixDecibelBoostAndKeepsFilesAligned() {
        val r = record(3) { b -> for (i in b.indices) b[i] *= 2f }
        assertEquals(0L, r.droppedFrames)
        assertEquals(3.0, r.seconds, 0.01)
        assertEquals(-12.04, r.dry.peakDbfs, 0.05)
        assertEquals(-6.02, r.processed.peakDbfs, 0.05)
        assertEquals(6.02, r.processed.rmsDbfs - r.dry.rmsDbfs, 0.05)
        // The 1 kHz band shows +6 dB; bands far away carry no signal in either stream.
        val b = r.bands.minByOrNull { abs(it.hz - 1000.0) }!!
        assertEquals(6.02, b.deltaDb, 0.3)
        assertEquals(r.dryWav.length(), r.processedWav.length())
        assertEquals(44L + 3L * rate * 6, r.dryWav.length())
        assertTrue(r.reportJson.readText().contains("\"deltaDb\""))
    }

    @Test fun wavHeaderAndSamplesDecode() {
        val r = record(1) { }
        val b = ByteBuffer.wrap(r.processedWav.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(ByteArray(4).also { b.get(it) }))
        assertEquals(36 + rate * 6, b.getInt(4))
        assertEquals(1, b.getShort(20).toInt()); assertEquals(2, b.getShort(22).toInt())
        assertEquals(rate, b.getInt(24)); assertEquals(24, b.getShort(34).toInt())
        assertEquals(rate * 6, b.getInt(40))
        // First left sample at i=0 is sin(0)=0; sample 12 (a quarter period of 1 kHz at 48 kHz) is the peak.
        fun s24(frame: Int): Double {
            val o = 44 + frame * 6
            val v = (b.get(o).toInt() and 255) or ((b.get(o + 1).toInt() and 255) shl 8) or (b.get(o + 2).toInt() shl 16)
            return v / 8388608.0
        }
        assertEquals(0.0, s24(0), 1e-6); assertEquals(0.25, s24(12), 1e-5)
    }

    @Test fun overshootIsCountedAndClampedNotWrapped() {
        val r = record(1) { b -> for (i in b.indices) b[i] *= 8f }
        assertTrue(r.processed.overs > 0)
        val b = ByteBuffer.wrap(r.processedWav.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val o = 44 + 12 * 6 // 0.25*8 = 2.0 → clamps to full scale, must stay positive
        val v = (b.get(o).toInt() and 255) or ((b.get(o + 1).toInt() and 255) shl 8) or (b.get(o + 2).toInt() shl 16)
        assertEquals(8388607, v)
    }

    @Test fun silenceProducesNoSpectrumInsteadOfAFlatLie() {
        val dir = File.createTempFile("proof", "").apply { delete() }
        val done = CountDownLatch(1); var result: ProofRecorder.Result? = null
        ProofRecorder.start(dir, rate, emptyMap(), { null }) { result = it; done.countDown() }
        val z = FloatArray(512)
        repeat(rate / 256) { ProofRecorder.offerDry(z, z.size); ProofRecorder.commitWet(z, z.size); if (it % 40 == 0) Thread.sleep(2) }
        ProofRecorder.stop(); assertTrue(done.await(20, TimeUnit.SECONDS))
        assertNotNull(result); assertTrue(result!!.bands.isEmpty())
    }

    @Test fun eachSettingChangeIsMeasuredInItsOwnSegment() {
        val dir = File.createTempFile("proof", "").apply { delete() }
        val done = CountDownLatch(1); var result: ProofRecorder.Result? = null
        val tapped = java.util.concurrent.atomic.AtomicLong()
        val tap = object : ProofRecorder.WetTap {
            override fun onChunk(chunk: FloatArray, floats: Int, firstBlockNanos: Long, startFrame: Long) { tapped.addAndGet(floats / 2L) }
            override fun onEnd() {}
        }
        ProofRecorder.start(dir, rate, mapOf("gain" to "flat"), { null }, tap) { result = it; done.countDown() }
        val block = 240; var at = 0
        // 2 s flat, then +6 dB for 2 s, then −6 dB for 2 s.
        for ((label, gain) in listOf("flat" to 1f, "Gain: 0 → +6 dB" to 2f, "Gain: +6 → −6 dB" to 0.5f)) {
            if (label != "flat") ProofRecorder.mark(label, mapOf("gain" to label))
            repeat(rate * 2 / block) { i ->
                val buf = tone(block, 1000.0, 0.25, at); at += block
                ProofRecorder.offerDry(buf, buf.size)
                for (k in buf.indices) buf[k] *= gain
                ProofRecorder.commitWet(buf, buf.size)
                if (i % 40 == 0) Thread.sleep(2)
            }
        }
        ProofRecorder.stop(); assertTrue(done.await(20, TimeUnit.SECONDS))
        val r = result!!
        assertEquals(3, r.segments.size)
        assertEquals(rate * 6L, tapped.get())
        fun delta(i: Int) = r.segments[i].bands.minByOrNull { abs(it.hz - 1000.0) }!!.deltaDb
        assertEquals(0.0, delta(0), 0.3); assertEquals(6.02, delta(1), 0.3); assertEquals(-6.02, delta(2), 0.3)
        assertEquals(2.0, r.segments[1].startSeconds, 0.1)
        assertEquals(3, r.report.getJSONArray("segments").length())
    }

    @Test fun settingChangesGetReadableLabels() {
        val a = mapOf("qualityMode" to "Efficient", "preampDb" to 0.0, "eqEnabled" to true, "device" to "x")
        val b = mapOf("qualityMode" to "Audiophile", "preampDb" to -3.5, "eqEnabled" to true, "device" to "y")
        assertEquals("Quality: Efficient → Audiophile · Preamp dB: 0 → -3.5", SettingsDiff.describe(a, b))
        assertEquals("EQ: on → off", SettingsDiff.describe(a, a + mapOf("eqEnabled" to false)))
        assertEquals("Settings changed", SettingsDiff.describe(a, a + mapOf("device" to "z")))
    }
}
