package app.svan.listening

import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.Random
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Export-only PCM utilities. No call from the capture/audio thread. */
internal object ProofWav {
    data class Header(val rate: Int, val bits: Int, val frames: Long) {
        val frameBytes get() = bits / 8 * 2
    }

    fun header(file: File): Header = RandomAccessFile(file, "r").use { f ->
        require(f.length() >= 44)
        val b = ByteArray(44); f.readFully(b)
        val h = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        require(String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WAVE" && String(b, 36, 4) == "data")
        require(h.getShort(20).toInt() == 1 && h.getShort(22).toInt() == 2)
        val bits = h.getShort(34).toInt(); require(bits == 16 || bits == 24)
        val data = h.getInt(40).toLong() and 0xffffffffL
        require(data % (bits / 8 * 2) == 0L && f.length() == data + 44)
        Header(h.getInt(24), bits, data / (bits / 8 * 2))
    }

    fun writeHeader(out: OutputStream, rate: Int, frames: Long, bits: Int) {
        require(bits == 16 || bits == 24)
        val frameBytes = bits / 8 * 2
        val data = frames * frameBytes
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((36 + data).toInt()).put("WAVEfmt ".toByteArray())
        h.putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate * frameBytes)
        h.putShort(frameBytes.toShort()).putShort(bits.toShort()).put("data".toByteArray()).putInt(data.toInt())
        out.write(h.array())
    }

    fun patchHeader(file: File, rate: Int, frames: Long, bits: Int) {
        RandomAccessFile(file, "rw").use { f ->
            val h = java.io.ByteArrayOutputStream(44); writeHeader(h, rate, frames, bits)
            f.seek(0); f.write(h.toByteArray())
        }
    }

    /** TPDF in units of one LSB, support (-1, 1), mean 0 and variance 1/6. */
    fun tpdf(random: Random) = random.nextDouble() - random.nextDouble()

    fun encode(src: FloatArray, n: Int, out: ByteArray, bits: Int, dither: Random?): Int {
        val scale = (1 shl (bits - 1)).toDouble()
        var at = 0
        for (i in 0 until n) {
            val x = src[i].toDouble().coerceIn(-1.0, 1.0)
            val v = Math.round(x * scale + if (dither == null) 0.0 else tpdf(dither))
                .coerceIn(-scale.toLong(), scale.toLong() - 1).toInt()
            out[at++] = v.toByte(); out[at++] = (v shr 8).toByte()
            if (bits == 24) out[at++] = (v shr 16).toByte()
        }
        return at
    }

    fun decode(bytes: ByteArray, count: Int, bits: Int): FloatArray {
        val out = FloatArray(count / (bits / 8))
        decodeInto(bytes, count, bits, out)
        return out
    }

    fun decodeInto(bytes: ByteArray, count: Int, bits: Int, out: FloatArray): Int {
        var at = 0; var i = 0
        val scale = (1 shl (bits - 1)).toFloat()
        while (at < count) {
            var v = bytes[at++].toInt() and 255
            v = v or ((if (bits == 16) bytes[at++].toInt() else bytes[at++].toInt() and 255) shl 8)
            if (bits == 24) v = v or (bytes[at++].toInt() shl 16)
            out[i++] = v / scale
        }
        return i
    }

    /** Bounded memory when analysing or converting a ten-minute recording. */
    fun visit(file: File, start: Long = 0, end: Long = header(file).frames, chunk: (FloatArray, Int, Long) -> Unit) {
        val h = header(file); require(start in 0..end && end <= h.frames)
        RandomAccessFile(file, "r").use { f ->
            f.seek(44 + start * h.frameBytes)
            val bytes = ByteArray(4096 * h.frameBytes); val floats = FloatArray(8192)
            var frame = start
            while (frame < end) {
                val n = minOf(4096L, end - frame).toInt() * h.frameBytes
                f.readFully(bytes, 0, n)
                val count = decodeInto(bytes, n, h.bits, floats)
                chunk(floats, count, frame); frame += count / 2
            }
        }
    }

