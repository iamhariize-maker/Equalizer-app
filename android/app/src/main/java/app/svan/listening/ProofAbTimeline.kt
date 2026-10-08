package app.svan.listening

import java.io.File
import java.io.RandomAccessFile
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Offline export only; the capture thread and live playback never call this. */
internal object ProofAbTimeline {
    /** Absolute saved-frame ranges and target After gains; 5ms gain ramps may
     * straddle their starts. Clipping counts transformed samples that required
     * saturation, including any added 16-bit export dither. */
    data class RangeGain(val startFrame: Long, val endFrame: Long, val gainLinear: Double,
                         val limitedByPeak: Boolean, val clippedSamples: Long = 0)

    fun render(dry: File, wet: File, target: File, switches: List<ProofRecorder.AbSwitch>,
               segments: List<ProofRecorder.Segment>, firstFrame: Long, matchLevel: Boolean): List<RangeGain> {
        val h = ProofWav.header(dry)
        require(h == ProofWav.header(wet)) { "Before and After WAV formats/timelines differ" }
        require(h.rate > 0 && firstFrame in 0..h.frames)
        require(target.canonicalFile != dry.canonicalFile && target.canonicalFile != wet.canonicalFile)
        require(switches.all { it.frame in 0..h.frames })
        require(segments.all { it.startFrame in 0..it.endFrame && it.endFrame <= h.frames })

        // Stable order gives the final press at a duplicate frame authority.
        val marks = mutableListOf<ProofRecorder.AbSwitch>()
        switches.sortedBy { it.frame }.forEach { mark ->
            if (marks.lastOrNull()?.frame == mark.frame) marks[marks.lastIndex] = mark
            else marks.add(mark)
        }
        val boundaries = sortedSetOf(0L, firstFrame, h.frames)
        marks.forEach { boundaries.add(it.frame) }
        segments.forEach { boundaries.add(it.startFrame); boundaries.add(it.endFrame) }
        val ranges = boundaries.zipWithNext().map { (start, end) ->
            if (!matchLevel) RangeGain(start, end, 1.0, false)
            else {
                val before = LevelMeter(); val after = LevelMeter()
                ProofWav.visit(dry, start, end) { pcm, count, _ -> for (i in 0 until count) before.add(pcm[i]) }
                ProofWav.visit(wet, start, end) { pcm, count, _ -> for (i in 0 until count) after.add(pcm[i]) }
                val requested = when {
                    after.sumSquares == 0.0 -> 1.0
                    before.sumSquares == 0.0 -> 0.0
                    else -> sqrt(before.sumSquares / after.sumSquares)
                }
                // The 16-bit transformed path adds TPDF. Reserve its extra
                // LSB before rounding rather than randomly clipping a peak.
                val reserve = if (h.bits == 16) 2.0 else 1.0
                val peakCap = if (after.peak == 0.0) 1.0 else (1.0-reserve/32768)/after.peak
                val gain = minOf(ProofWav.matchingGain(before, after), peakCap)
                RangeGain(start, end, gain, requested > peakCap)
            }
        }
        val clipped = LongArray(ranges.size)
        var rangeIndex = ranges.indexOfFirst { firstFrame < it.endFrame }.let { if (it < 0) ranges.size else it }
        var markIndex = marks.indexOfFirst { it.frame > firstFrame }.let { if (it < 0) marks.size else it }
        val halfPi = PI / 2
        var angle = if (marks.lastOrNull { it.frame <= firstFrame }?.isAfter == true) halfPi else 0.0
        var angleTarget = angle; var angleStep = 0.0; var angleRemaining = 0
        var gain = ranges.getOrNull(rangeIndex)?.gainLinear ?: 1.0
        var gainTarget = gain; var gainStep = 0.0; var gainRemaining = 0
        val fadeFrames = maxOf(1, (h.rate * .005).roundToInt())
        val scale = (1 shl (h.bits - 1)).toDouble()
        val dither = if (h.bits == 16) Random() else null // transformed exports only; raw copy paths preserve saved PCM

        RandomAccessFile(dry, "r").use { before ->
            RandomAccessFile(wet, "r").use { after ->
                before.seek(44 + firstFrame * h.frameBytes)
                after.seek(44 + firstFrame * h.frameBytes)
                target.outputStream().buffered().use { output ->
                    ProofWav.writeHeader(output, h.rate, h.frames - firstFrame, h.bits)
                    val dryBytes = ByteArray(4096 * h.frameBytes); val wetBytes = ByteArray(dryBytes.size)
                    val outBytes = ByteArray(dryBytes.size)
                    val dryPcm = FloatArray(8192); val wetPcm = FloatArray(8192)
                    val encodedPair = ByteArray(h.frameBytes)
                    var base = firstFrame
                    while (base < h.frames) {
                        val count = minOf(4096L, h.frames - base).toInt()
                        val bytes = count * h.frameBytes
                        before.readFully(dryBytes, 0, bytes); after.readFully(wetBytes, 0, bytes)
                        ProofWav.decodeInto(dryBytes, bytes, h.bits, dryPcm)
                        ProofWav.decodeInto(wetBytes, bytes, h.bits, wetPcm)
                        for (i in 0 until count) {
                            val frame = base + i
                            while (frame >= ranges[rangeIndex].endFrame) rangeIndex++
                            val nextGain = ranges[rangeIndex].gainLinear
                            if (nextGain != gainTarget) {
                                gainTarget = nextGain; gainRemaining = fadeFrames
                                gainStep = (gainTarget - gain) / fadeFrames
                            }
                            if (markIndex < marks.size && marks[markIndex].frame == frame) {
                                val nextAngle = if (marks[markIndex++].isAfter) halfPi else 0.0
                                if (nextAngle != angleTarget) {
                                    angleTarget = nextAngle; angleRemaining = fadeFrames
                                    angleStep = (angleTarget - angle) / fadeFrames
                                }
                            }
                            val offset = i * h.frameBytes
                            if (angle == 0.0 || (angle == halfPi && gain == 1.0)) {
                                val selected = if (angle == 0.0) dryBytes else wetBytes
                                System.arraycopy(selected, offset, outBytes, offset, h.frameBytes)
                            } else {
                                val beforeWeight = if (angle == halfPi) 0.0 else cos(angle)
                                val afterWeight = if (angle == halfPi) gain else sin(angle) * gain
                                for (channel in 0..1) {
                                    val sample = beforeWeight * dryPcm[i * 2 + channel] + afterWeight * wetPcm[i * 2 + channel]
                                    val quantized = Math.round(sample * scale + if (dither == null) 0.0 else ProofWav.tpdf(dither))
                                    if (quantized < -scale.toLong() || quantized >= scale.toLong()) clipped[rangeIndex]++
                                    val value = quantized.coerceIn(-scale.toLong(), scale.toLong()-1).toInt()
                                    val at = channel * (h.bits / 8)
                                    encodedPair[at] = value.toByte(); encodedPair[at+1] = (value shr 8).toByte()
                                    if (h.bits == 24) encodedPair[at+2] = (value shr 16).toByte()
                                }
                                System.arraycopy(encodedPair, 0, outBytes, offset, h.frameBytes)
                            }
                            if (angleRemaining > 0) {
                                angle += angleStep
                                if (--angleRemaining == 0) angle = angleTarget
                            }
                            if (gainRemaining > 0) {
                                gain += gainStep
                                if (--gainRemaining == 0) gain = gainTarget
                            }
                        }
                        output.write(outBytes, 0, bytes)
                        base += count
                    }
                }
            }
        }
        return ranges.mapIndexed { index, range -> range.copy(clippedSamples = clipped[index]) }
    }
}
