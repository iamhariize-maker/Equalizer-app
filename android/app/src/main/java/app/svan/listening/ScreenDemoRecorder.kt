package app.svan.listening

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.view.WindowManager
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * One MP4 of the screen plus Svan's own processed output.
 *
 * Android's screen recorders cannot hear the audiophile engine, so the audio track here is
 * the exact stream Svan sends to the AudioTrack (fed by [ProofRecorder] through [WetTap]),
 * and the video comes from the engine's existing MediaProjection. Audio is timed from the
 * moment its first block was committed; video from the first frame the encoder delivered.
 * Expect roughly ±100 ms start alignment, before any output-device (Bluetooth) delay.
 *
 * Android 14+ allows one VirtualDisplay per MediaProjection, so a screen recording can be made
 * once per audiophile-engine start ([app.svan.CaptureService.screenUsed]).
 */
class ScreenDemoRecorder(context: Context, projection: MediaProjection, private val rate: Int, val file: File) : ProofRecorder.WetTap {
    private val lock = Object()
    private val t0Us = System.nanoTime() / 1000
    private var muxer: MediaMuxer? = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var videoOffsetUs: Long? = null
    private var lastVideoUs = -1L
    private val pendingAudio = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>()
    private val video: MediaCodec
    private val audio: MediaCodec
    private var display: VirtualDisplay?
    private val videoThread: Thread
    private val pcm = ByteArray(8192 * 2)
    @Volatile var failure: String? = null; private set

    /** True when both tracks were written and the file can be played. */
    val ok: Boolean get() = started && failure == null

    init {
        val (w, h, dpi) = screenSize(context)
        val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 250_000) // a still screen must still advance the timeline
        }
        video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        video.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = video.createInputSurface()
        video.start()
        val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 16)
        }
        audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audio.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audio.start()
        display = try {
            projection.createVirtualDisplay("svan-demo", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
        } catch (e: Exception) { release(); throw e }
        videoThread = Thread({ drainVideo() }, "svan-demo-video").apply { start() }
    }

    // ---- WetTap (writer thread) ---------------------------------------------------------

    override fun onChunk(chunk: FloatArray, floats: Int, firstBlockNanos: Long, startFrame: Long) {
        if (failure != null) return
        var done = 0 // floats
        while (done < floats) {
            val idx = awaitInput()
            if (idx < 0) { failure = "audio encoder stalled"; return }
            val buf = audio.getInputBuffer(idx)!!
            val frames = min((floats - done) / 2, min(buf.capacity(), pcm.size) / 4)
            var o = 0
            for (i in 0 until frames * 2) {
                val v = Math.round(chunk[done + i].toDouble().coerceIn(-1.0, 1.0) * 32767.0).toInt()
                pcm[o++] = v.toByte(); pcm[o++] = (v shr 8).toByte()
            }
            buf.clear(); buf.put(pcm, 0, o)
            val ptsUs = (firstBlockNanos / 1000 - t0Us) + (startFrame + done / 2) * 1_000_000L / rate
            audio.queueInputBuffer(idx, 0, o, max(0L, ptsUs), 0)
            done += frames * 2
            drainAudio()
        }
    }

    override fun onEnd() {
        try {
            val idx = awaitInput(200)
            if (idx >= 0) audio.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            val end = System.nanoTime() + 2_000_000_000L
            while (System.nanoTime() < end && !drainAudio()) Thread.sleep(10)
            runCatching { display?.release(); display = null }
            runCatching { video.signalEndOfInputStream() }
            videoThread.join(3000)
        } catch (e: Exception) { failure = failure ?: "stopping failed: ${e.message}" }
        finally { release() }
    }

    private fun awaitInput(attempts: Int = 40): Int {
        repeat(attempts) {
            val i = audio.dequeueInputBuffer(5_000)
            if (i >= 0) return i
            drainAudio()
        }
        return -1
    }

    /** Returns true once the encoder reported end of stream. */
    private fun drainAudio(): Boolean {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val i = audio.dequeueOutputBuffer(info, 0)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> addTrack(audio.outputFormat, isVideo = false)
                i >= 0 -> {
                    val out = audio.getOutputBuffer(i)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0) {
                        out.position(info.offset); out.limit(info.offset + info.size)
                        synchronized(lock) {
                            if (started) muxer?.writeSampleData(audioTrack, out, info)
                            else pendingAudio.add(ByteArray(info.size).also { out.get(it) } to MediaCodec.BufferInfo().also { it.set(0, info.size, info.presentationTimeUs, info.flags) })
                        }
                    }
                    audio.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }
            }
        }
    }

    // ---- video thread -----------------------------------------------------------------

    private fun drainVideo() {
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val i = video.dequeueOutputBuffer(info, 10_000)
                when {
                    i == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> addTrack(video.outputFormat, isVideo = true)
                    i >= 0 -> {
                        val out = video.getOutputBuffer(i)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0) {
                            synchronized(lock) {
                                // The muxer starts when the audio format is also known; wait for it.
                                var waited = 0
                                while (!started && failure == null && waited < 100) { lock.wait(50); waited++ }
                                if (started) {
                                    val offset = videoOffsetUs ?: ((System.nanoTime() / 1000 - t0Us) - info.presentationTimeUs).also { videoOffsetUs = it }
                                    val pts = max(info.presentationTimeUs + offset, lastVideoUs + 1)
                                    lastVideoUs = pts
                                    info.presentationTimeUs = max(0L, pts)
                                    out.position(info.offset); out.limit(info.offset + info.size)
                                    muxer?.writeSampleData(videoTrack, out, info)
                                }
                            }
                        }
                        video.releaseOutputBuffer(i, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        } catch (e: Exception) { failure = failure ?: "video encoding failed: ${e.message}" }
    }

    private fun addTrack(format: MediaFormat, isVideo: Boolean) = synchronized(lock) {
        val m = muxer ?: return@synchronized
        if (isVideo) videoTrack = m.addTrack(format) else audioTrack = m.addTrack(format)
        if (videoTrack >= 0 && audioTrack >= 0 && !started) {
            m.start(); started = true
            pendingAudio.forEach { (bytes, info) -> m.writeSampleData(audioTrack, java.nio.ByteBuffer.wrap(bytes), info) }
            pendingAudio.clear()
            lock.notifyAll()
        }
    }

    fun release() {
        synchronized(lock) {
            runCatching { display?.release() }; display = null
            runCatching { video.stop() }; runCatching { video.release() }
            runCatching { audio.stop() }; runCatching { audio.release() }
            runCatching { if (started) muxer?.stop() }
            runCatching { muxer?.release() }; muxer = null
            lock.notifyAll()
        }
    }

    private fun screenSize(context: Context): Triple<Int, Int, Int> {
        val wm = context.getSystemService(WindowManager::class.java)
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
        // About 720p on the short side keeps the file small enough to share; 16-aligned for encoders.
        val scale = min(1f, 720f / min(dm.widthPixels, dm.heightPixels))
        fun align(x: Int) = max(16, ((x * scale / 16f).toInt()) * 16)
        return Triple(align(dm.widthPixels), align(dm.heightPixels), dm.densityDpi)
    }
}