    fun read(file: File): FloatArray {
        val h = header(file); val out = FloatArray((h.frames * 2).toInt())
        visit(file) { b, n, frame -> System.arraycopy(b, 0, out, (frame * 2).toInt(), n) }
        return out
    }

    /** Copy payload bytes exactly, including existing dither; only the header changes. */
    fun tail(source: File, target: File, firstFrame: Long) {
        val h = header(source); require(firstFrame in 0..h.frames)
        RandomAccessFile(source, "r").use { input ->
            input.seek(44 + firstFrame * h.frameBytes)
            target.outputStream().buffered().use { out ->
                writeHeader(out, h.rate, h.frames - firstFrame, h.bits)
                val bytes = ByteArray(65536); var remaining = (h.frames - firstFrame) * h.frameBytes
                while (remaining > 0) {
                    val n = minOf(remaining, bytes.size.toLong()).toInt()
                    input.readFully(bytes, 0, n); out.write(bytes, 0, n); remaining -= n
                }
            }
        }
    }

    /** An alignment copy only. Payload after the cue remains byte-identical. */
    fun withCue(source: File, target: File) {
        val h = header(source); source.copyTo(target, overwrite = true)
        val cueFrames = h.rate * 51 / 100 // three bursts at 0, 250 and 500 ms, ending at 510 ms
        val frames = maxOf(h.frames, cueFrames.toLong())
        RandomAccessFile(target, "rw").use { f ->
            f.setLength(44 + frames * h.frameBytes)
            val bytes = ByteArray(cueFrames * h.frameBytes)
            f.seek(44); f.readFully(bytes)
            val samples = decode(bytes, bytes.size, h.bits)
            for (frame in 0 until cueFrames) {
                val click = ProofSyncCue.sample(frame.toLong(), h.rate)
                samples[frame * 2] += click; samples[frame * 2 + 1] += click
            }
            encode(samples, samples.size, bytes, h.bits, null)
            f.seek(44); f.write(bytes)
        }
        patchHeader(target, h.rate, frames, h.bits)
    }

    /** Same RMS ratio approach as BlindRenderer, with a peak cap to avoid export clipping. */
    fun matchingGain(dry: LevelMeter, wet: LevelMeter): Double {
        if (wet.sumSquares == 0.0) return 1.0
        if (dry.sumSquares == 0.0) return 0.0
        val ratio = sqrt((dry.sumSquares / dry.samples) / (wet.sumSquares / wet.samples))
        return minOf(ratio, if (wet.peak == 0.0) 1.0 else (1.0 - 1.0 / 32768) / wet.peak)
    }

    fun matched(source: File, target: File, segments: List<ProofRecorder.Segment>) {
        val h = header(source); val dither = if (h.bits == 16) Random() else null
        BufferedOutputStream(target.outputStream()).use { out ->
            writeHeader(out, h.rate, h.frames, h.bits)
            val bytes = ByteArray(8192 * h.bits / 8)
            segments.forEach { s ->
                visit(source, s.startFrame, s.endFrame) { b, n, _ ->
                    for (i in 0 until n) b[i] = (b[i] * s.matchedGain).toFloat()
                    out.write(bytes, 0, encode(b, n, bytes, h.bits, dither))
                }
            }
        }
    }
}

/** The same digital waveform is used for the speaker and the disposable alignment WAV. */
internal object ProofSyncCue {
    private val peak = 10.0.pow(-6.0 / 20)
    fun sample(frame: Long, rate: Int): Float {
        val burst = frame / (rate / 4)
        val local = frame % (rate / 4)
        if (burst !in 0..2 || local >= rate / 100) return 0f
        return (peak * sin(2 * PI * 2000 * local / rate)).toFloat()
    }
}
