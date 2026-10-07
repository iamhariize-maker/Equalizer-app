package app.svan

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.os.Process
import android.util.Log
import app.svan.model.AudioSettings
import app.svan.model.DitherChoice
import app.svan.model.EqState
import app.svan.model.QualityMode

/**
 * Engine B: capture other apps' playback, run the full native chain (parametric
 * EQ, Audiophile oversampling, dither), and play the result.
 *
 * The source apps' own output is muted by SessionRouter/SourceMuter (session
 * DynamicsProcessing at -200 dB; capture taps audio before session effects).
 * Apps that opt out of capture (reported: Spotify, Chrome, SoundCloud) are
 * detected by CaptureCompat and left unmuted on Engine A instead.
 */
class CaptureService : Service() {

    @Volatile private var running = false
    private var worker: Thread? = null
    private var projection: MediaProjection? = null
    @Volatile private var activeRecord: AudioRecord? = null
    private var systemPackages: Set<String> = emptySet()
    /** Other (non-Svan) active media players, from the public playback callback. */
    @Volatile private var otherActivePlayers = 0
    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            // Svan's own output track is one of them while capture runs.
            otherActivePlayers = (configs.count { it.audioAttributes.usage in CaptureCompat.MIX_USAGES } - 1).coerceAtLeast(0)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        SvanRepository.init(this)
        if (SvanRepository.settings.value.engineMode == app.svan.model.EngineMode.SYSTEM_ONLY) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Must be in the foreground (type mediaProjection) *before* getMediaProjection on Android 14+.
        startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val blocker = CapturePolicy.startupBlock(PlaybackSessions.hasDumpPermission(this), SessionRouter.snapshot, Process.myUid())
        if (blocker != null) {
            startupMessage.value = blocker
            EqController.log("capture: start blocked — $blocker")
            stopSelf()
            return START_NOT_STICKY
        }
        startupMessage.value = ""

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        SvanRepository.init(this)
        if (intent?.hasExtra(EXTRA_QUALITY) == true) {
            // Explicit quality (scripted tests) overrides the saved setting.
            val q = QualityMode.entries[intent.getIntExtra(EXTRA_QUALITY, 0).coerceIn(0, QualityMode.entries.size - 1)]
            SvanRepository.updateSettings { it.copy(quality = q) }
        }
        if (running) return START_NOT_STICKY
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(resultCode, data) ?: return START_NOT_STICKY
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { running = false; stopSelf() }
        }, null)
        projection = mp
        activeProjection = mp
        screenUsed = false

        SessionRouter.init(this)
        systemPackages = SessionRouter.appPreferences().systemOnlyPackages()

        running = true
        isRunning = true
        worker = Thread({ audioLoop(mp) }, "eq-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission") // checked before starting
    private fun audioLoop(mp: MediaProjection) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val rate = EqController.SAMPLE_RATE
        val frames = 256
        var record: AudioRecord? = null
        var track: AudioTrack? = null
        var engine: NativeEngine? = null
        var eqWatcher: Thread? = null
        try {
            val minOut = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            require(minOut > 0) { "unsupported output format ($minOut)" }
            val output = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(maxOf(minOut, frames * 2 * 4))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            track = output
            check(output.state == AudioTrack.STATE_INITIALIZED) { "output not initialized" }
            output.setBufferSizeInFrames(maxOf(minOut / 8, frames))
            var settings = SvanRepository.settings.value
            var dsp = buildEngine(settings)
            engine = dsp
            synchronized(engineLock) { current = dsp }
            eqWatcher = Thread({
                var last: EqState? = null
                var lastSettings: AudioSettings? = null
                while (running) {
                    val eq = SvanRepository.eq.value
                    val audioSettings=SvanRepository.settings.value
                    if (eq !== last || audioSettings != lastSettings) {
                        synchronized(engineLock) { current?.let {
                            applyProtection(it,eq,audioSettings)
                            if(eq !== last)applyEq(it, eq)
                        } }
                        last = eq
                        lastSettings=audioSettings
                    }
                    Thread.sleep(15)
                }
            }, "svan-eq-watch").apply { start() }

            // Resolve existing players before the main AudioRecord is opened. Some
            // Android audio HALs reject a second playback-capture AudioRecord, so
            // CaptureCompat's startup probes must finish first.
            check(SessionRouter.onCaptureStarted(mp, systemPackages)) { "session routing failed before capture startup" }
            var allowed: Set<Int> = SessionRouter.captureUids
            var input = openRecord(mp, allowed)
            record = input
            activeRecord = input
            input.startRecording()
            output.play()
            EqController.log("capture: started, quality=${settings.quality}, DSP latency=${dsp.latencyFrames} frames, output buffer=${output.bufferSizeInFrames} frames")
            val buf = FloatArray(frames * 2)
            var levelPeak = 0f
            var outputPeak = 0f
            var levelFrames = 0L
            var writtenFrames = 0L
            var dspNanos = 0L
            var processedFrames = 0L
            // Frames of exact digital silence in a row (a capture-blocked or paused source).
            var silentRun = 0L
            var watchdogFired = false
            getSystemService(AudioManager::class.java).registerAudioPlaybackCallback(playbackCallback, null)
            while (running) {
                val next = SessionRouter.captureUids
                if (next !== allowed) {
                    // Route changes are rare. No record, filter or buffer allocation
                    // occurs in steady playback. Never capture unknown/unmuted apps.
                    input.stop(); input.release(); record = null; activeRecord = null
                    allowed = next
                    silentRun = 0; watchdogFired = false
                    input = openRecord(mp, allowed)
                    record = input; activeRecord = input
                    input.startRecording()
                    EqController.log("capture filter: ${allowed.size} muted UID(s)")
                }
                val now = SvanRepository.settings.value
                if (!now.sameCaptureFormat(settings)) {
                    // Build before swapping so an unsuccessful rebuild leaves a
                    // valid handle for cleanup. Native parameter edits stay separate.
                    val replacement = buildEngine(now)
                    synchronized(engineLock) {
                        current = replacement
                        dsp.close()
                        dsp = replacement
                        engine = replacement
                        settings = now
                    }
                    EqController.log("capture: engine rebuilt, quality=${now.quality}")
                }
                settings=now
                val n = input.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n < 0) { if (running) EqController.log("capture: read failed ($n)"); break }
                if (n == 0) continue
                app.svan.listening.ClipRecorder.offer(buf,n)
                app.svan.listening.ProofRecorder.offerDry(buf, n) // dry tap, before the DSP edits buf in place
                var blockPeak = 0f
                for (i in 0 until n) blockPeak = maxOf(blockPeak, kotlin.math.abs(buf[i]))
                levelPeak = maxOf(levelPeak, blockPeak)
                levelFrames += n / 2
                if (app.svan.listening.ClipPlayer.playing) silentRun=0
                else if (blockPeak == 0f) silentRun += n / 2 else { silentRun = 0; watchdogFired = false }
                // Fail open: a muted source whose capture stays all-zero while other media is
                // playing means its audio is not reaching us (capture opt-out mid-session, a
                // DRM stream...). Silence forever is the worst outcome, so hand it back to
                // Engine A. Paused sources are indistinguishable from blocked ones without
                // another active player, hence the otherActivePlayers condition.
                if (allowed.isNotEmpty() && !watchdogFired && silentRun >= rate * SILENCE_FAILOPEN_S && otherActivePlayers > 0) {
                    watchdogFired = true
                    EqController.log("capture: muted source delivers only silence for ${SILENCE_FAILOPEN_S}s while other media plays → failing open")
                    SessionRouter.onCaptureSilent()
                }
                if (allowed.isNotEmpty() && silentRun < rate) {
                    val begin = System.nanoTime()
                    dsp.process(buf, buf, n / 2)
                    dspNanos += System.nanoTime() - begin
                } else {
                    // No admitted source, or >1 s of digital silence (filter state has fully
                    // decayed): do not spend phone CPU oversampling silence or let dither/filter
                    // tails masquerade as music. Processing resumes with the next non-zero block.
                    java.util.Arrays.fill(buf, 0, n, 0f)
                }
                app.svan.listening.ProofRecorder.commitWet(buf, n) // exactly what the AudioTrack receives
                for (i in 0 until n) outputPeak = maxOf(outputPeak, kotlin.math.abs(buf[i]))
                processedFrames += n / 2
                var written = 0
                while (running && written < n) {
                    val count = output.write(buf, written, n - written, AudioTrack.WRITE_BLOCKING)
                    if (count <= 0) { running = false; break }
                    written += count
                    writtenFrames += count / 2
                }
                if (levelFrames >= rate * 2) {
                    val played = output.playbackHeadPosition.toLong() and 0xffffffffL
                    val queued = (writtenFrames - played).coerceAtLeast(0)
                    stats = Stats(output.bufferSizeInFrames * 1000.0 / rate, queued * 1000.0 / rate,
                        dsp.latencyFrames * 1000.0 / rate, output.underrunCount,
                        dspNanos.toDouble() / (processedFrames * 1e9 / rate) * 100.0,
                        dsp.appliedGainDb, dsp.gainProtectionDb,
                        20.0 * kotlin.math.log10(maxOf(levelPeak.toDouble(), 1e-6)),
                        20.0 * kotlin.math.log10(maxOf(outputPeak.toDouble(), 1e-6)))
                    val muted = SessionRouter.snapshot.filter { it.owner == SessionRouter.Owner.ENGINE_B_MUTED }.joinToString { it.pkg }
                    EqController.log("capture level: peak=%.4f over %d frames; output queued=%.1f ms, underruns=%d, DSP=%.1f%%, muted=[%s], otherPlayers=%d".format(levelPeak, levelFrames, stats!!.queuedMs, stats!!.underruns, stats!!.dspPercent, muted, otherActivePlayers))
                    levelPeak = 0f; outputPeak = 0f; levelFrames = 0; dspNanos = 0; processedFrames = 0
                }
            }
        } catch (e: Exception) {
            EqController.log("capture: audio loop failed: $e")
        } finally {
            running = false
            app.svan.listening.ProofRecorder.stop()
            runCatching { getSystemService(AudioManager::class.java).unregisterAudioPlaybackCallback(playbackCallback) }
            activeRecord = null
            record?.let { runCatching { it.stop() }; it.release() }
            track?.let { runCatching { it.pause(); it.flush(); it.stop() }; it.release() }
            eqWatcher?.join(200)
            synchronized(engineLock) { current = null; engine?.close() }
            isRunning = false
            stats = null
            SessionRouter.onCaptureStopped()
            EqController.log("capture: stopped")
            stopSelf()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(mp: MediaProjection, allowed: Set<Int>): AudioRecord {
        val rate = EqController.SAMPLE_RATE
        val builder = AudioPlaybackCaptureConfiguration.Builder(mp)
        CaptureCompat.MIX_USAGES.forEach { builder.addMatchingUsage(it) }
        // An empty route list must yield silence, not an unrestricted capture.
        // Our own output opts out of capture in the manifest.
        if (allowed.isEmpty()) builder.addMatchingUid(Process.myUid())
        else allowed.forEach { builder.addMatchingUid(it) }
        val minIn = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        require(minIn > 0) { "unsupported capture format ($minIn)" }
        return AudioRecord.Builder()
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build())
            .setBufferSizeInBytes(maxOf(minIn, 256 * 8))
            .setAudioPlaybackCaptureConfig(builder.build()).build().also {
                if (it.state != AudioRecord.STATE_INITIALIZED) { it.release(); error("capture not initialized") }
            }
    }

    private fun buildEngine(s: AudioSettings): NativeEngine =
        NativeEngine(
            EqController.SAMPLE_RATE, 2,
            oversample = s.quality.oversample,
            stopbandDb = s.quality.stopbandDb,
            // Dither "off" means no word-length reduction at all: float goes straight out.
            ditherBits = if (s.dither == DitherChoice.OFF) 0 else s.outputBits,
            ditherMode = s.dither.nativeMode,
            autoHeadroom = s.effectiveFor(SvanRepository.eq.value).autoHeadroom,
            gainProtection = s.effectiveFor(SvanRepository.eq.value).gainProtection,
        ).also {
            it.setAnalysis(true) // Svaramanas listens to the source (preallocated mid/side analysis every 85 ms)
            applyEq(it, SvanRepository.eq.value)
        }

    private fun applyEq(engine: NativeEngine, eq: EqState) {
        // Preserve limiter history through adaptation; resetting it would release
        // attenuation abruptly every time Svaresa publishes a new curve.
        engine.setDynamicEq(eq.dynamicEq)
        engine.setBands(eq.effectiveBands().map { it.toNative() })
        engine.setPreampDb(eq.effectivePreampDb())
        engine.setBassCharacter(eq.bassCharacter, eq.bass.crossoverHz)
        val v = eq.activeVocal
        val i = eq.activeInstrument
        engine.setStereoTuner(v.intimacy, v.warmth, v.smoothness, i.space, i.instruments)
    }

    private fun applyProtection(engine: NativeEngine,eq: EqState,settings: AudioSettings) {
        val effective=settings.effectiveFor(eq)
        engine.setAutoHeadroom(effective.autoHeadroom)
        engine.setGainProtection(effective.gainProtection)
    }

    override fun onDestroy() {
        running = false
        runCatching { activeRecord?.stop() }
        isRunning = false
        worker?.join(500)
        activeProjection = null
        projection?.stop()
        if (worker == null) SessionRouter.onCaptureStopped()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Capture engine", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Svan capture engine running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }

    data class Stats(val bufferMs: Double, val queuedMs: Double, val dspLatencyMs: Double,
        val underruns: Int, val dspPercent: Double, val gainDb: Double, val protectionDb: Double,
        val inputPeakDb: Double, val outputPeakDb: Double)

    companion object {
        val startupMessage = kotlinx.coroutines.flow.MutableStateFlow("")
        private val engineLock = Any()
        @Volatile private var current: NativeEngine? = null

        /** What Svaramanas heard (packed SourceFeatures), or null when Engine B isn't running. */
        fun analysis(): DoubleArray? = synchronized(engineLock) { current?.analysis() }

        @Volatile var stats: Stats? = null
            private set
        private const val TAG = "CaptureService"
        private const val CHANNEL = "capture"
        private const val NOTIF_ID = 1
        private const val SILENCE_FAILOPEN_S = 4
        const val ACTION_STOP = "app.svan.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_QUALITY = "quality"

        @Volatile var isRunning = false
            private set

        /** The engine's consent-granted projection, for Recording mode's screen video. Android 14+ allows one VirtualDisplay per projection. */
        @Volatile var activeProjection: MediaProjection? = null
            private set
        @Volatile var screenUsed = false

        /** [quality] null = use the saved Audiophile setting. */
        fun start(context: Context, resultCode: Int, data: Intent, quality: QualityMode? = null) {
            Log.i(TAG, "starting capture, quality=${quality ?: "saved"}")
            context.startForegroundService(
                Intent(context, CaptureService::class.java)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_RESULT_DATA, data)
                    .apply { if (quality != null) putExtra(EXTRA_QUALITY, quality.ordinal) },
            )
        }
    }
}
