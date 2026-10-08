package app.svan.listening

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
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
        ProofRecorder.start(dir, rate, mapOf("quality" to "test"), { mapOf("dspLatencyMs" to 1.1) }) { ProofRecorder.state.value = ProofRecorder.State.Done(it); result = it; done.countDown() }
        val block = 240; var at = 0 // divides the sample rate exactly
        repeat(rate * seconds / block) {
            val buf = tone(block, 1000.0, 0.25, at); at += block
            ProofRecorder.offerDry(buf, buf.size)
            process(buf)
            ProofRecorder.commitWet(buf, buf.size)
            if (it % 40 == 0) Thread.sleep(50) // the real loop is paced by the hardware
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
        ProofRecorder.start(dir, rate, emptyMap(), { null }) { ProofRecorder.state.value = ProofRecorder.State.Done(it); result = it; done.countDown() }
        val z = FloatArray(512)
        repeat(rate / 256) { ProofRecorder.offerDry(z, z.size); ProofRecorder.commitWet(z, z.size); if (it % 40 == 0) Thread.sleep(50) }
        ProofRecorder.stop(); assertTrue(done.await(20, TimeUnit.SECONDS))
        assertNotNull(result); assertTrue(result!!.bands.isEmpty())
    }

    @Test fun eachSettingChangeIsMeasuredInItsOwnSegment() {
        val dir = File.createTempFile("proof", "").apply { delete() }
        val done = CountDownLatch(1); var result: ProofRecorder.Result? = null
        ProofRecorder.start(dir, rate, mapOf("gain" to "flat"), { null }) { ProofRecorder.state.value = ProofRecorder.State.Done(it); result = it; done.countDown() }
        val block = 240; var at = 0
        // 2 s flat, then +6 dB for 2 s, then −6 dB for 2 s.
        for ((label, gain) in listOf("flat" to 1f, "Gain: 0 → +6 dB" to 2f, "Gain: +6 → −6 dB" to 0.5f)) {
            if (label != "flat") ProofRecorder.mark(label, mapOf("gain" to label))
            repeat(rate * 2 / block) { i ->
                val buf = tone(block, 1000.0, 0.25, at); at += block
                ProofRecorder.offerDry(buf, buf.size)
                for (k in buf.indices) buf[k] *= gain
                ProofRecorder.commitWet(buf, buf.size)
                if (i % 40 == 0) Thread.sleep(50)
            }
        }
        ProofRecorder.stop(); assertTrue(done.await(20, TimeUnit.SECONDS))
        val r = result!!
        assertEquals(3, r.segments.size)
        assertEquals(6.0, r.seconds, 1e-12) // replaces the removed video tap frame-count check
        assertEquals(0L, r.droppedFrames)
        fun delta(i: Int) = r.segments[i].bands.minByOrNull { abs(it.hz - 1000.0) }!!.deltaDb
        assertEquals(0.0, delta(0), 0.3); assertEquals(6.02, delta(1), 0.3); assertEquals(-6.02, delta(2), 0.3)
        assertEquals(2.0, r.segments[1].startSeconds, 0.1)
        assertEquals(3, r.report.getJSONArray("segments").length())
    }

    private fun session(options: ProofRecorder.Options = ProofRecorder.Options(), feed: () -> Unit): ProofRecorder.Result {
        val dir = File.createTempFile("proof-sync", "").apply { delete() }
        val done = CountDownLatch(1)
        var result: ProofRecorder.Result? = null
        ProofRecorder.start(dir, rate, emptyMap(), { null }, options) { ProofRecorder.state.value = ProofRecorder.State.Done(it); result = it; done.countDown() }
        try { feed() } finally { ProofRecorder.stop() }
        assertTrue("Recorder failed: ${ProofRecorder.state.value}", done.await(20, TimeUnit.SECONDS))
        return result!!
    }

    private fun feed(frames: Int, gain: Float = 1f, silent: Boolean = false) {
        var at = 0
        while (at < frames) {
            val n = minOf(240, frames - at)
            val b = if (silent) FloatArray(n * 2) else tone(n, 1000.0, 0.2, at)
            ProofRecorder.offerDry(b, b.size)
            for (i in b.indices) b[i] *= gain
            ProofRecorder.commitWet(b, b.size)
            at += n
            if (at % 9600 == 0) Thread.sleep(50)
        }
    }

    @Test fun syncPositionsAndClockUseCommittedFramesEvenWithPendingDry() {
        val before = System.currentTimeMillis()
        val r = session {
            feed(12345)
            val b = tone(240, 1000.0, 0.2)
            ProofRecorder.offerDry(b, b.size)
            val sync = ProofRecorder.markSync()!!
            assertEquals(12345L, sync.frame)
            assertEquals(12345L, ProofRecorder.recordedFrames)
            assertEquals("0:00.257", ProofRecorder.clockText())
            assertTrue(sync.epochMs >= before)
            ProofRecorder.commitWet(b, b.size)
            assertEquals(12585L, ProofRecorder.markSync()!!.frame)
        }
        assertEquals(12345L, r.report.getJSONArray("syncFrames").getLong(0))
        assertEquals(12585L, r.report.getJSONArray("syncFrames").getLong(1))
        assertEquals(12345.0 / rate, r.report.getJSONArray("syncSeconds").getDouble(0), 1e-12)
        assertTrue(r.report.getLong("clockStartEpochMs") >= before)
        assertTrue(r.report.getLong("recordedAtEpochMs") >= before)
    }

    @Test fun syncWavsAreExactTailsWithValidHeadersForBothBitDepths() {
        for (bits in listOf(16, 24)) {
            val r = session(ProofRecorder.Options(wavBits = bits)) {
                feed(12345); ProofRecorder.markSync(); feed(rate)
            }
            for ((full, tail) in listOf(r.dryWav to r.dryFromSyncWav!!, r.processedWav to r.processedFromSyncWav!!)) {
                val all = full.readBytes(); val cut = tail.readBytes()
                val bytesPerFrame = bits / 8 * 2
                assertTrue(all.copyOfRange(44 + 12345 * bytesPerFrame, all.size).contentEquals(cut.copyOfRange(44, cut.size)))
                val h = ByteBuffer.wrap(cut).order(ByteOrder.LITTLE_ENDIAN)
                assertEquals(cut.size - 8, h.getInt(4)); assertEquals(cut.size - 44, h.getInt(40))
                assertEquals(bits, h.getShort(34).toInt()); assertEquals(bytesPerFrame, h.getShort(32).toInt())
                assertEquals(rate * bytesPerFrame, h.getInt(28))
            }
        }
    }

    @Test fun firstSignalIsDetectedAtOnePointFiveSecondsInEitherStream() {
        val r = session { feed(rate * 3 / 2, silent = true); feed(rate) }
        assertEquals(1.5, r.report.getDouble("firstSignalSeconds"), 1.0 / rate)
        val silent = session { feed(rate, silent = true) }
        assertTrue(silent.report.isNull("firstSignalSeconds"))
        val wetOnly = session {
            feed(rate * 3 / 2, silent = true)
            val dry = FloatArray(480); val wet = tone(240, 1000.0, 0.2)
            ProofRecorder.offerDry(dry, dry.size); ProofRecorder.commitWet(wet, wet.size)
        }
        assertEquals(1.5, wetOnly.report.getDouble("firstSignalSeconds"), 1.0 / rate)
    }

    @Test fun pcmConversionsRoundTripAndTpdfHasZeroMeanAndExpectedVariance() {
        val src = floatArrayOf(-1f, -0.7f, 0f, 0.3f, 1f, -2f, 2f)
        for (bits in listOf(16, 24)) {
            val out = ByteArray(src.size * bits / 8)
            ProofWav.encode(src, src.size, out, bits, null)
            val decoded = ProofWav.decode(out, out.size, bits)
            src.indices.forEach { assertEquals(src[it].coerceIn(-1f, 1f).toDouble(), decoded[it].toDouble(), 2.0 / (1 shl (bits - 1))) }
        }
        val random = java.util.Random(42)
        val noise = DoubleArray(200000) { ProofWav.tpdf(random) }
        assertEquals(0.0, noise.average(), 0.003)
        assertEquals(1.0 / 6, noise.map { it * it }.average(), 0.003)
    }

    @Test fun eachMatchedSegmentReachesDryRmsWithinPointZeroFiveDb() {
        val r = session(ProofRecorder.Options(matchLevel = true)) {
            feed(rate, 2f)
            ProofRecorder.mark("Quieter", emptyMap()); feed(rate, 0.5f)
        }
        val dry = ProofWav.read(r.dryWav); val matched = ProofWav.read(r.matchedWav!!)
        for (segment in 0..1) {
            val d = LevelMeter(); val m = LevelMeter()
            for (i in segment * rate * 2 until (segment + 1) * rate * 2) { d.add(dry[i]); m.add(matched[i]) }
            assertEquals(d.rmsDbfs, m.rmsDbfs, 0.05)
            assertEquals(if (segment == 0) -6.0206 else 6.0206, r.report.getJSONArray("segments").getJSONObject(segment).getDouble("matchedGainDb"), 0.05)
        }
    }

    @Test fun syncCueExistsOnlyInAlignmentCopyAndHasMeasuredBurstShape() {
        val r = session { ProofRecorder.markSync(); feed(rate, silent = true) }
        assertTrue(ProofWav.read(r.dryWav).all { it == 0f })
        assertTrue(ProofWav.read(r.processedWav).all { it == 0f })
        assertTrue(ProofWav.read(r.processedFromSyncWav!!).all { it == 0f })
        val cue = ProofWav.read(r.syncCueWav!!)
        assertEquals(-6.0, LevelMeter.toDb(cue.maxOf { abs(it) }.toDouble()), 0.01)
        for (frame in 0 until rate) {
            val inBurst = (0..2).any { frame in it * rate / 4 until it * rate / 4 + rate / 100 }
            if (!inBurst) assertEquals(0f, cue[frame * 2], 0f)
            else assertEquals(ProofSyncCue.sample(frame.toLong(), rate).toDouble(), cue[frame * 2].toDouble(), 2e-7)
        }
        val noSync = session { feed(rate) }
        assertTrue(noSync.processedFromSyncWav == null && noSync.syncCueWav == null && noSync.matchedWav == null)
    }

    @Test fun manualBoundaryAtDrainedPositionAndAtEndHasNoEmptySegment() {
        val r = session {
            ProofRecorder.mark("First", emptyMap())
            feed(rate); Thread.sleep(100)
            ProofRecorder.mark("Second", emptyMap()); feed(rate)
            ProofRecorder.mark("After end", emptyMap())
        }
        assertEquals(listOf("First", "Second"), r.segments.map { it.label })
        assertEquals(1.0, r.segments[1].startSeconds, 1e-12)
    }

    @Test fun countdownSyncIsFrameZeroAndSilent16BitSegmentsHaveNoSignalSpectrum() {
        val r = session(ProofRecorder.Options(wavBits = 16, startWithSync = true)) { feed(rate, silent = true) }
        assertEquals(0L, r.report.getJSONArray("syncFrames").getLong(0))
        assertTrue(r.report.isNull("firstSignalSeconds"))
        assertTrue(r.segments.single().bands.isEmpty())
    }

    @Test fun matchingSilenceAndPeakLimitedTargetsAreExplicit() {
        val dry = LevelMeter(); val wet = LevelMeter()
        repeat(1000) { dry.add(0f); wet.add(0.1f) }
        assertEquals(0.0, ProofWav.matchingGain(dry, wet), 0.0)
        val highDry = LevelMeter(); val highWet = LevelMeter()
        repeat(1000) { highDry.add(0.9f); highWet.add(if (it == 0) 1f else 0.1f) }
        val gain = ProofWav.matchingGain(highDry, highWet)
        assertTrue(gain * highWet.peak < 1.0)
        assertTrue(highWet.rmsDbfs + LevelMeter.toDb(gain) < highDry.rmsDbfs)
    }

    @Test fun segmentLimitIsReportedWithoutLimitingSyncs() {
        val r = session {
            feed(240)
            repeat(ProofRecorder.MAX_SEGMENTS + 2) { index ->
                ProofRecorder.mark("Change $index", emptyMap())
                ProofRecorder.markSync()
                feed(240)
            }
        }
        assertEquals(ProofRecorder.MAX_SEGMENTS, r.segments.size)
        assertTrue(r.report.getBoolean("segmentLimitReached"))
        assertEquals(ProofRecorder.MAX_SEGMENTS + 2, r.report.getJSONArray("syncFrames").length())
    }

    @Test fun settingChangesGetReadableLabels() {
        val a = mapOf("qualityMode" to "Efficient", "preampDb" to 0.0, "eqEnabled" to true, "device" to "x")
        val b = mapOf("qualityMode" to "Audiophile", "preampDb" to -3.5, "eqEnabled" to true, "device" to "y")
        assertEquals("Quality: Efficient → Audiophile · Preamp dB: 0 → -3.5", SettingsDiff.describe(a, b))
        assertEquals("EQ curve changed", SettingsDiff.describe(mapOf("eqCurve" to "a"), mapOf("eqCurve" to "b")))
        assertEquals("EQ: on → off", SettingsDiff.describe(a, a + mapOf("eqEnabled" to false)))
        assertEquals("Settings changed", SettingsDiff.describe(a, a + mapOf("device" to "z")))
    }
    @Test fun abPositionsUseCommittedFramesAndExportsLeavePlainWavsUntouched() {
        val r = session(ProofRecorder.Options(abMatchLevel = false)) {
            feed(12345, 2f)
            val b = tone(240, 1000.0, 0.2)
            ProofRecorder.offerDry(b, b.size)
            assertEquals(12345L, ProofRecorder.markAb(false)!!.frame)
            assertEquals("Before", ProofRecorder.currentLabel.value)
            ProofRecorder.markSync()
            ProofRecorder.commitWet(b, b.size)
            assertEquals(12585L, ProofRecorder.markAb(true)!!.frame)
            feed(rate, 2f)
        }
        val switches = r.report.getJSONArray("abSwitches")
        assertEquals(12345L, switches.getJSONObject(0).getLong("frame"))
        assertEquals("Before", switches.getJSONObject(0).getString("choice"))
        assertEquals(12585L, switches.getJSONObject(1).getLong("frame"))
        assertEquals(12585.0 / rate, switches.getJSONObject(1).getDouble("seconds"), 1e-12)
        assertEquals(12585L + rate - 12345, ProofWav.header(r.abTimelineWav!!).frames)
        assertArrayEquals(r.abTimelineWav!!.readBytes(), r.abTimelineFromSyncWav!!.readBytes())
        val tail = ProofWav.read(r.abTimelineWav!!)
        val wet = ProofWav.read(r.processedWav)
        for (frame in 12585 + rate / 200 until 12585 + rate) {
            assertEquals(wet[frame * 2], tail[(frame - 12345) * 2], 0f)
        }
        assertEquals(6.0206, r.processed.rmsDbfs - r.dry.rmsDbfs, 0.05)
        assertTrue(r.segments.any { it.label == "Before" } && r.segments.any { it.label == "After" })
    }

    @Test fun abMatchingDefaultsOnAndSyncExportStartsBeforeFirstPress() {
        val r = session(ProofRecorder.Options(startWithSync = true)) {
            feed(rate, 2f)
            ProofRecorder.markAb(true)
            feed(rate, 2f)
        }
        val full = ProofWav.read(r.abTimelineFromSyncWav!!)
        val timeline = ProofWav.read(r.abTimelineWav!!)
        assertEquals(rate.toLong(), ProofWav.header(r.abTimelineWav!!).frames)
        assertEquals((rate * 2).toLong(), ProofWav.header(r.abTimelineFromSyncWav!!).frames)
        val before = LevelMeter(); val after = LevelMeter()
        for (frame in rate / 4 until rate * 3 / 4) {
            before.add(full[frame * 2]); after.add(timeline[frame * 2])
        }
        assertEquals(before.rmsDbfs, after.rmsDbfs, 0.05)
        assertTrue(r.report.getJSONObject("abTimeline").getBoolean("matchLevel"))
        val noAb = session { feed(240) }
        assertTrue(noAb.abTimelineWav == null && noAb.abTimelineFromSyncWav == null)
    }

    @Test fun highRateFrameClockSyncTailAndHeaderKeepTheActualCaptureRate() {
        val hz = 96000
        val dir = File.createTempFile("proof-high-rate", "").apply { delete() }
        val done = CountDownLatch(1); var result: ProofRecorder.Result? = null
        ProofRecorder.start(dir, hz, emptyMap(), { null }, ProofRecorder.Options(wavBits = 16)) {
            result = it; ProofRecorder.state.value = ProofRecorder.State.Done(it); done.countDown()
        }
        try {
            repeat(400) { i ->
                val b = FloatArray(480) { n -> (0.2 * sin(2 * PI * 1000 * (i * 240 + n / 2) / hz)).toFloat() }
                ProofRecorder.offerDry(b,b.size); ProofRecorder.commitWet(b,b.size)
                if (i == 199) {
                    assertEquals(48000L,ProofRecorder.markSync()!!.frame)
                    assertEquals("0:00.500",ProofRecorder.clockText())
                }
                if (i % 40 == 0) Thread.sleep(50)
            }
        } finally { ProofRecorder.stop() }
        assertTrue(done.await(20,TimeUnit.SECONDS))
        val r = result!!
        assertEquals(1.0,r.seconds,1e-12)
        assertEquals(hz,ProofWav.header(r.processedWav).rate)
        assertEquals(48000L,ProofWav.header(r.processedFromSyncWav!!).frames)
        assertEquals(0.5,r.report.getJSONArray("syncSeconds").getDouble(0),1e-12)
        assertArrayEquals(r.processedWav.readBytes().copyOfRange(44 + 48000 * 4, r.processedWav.length().toInt()),
            r.processedFromSyncWav!!.readBytes().drop(44).toByteArray())
    }
}
