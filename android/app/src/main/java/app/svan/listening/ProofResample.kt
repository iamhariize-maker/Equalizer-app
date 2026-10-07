package app.svan.listening

import java.io.File
import java.io.RandomAccessFile
import java.util.Random
import kotlin.math.*

/** Finished-file AAC preparation. WAV originals and the live DSP are never resampled here. */
internal object ProofResample {
    private const val RATE = 48000
    private const val RADIUS = 96
    private const val CACHE_FRAMES = 8192

    fun to48k(source: File, target: File) {
        require(source.canonicalFile != target.canonicalFile)
        val h = ProofWav.header(source)
        require(h.frames > 0 && h.rate in listOf(44100,48000,88200,96000,176400,192000))
        if (h.rate == RATE) { source.copyTo(target, overwrite = true); return }
        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
        val divisor = gcd(h.rate, RATE)
        val phases = RATE / divisor
        val cutoff = 0.45 * minOf(1.0, RATE.toDouble() / h.rate)
        val kernels = Array(phases) { phase ->
            val fraction = phase.toDouble() / phases
            val weights = DoubleArray(RADIUS * 2 + 1) { tap ->
                val distance = tap - RADIUS - fraction
                val x = 2 * cutoff * distance
                val sinc = if (abs(x) < 1e-12) 1.0 else sin(PI * x) / (PI * x)
                val window = if (abs(distance) > RADIUS) 0.0 else
                    0.42 + 0.5 * cos(PI * distance / RADIUS) + 0.08 * cos(2 * PI * distance / RADIUS)
                2 * cutoff * sinc * window
            }
            val sum = weights.sum()
            FloatArray(weights.size) { (weights[it] / sum).toFloat() }
        }
        val frames = (h.frames * RATE + h.rate / 2) / h.rate
        RandomAccessFile(source, "r").use { input ->
            val raw = ByteArray(CACHE_FRAMES * h.frameBytes)
            val cache = FloatArray(CACHE_FRAMES * 2)
            var cacheStart = -1L; var cached = 0
            fun sample(frame: Long, channel: Int): Float {
                if (frame < 0 || frame >= h.frames) return 0f // centered, zero-padded edges: no added group delay
                if (cacheStart < 0 || frame !in cacheStart until cacheStart + cached) {
                    cacheStart = maxOf(0, frame - RADIUS * 2)
                    cached = minOf(CACHE_FRAMES.toLong(), h.frames - cacheStart).toInt()
                    input.seek(44 + cacheStart * h.frameBytes)
                    input.readFully(raw, 0, cached * h.frameBytes)
                    ProofWav.decodeInto(raw, cached * h.frameBytes, h.bits, cache)
                }
                return cache[(frame - cacheStart).toInt() * 2 + channel]
            }
            target.outputStream().buffered().use { output ->
                ProofWav.writeHeader(output, RATE, frames, 16)
                val samples = FloatArray(4096 * 2); val pcm = ByteArray(samples.size * 2)
                val random = Random()
                var written = 0L
                while (written < frames) {
                    val count = minOf(4096L, frames - written).toInt()
                    repeat(count) { i ->
                        val position = (written + i) * h.rate
                        val center = position / RATE
                        val kernel = kernels[(position % RATE / divisor).toInt()]
                        var left = 0.0; var right = 0.0
                        kernel.indices.forEach { tap ->
                            val frame = center + tap - RADIUS
                            left += sample(frame, 0) * kernel[tap]
                            right += sample(frame, 1) * kernel[tap]
                        }
                        samples[i * 2] = left.toFloat(); samples[i * 2 + 1] = right.toFloat()
                    }
                    val bytes = ProofWav.encode(samples, count * 2, pcm, 16, random)
                    output.write(pcm, 0, bytes); written += count
                }
            }
        }
    }
}
