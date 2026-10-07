package app.svan.listening

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile
import java.util.Random

/** Finished-file conversion only; never used by offerDry/commitWet. */
internal object ProofAac {
    fun encode(source: File, target: File) {
        val h = ProofWav.header(source)
        require(h.rate == 48000 && h.frames > 0) { "AAC export needs nonempty 48 kHz audio" }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var muxer: MediaMuxer? = null
        var codecStarted = false; var muxStarted = false; var complete = false
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 256000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start(); codecStarted = true
            val mux = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4); muxer = mux
            var track = -1; var eos = false
            val info = MediaCodec.BufferInfo()
            fun drain() {
                while (!eos) {
                    val index = codec.dequeueOutputBuffer(info, 10000)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxStarted)
                            track = mux.addTrack(codec.outputFormat); mux.start(); muxStarted = true
                        }
                        index >= 0 -> {
                            val out = codec.getOutputBuffer(index)!!
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                check(muxStarted)
                                out.position(info.offset); out.limit(info.offset + info.size)
                                mux.writeSampleData(track, out, info)
                            }
                            eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                }
            }
            RandomAccessFile(source, "r").use { input ->
                input.seek(44)
                val raw = ByteArray(4096 * h.frameBytes); val floats = FloatArray(8192); val pcm = ByteArray(16384)
                val random = if (h.bits == 24) Random() else null
                var frame = 0L; var queuedEnd = false
                var lastProgress = System.nanoTime()
                while (!eos) {
                    if (!queuedEnd) {
                        val index = codec.dequeueInputBuffer(10000)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!; buffer.clear()
                            val count = minOf(4096L, h.frames - frame, buffer.remaining().toLong() / 4).toInt()
                            if (count == 0) {
                                codec.queueInputBuffer(index, 0, 0, frame * 1_000_000 / h.rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                queuedEnd = true
                            } else {
                                input.readFully(raw, 0, count * h.frameBytes)
                                ProofWav.decodeInto(raw, count * h.frameBytes, h.bits, floats)
                                val n = ProofWav.encode(floats, count * 2, pcm, 16, random)
                                buffer.put(pcm, 0, n)
                                codec.queueInputBuffer(index, 0, n, frame * 1_000_000 / h.rate, 0)
                                frame += count
                            }
                            lastProgress = System.nanoTime()
                        }
                    }
                    drain()
                    check(System.nanoTime() - lastProgress < 10_000_000_000L) { "AAC encoder did not finish" }
                }
            }
            check(muxStarted); mux.stop(); muxStarted = false
            complete = true
        } finally {
            if (codecStarted) runCatching { codec.stop() }
            codec.release()
            if (muxStarted) runCatching { muxer?.stop() }
            muxer?.release()
            if (!complete) target.delete()
        }
    }
}
