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
import app.svan.model.SpatialMode
import app.svan.lab.IntegratedLab

/**
 * Engine B: capture other apps' playback, run the full native chain (parametric
 * EQ, Audiophile oversampling, dither), and play the result.
 *
 * The source apps' own output is muted by SessionRouter/SourceMuter (session
 * DynamicsProcessing at -200 dB), only after capture is verified both before and after the mute.
 * Apps that opt out of capture are probed on the present setup and left
 * unmuted on Engine A instead; player names alone do not determine capability.
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
        app.svan.diag.EngineTrace.attachStorage(java.io.File(filesDir, "diag"))
        if (SvanRepository.settings.value.engineMode == app.svan.model.EngineMode.SYSTEM_ONLY) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Must be in the foreground (type mediaProjection) *before* getMediaProjection on Android 14+.
        if (!running) app.svan.diag.EngineTrace.beginRun()
        startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        app.svan.diag.EngineTrace.mark(app.svan.diag.EngineTrace.Mark.FOREGROUND)
        val blocker = CapturePolicy.startupBlock(PlaybackSessions.hasReportAccess(this), SessionRouter.snapshot, Process.myUid(), SharedOutput.status.value.requested)
        if (blocker != null) {
            startupMessage.value = blocker
            EqController.log("capture: start blocked — $blocker")
            stopSelf()
            return START_NOT_STICKY
        }
        startupMessage.value = ""
        recoveryMessage.value = ""

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
        val mp = mpm.getMediaProjection(resultCode, data) ?: run {
            EqController.log("capture: Android returned no capture token (result code $resultCode)")
            return START_NOT_STICKY
        }
        app.svan.diag.EngineTrace.mark(app.svan.diag.EngineTrace.Mark.PROJECTION)
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { running = false; stopSelf() }
        }, null)
        projection = mp

        SessionRouter.init(this)
        systemPackages = SessionRouter.appPreferences().systemOnlyPackages()

        running = true
        isRunning = true
        // Capture replays processed audio through the mix: never also EQ the whole mix.
        Thread { EqController.globalEq.setMixFallback(false) }.start()
        worker = Thread({ audioLoop(mp) }, "eq-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission") // checked before starting
    private fun audioLoop(mp: MediaProjection) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        try {
            // Probe before the main recorder: some HALs cannot open two capture records.
            check(SessionRouter.onCaptureStarted(mp, systemPackages)) { "session routing failed before capture startup" }
            app.svan.diag.EngineTrace.mark(app.svan.diag.EngineTrace.Mark.ROUTING_DONE)
            var safeFallback = false
            while (running) {
                when (audioEpoch(mp, safeFallback)) {
                    EpochExit.STOP -> break
                    EpochExit.SAFE_RETRY -> {
                        app.svan.diag.EngineTrace.restarted(recoveryMessage.value)
                        safeFallback = true // at most one conservative recovery reopen
                    }
                    EpochExit.LAB_REOPEN -> EqController.log("capture: reopening for Lab selection; quality and projection retained")
                }
            }
        } catch (e: Exception) {
            EqController.log("capture: startup failed: $e")
        } finally {
            running = false
            isRunning = false
            epoch = null; stats = null; rateFacts = null
            SessionRouter.onCaptureStopped()
            EqController.log("capture: stopped")
            app.svan.diag.EngineTrace.mark(app.svan.diag.EngineTrace.Mark.STOPPED)
            stopSelf()
        }
    }

    @SuppressLint("MissingPermission")
    private enum class EpochExit { STOP, SAFE_RETRY, LAB_REOPEN }
    private fun audioEpoch(mp: MediaProjection, safeFallback: Boolean): EpochExit {
        epoch = null
        app.svan.listening.ProofRecorder.stop() // finish the old file before any format renegotiation
        var record: AudioRecord? = null
        var recorderLease: CaptureRecorderGate.Lease? = null
        var track: AudioTrack? = null
        var engine: NativeEngine? = null
        var eqWatcher: Thread? = null
        val epochRunning = java.util.concurrent.atomic.AtomicBoolean(true)
        val settings = SvanRepository.settings.value.let {
            if (safeFallback) it.copy(spatialMode = SpatialMode.FAST, captureRateMode = RatePolicy.Mode.SAFE) else it
        }
        val spatialCapable = settings.spatialMode != SpatialMode.FAST
        val labSelection = IntegratedLab.captureSelectionId
        val spatialLimited = java.util.concurrent.atomic.AtomicBoolean(false)
        // Set by the audio thread when recovery changes the spatial limit; the watcher records it
        // in the epoch under the lock, so the audio thread never waits for the lock on that path.
        val spatialEpochDirty = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            var allowed: Set<Int> = SessionRouter.captureUids
            val manager = getSystemService(AudioManager::class.java)
            val mixerHint = manager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
            // Query the actual route using silent output, never infer it from all connected devices.
            val deviceRates = if (settings.captureRateMode == RatePolicy.Mode.SAFE) emptyList() else
                openTrack(RatePolicy.SAFE_HZ).let { probe ->
                    try {
                        val silence = FloatArray(RatePolicy.SAFE_HZ / 25 * 2)
                        probe.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING)
                        probe.play()
                        var attempts = 0
                        while (running && probe.routedDevice == null && attempts++ < 10) Thread.sleep(20)
                        probe.routedDevice?.sampleRates?.toList().orEmpty()
                    } finally { runCatching { probe.stop() }; probe.release() }
                }
            val negotiation = RateNegotiation.open(RatePolicy.candidates(mixerHint, deviceRates, settings.captureRateMode)) { candidate ->
                val out = openTrack(candidate)
                var lease: CaptureRecorderGate.Lease? = null
                var rec: AudioRecord? = null
                try {
                    lease = CaptureCompat.recorders.await("main", active = { running })
                    rec = startRecord(mp, allowed, candidate, lease)
                    check(rec.sampleRate == candidate && out.sampleRate == candidate) { "client format differs from requested $candidate Hz" }
                    Triple(rec, out, lease)
                } catch (e: Exception) {
                    rec?.let { runCatching { it.stop() }; it.release() }
                    lease?.close()
                    out.release()
                    EqController.log("capture: rate $candidate rejected: ${e.message}")
                    throw e
                }
            }
            val rate = negotiation.rate
            app.svan.diag.EngineTrace.mark(app.svan.diag.EngineTrace.Mark.RATE_NEGOTIATED, "$rate Hz" + if (safeFallback) " (safe fallback)" else "")
            val frames = maxOf(64, (rate * 256L / 48000).toInt())
            var input: AudioRecord? = negotiation.value.first
            val output = negotiation.value.second
            recorderLease = negotiation.value.third
            record = input; activeRecord = input
            track = output
            val minOut = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            // Standing reserve against scheduling stalls (app switches, a busy phone).
            // Clock identity is not assumed; timestamp/backlog qualification remains separate.
            val cushion = rate * OUTPUT_CUSHION_MS / 1000
            output.setBufferSizeInFrames(minOf(output.bufferCapacityInFrames, maxOf(minOut / 8, cushion + frames * 2)))
            val lab = IntegratedLab.captureControls(rate)
            var dsp = buildEngine(settings, rate, spatialCapable, lab)
            engine = dsp
            synchronized(engineLock) { current = dsp }
            epoch = CaptureEpoch(nextEpoch.incrementAndGet(), rate, dsp.latencyFrames, spatialCapable, settings,
                lab?.block, lab?.plan?.hybrid ?: false)
            if (lab != null) EqController.log("capture Lab: ${lab.block} frames at $rate Hz, hybrid=${lab.plan.hybrid}; static EQ replaced, final native protection retained")
            else if (IntegratedLab.state.value.applied) EqController.log("capture Lab: no reference fit for $rate Hz; normal native capture active")
            app.svan.listening.ClipRecorder.captureChanged(epoch)
            eqWatcher = Thread({
                var last: EqState? = null
                var lastSettings: AudioSettings? = null
                while (running && epochRunning.get()) {
                    val eq = SvanRepository.eq.value
                    val audioSettings=SvanRepository.settings.value
                    if (spatialEpochDirty.getAndSet(false)) synchronized(engineLock) {
                        val limited = spatialLimited.get()
                        epoch = epoch?.let { active -> active.copy(appliedSettings = active.appliedSettings.copy(
                            spatialMode = if (limited) SpatialMode.FAST else audioSettings.spatialMode)) }
                        app.svan.listening.ClipRecorder.captureChanged(epoch)
                    }
                    if (eq !== last || audioSettings != lastSettings) {
                        synchronized(engineLock) { current?.let {
                            applyProtection(it,eq,audioSettings)
                            it.setBassUnmask(if (audioSettings.experimentalBassUnmask && eq.enabled) 1.0 else 0.0)
                            if (spatialCapable) it.setSpatialMode(audioSettings.spatialMode)
                            if(eq !== last)applyEq(it, eq, last)
                            epoch = epoch?.let { active ->
                                val live = active.appliedSettings.withLiveCaptureControls(audioSettings)
                                active.copy(appliedSettings = if (spatialCapable) live.copy(
                                    spatialMode = if (spatialLimited.get()) SpatialMode.FAST else audioSettings.spatialMode
                                ) else live)
                            }
                            app.svan.listening.ClipRecorder.captureChanged(epoch)
                        } }
                        last = eq
                        lastSettings=audioSettings
                    }
                    Thread.sleep(15)
                }
            }, "svan-eq-watch").apply { start() }

            val fade = CaptureFade(rate / 100)
            EqController.log("capture: started, quality=${settings.quality}, DSP latency=${dsp.latencyFrames} frames, output buffer=${output.bufferSizeInFrames} frames")
            val buf = FloatArray(frames * 2)
            // Prime before play: starting on one 5 ms block underruns at the first hiccup.
            val primed = minOf(cushion, output.bufferSizeInFrames - frames) * 2
            // Count what the track actually accepted: primed silence is queued audio too.
            val primedWritten = if (primed > 0) output.write(FloatArray(primed), 0, primed, AudioTrack.WRITE_NON_BLOCKING) else 0
            output.play()
            val recovery = CaptureBufferRecovery(output.underrunCount)
            val spatialRecovery = CaptureSpatialRecovery(output.underrunCount)
            var recoveryFrames = 0L
            runCatching {
                val facts = RateFacts(rate, input?.sampleRate, output.sampleRate,
                    mixerHint,
                    output.routedDevice?.sampleRates?.toList().orEmpty(), input?.activeRecordingConfiguration?.format?.sampleRate)
                rateFacts = facts
                EqController.log("capture: ${facts.summary()}")
            }
            // An idle recorder serves no source and can silently starve a second recorder on OEMs.
            // Close it before any late check. The output keeps its paced silence and standing cushion.
            if (allowed.isEmpty()) {
                input?.let { runCatching { it.stop() }; it.release() }
                input = null; record = null; activeRecord = null
                recorderLease?.close(); recorderLease = null
            }
            if (negotiation.failures.isNotEmpty()) recoveryMessage.value = "Rate candidates ${negotiation.failures.joinToString()} Hz failed; using $rate Hz. Source and DAC rates remain unknown."
            var levelPeak = 0f
            var outputPeak = 0f
            var levelFrames = 0L
            // Frames the recorder really delivered vs. frames generated because no source is admitted.
            var capturedFrames = 0L
            var generatedFrames = 0L
            var writtenFrames = maxOf(primedWritten, 0) / 2L
            val headClock = PlaybackHeadClock()
            var dspNanos = 0L
            // Timing probe for the 2 s log only: where the audio thread waits or works per block.
            var readWaitMaxNs = 0L
            var dspMaxNs = 0L
            var writeWaitMaxNs = 0L
            var overBudgetBlocks = 0
            var cushionTopUps = 0
            var processedFrames = 0L
            // Frames of exact digital silence in a row (a capture-blocked or paused source).
            var silentRun = 0L
            var watchdogFired = false
            val noDataWatchdog = CaptureNoDataWatchdog()
            // Flight recorder: milestones fire once per epoch; windows are taken every 2 s (see EngineTrace).
            var sawFrame = false
            var sawAudio = false
            var sawOutput = false
            var lastIdleWindowNs = System.nanoTime()
            fun traceWindow(captured: Long, generated: Long, inDb: Double, outDb: Double, queuedMs: Double,
                            dspPct: Double, over: Int, readMs: Double, writeMs: Double) {
                val silenced = runCatching { input?.activeRecordingConfiguration?.isClientSilenced }.getOrNull()
                app.svan.diag.EngineTrace.sample(app.svan.diag.EngineTrace.Sample(
                    System.currentTimeMillis(), epoch?.id, rate,
                    if (allowed.isEmpty()) "no source admitted" else if (input == null) "waiting for recorder lease" else "captured",
                    captured, generated, inDb, outDb, queuedMs, output.underrunCount, dspPct, over, readMs, writeMs,
                    silenced, otherActivePlayers,
                    SessionRouter.snapshot.filter { it.owner == SessionRouter.Owner.ENGINE_B_MUTED }.map { it.pkg }))
            }
            fun changeSources(next: Set<Int>) {
                input?.let { runCatching { it.stop() }; it.release() }
                input = null; record = null; activeRecord = null
                recorderLease?.close(); recorderLease = null
                allowed = next
                silentRun = 0; watchdogFired = false; noDataWatchdog.reset()
                // A source boundary resets analysis, even when the old recorder returned no final block.
                val replacement = buildEngine(epoch?.appliedSettings ?: settings, rate, spatialCapable, lab)
                replacement.setSpatialLoadLimited(spatialLimited.get())
                synchronized(engineLock) {
                    current = null
                    dsp.close()
                    dsp = replacement
                    engine = dsp
                    current = dsp
                    epoch = epoch?.copy(id = nextEpoch.incrementAndGet())
                }
                fade.restart()
                app.svan.listening.ClipRecorder.captureChanged(epoch)
                EqController.log("capture filter: ${allowed.size} muted UID(s)")
            }
            getSystemService(AudioManager::class.java).registerAudioPlaybackCallback(playbackCallback, null)
            while (running) {
                val next = SessionRouter.captureUids
                // A finishing probe retains its lease until its AudioRecord has actually been released.
                // Keep output paced rather than opening a competing recorder or blocking the audio thread.
                if (input == null && allowed.isNotEmpty()) {
                    CaptureCompat.recorders.tryAcquire("main")?.let { lease ->
                        try {
                            input = startRecord(mp, allowed, rate, lease)
                            record = input; activeRecord = input; recorderLease = lease
                        } catch (e: Exception) {
                            if (!safeFallback && rate != RatePolicy.SAFE_HZ) {
                                recoveryMessage.value = "Capture format was rejected for this source; retrying Fast at safe 48 kHz."
                                return EpochExit.SAFE_RETRY
                            }
                            throw e
                        }
                    }
                }
                // Fade the final block before reopening the recorder. Settings that change
                // filter latency apply at the next capture start. Source changes reset the
                // native analysis at this explicit, faded boundary, keeping latency unchanged.
                // Identity first: SessionRouter publishes a new set only when its contents change, so the
                // steady state never runs Set.equals (which allocates) on the audio thread.
                val sourceChanged = next !== allowed && next != allowed
                val readBegin = System.nanoTime()
                // A blocking read can prevent a source change or fail-open from ever running if the HAL stops
                // returning frames. Read what is available; empty reads have their own elapsed-time deadline.
                // Without an admitted source, output silence is paced by the blocking write below.
                val reading = input
                val n = if (allowed.isEmpty() || reading == null) { java.util.Arrays.fill(buf, 0f); buf.size }
                    else reading.read(buf, 0, buf.size, AudioRecord.READ_NON_BLOCKING)
                readWaitMaxNs = maxOf(readWaitMaxNs, System.nanoTime() - readBegin)
                if (n < 0) {
                    if (running) EqController.log("capture: read failed ($n)")
                    if (running && !safeFallback && (rate != RatePolicy.SAFE_HZ || epoch?.detailed == true)) {
                        recoveryMessage.value = "Capture read failed; retrying Fast at safe 48 kHz."
                        return EpochExit.SAFE_RETRY
                    }
                    break
                }
                if (n == 0) {
                    if (sourceChanged && running) changeSources(next)
                    else if (!watchdogFired && noDataWatchdog.observe(n, System.nanoTime()) && otherActivePlayers > 0) {
                        if (!safeFallback && rate != RatePolicy.SAFE_HZ) {
                            recoveryMessage.value = "Capture stopped delivering frames; retrying Fast at safe 48 kHz."
                            return EpochExit.SAFE_RETRY
                        }
                        watchdogFired = true
                        EqController.log("capture: no frames for 2.5s while a muted source plays → failing open")
                        SessionRouter.onCaptureSilent(noData = true)
                    }
                    // A recorder that delivers nothing never completes a normal 2 s window: record that fact explicitly.
                    if (reading != null && allowed.isNotEmpty() && System.nanoTime() - lastIdleWindowNs >= 2_000_000_000L) {
                        lastIdleWindowNs = System.nanoTime()
                        traceWindow(0, 0, -120.0, -120.0, stats?.queuedMs ?: 0.0, 0.0, 0, readWaitMaxNs / 1e6, writeWaitMaxNs / 1e6)
                        readWaitMaxNs = 0L
                    }
                    Thread.sleep(2)
                    continue
                }
                noDataWatchdog.reset()
                lastIdleWindowNs = System.nanoTime()
                if (allowed.isEmpty() || reading == null) generatedFrames += n / 2 else capturedFrames += n / 2
                app.svan.listening.ClipRecorder.offer(buf,n,epoch)
                app.svan.listening.ProofRecorder.offerDry(buf, n) // dry tap, before the DSP edits buf in place
                var blockPeak = 0f
                for (i in 0 until n) blockPeak = maxOf(blockPeak, kotlin.math.abs(buf[i]))
                levelPeak = maxOf(levelPeak, blockPeak)
                if (reading != null && allowed.isNotEmpty()) {
                    if (!sawFrame) { sawFrame = true; app.svan.diag.EngineTrace.mark(app.svan.diag.EngineTrace.Mark.FIRST_FRAME) }
                    if (!sawAudio && blockPeak > 0f) { sawAudio = true; app.svan.diag.EngineTrace.mark(app.svan.diag.EngineTrace.Mark.FIRST_AUDIO) }
                }
                levelFrames += n / 2
                if (app.svan.listening.ClipPlayer.playing) silentRun=0
                else if (blockPeak == 0f) silentRun += n / 2 else { silentRun = 0; watchdogFired = false }
                // Fail open: a muted source whose capture stays all-zero while other media is
                // playing means its audio is not reaching us (capture opt-out mid-session, a
                // DRM stream...). Silence forever is the worst outcome, so hand it back to
                // Engine A. Paused sources are indistinguishable from blocked ones without
                // another active player, hence the otherActivePlayers condition. Once this epoch has heard the
                // source, silence is a stall (buffering, a gap between tracks): it waits longer, skips the rate
                // retry (a capture restart) and records no strike.
                val silenceLimitS = if (sawAudio) STALL_FAILOPEN_S else SILENCE_FAILOPEN_S
                if (reading != null && allowed.isNotEmpty() && !watchdogFired && silentRun >= rate * silenceLimitS && otherActivePlayers > 0) {
                    if (!sawAudio && !safeFallback && rate != RatePolicy.SAFE_HZ) {
                        recoveryMessage.value = "Capture returned silence at this rate; retrying Fast at safe 48 kHz."
                        return EpochExit.SAFE_RETRY
                    }
                    watchdogFired = true
                    EqController.log("capture: muted source delivers only silence for ${silenceLimitS}s while other media plays → failing open" + if (sawAudio) " (stall, no strike)" else "")
                    SessionRouter.onCaptureSilent(stall = sawAudio)
                }
                if (reading != null && allowed.isNotEmpty() && silentRun < rate) {
                    val begin = System.nanoTime()
                    dsp.process(buf, buf, n / 2)
                    val elapsed = System.nanoTime() - begin
                    dspNanos += elapsed
                    dspMaxNs = maxOf(dspMaxNs, elapsed)
                    if (elapsed > (n / 2) * 1_000_000_000L / rate) overBudgetBlocks++
                } else {
                    // No admitted source, or >1 s of digital silence (filter state has fully
                    // decayed): do not spend phone CPU oversampling silence or let dither/filter
                    // tails masquerade as music. Processing resumes with the next non-zero block.
                    java.util.Arrays.fill(buf, 0, n, 0f)
                }
                val labChanged = IntegratedLab.captureSelectionId != labSelection
                fade.apply(buf, n, fadeOut = sourceChanged || labChanged)
                app.svan.listening.ProofRecorder.commitWet(buf, n) // exactly what the AudioTrack receives
                var blockOut = 0f
                for (i in 0 until n) blockOut = maxOf(blockOut, kotlin.math.abs(buf[i]))
                outputPeak = maxOf(outputPeak, blockOut)
                processedFrames += n / 2
                var written = 0
                var writeError: Int? = null
                val writeBegin = System.nanoTime()
                while (running && written < n) {
                    val count = output.write(buf, written, n - written, AudioTrack.WRITE_BLOCKING)
                    if (count <= 0) { writeError = count; break }
                    written += count
                    writtenFrames += count / 2
                }
                writeWaitMaxNs = maxOf(writeWaitMaxNs, System.nanoTime() - writeBegin)
                if (!sawOutput && written > 0 && blockOut > 0f) { sawOutput = true; app.svan.diag.EngineTrace.mark(app.svan.diag.EngineTrace.Mark.FIRST_OUTPUT) }
                // Rebuild the cushion during digital silence. A stall spends the primed cushion and nothing else
                // refills it (the loop writes only what capture delivers), so the next hiccup would underrun.
                // Extra silence here adds latency only while nothing is playing, where it cannot be heard.
                if (writeError == null && running && blockPeak == 0f && blockOut == 0f) {
                    val queuedNow = writtenFrames - headClock.unwrap(output.playbackHeadPosition)
                    if (queuedNow < cushion - frames) {
                        java.util.Arrays.fill(buf, 0, n, 0f)
                        val topUp = output.write(buf, 0, n, AudioTrack.WRITE_NON_BLOCKING)
                        if (topUp > 0) { writtenFrames += topUp / 2; cushionTopUps++ }
                    }
                }
                if (writeError != null && running) {
                    // A dead or invalidated output (audio server restart, route teardown) is recoverable:
                    // rebuild once in Fast at 48 kHz like a read failure, instead of ending the session.
                    EqController.log("capture: output write failed ($writeError)")
                    if (!safeFallback) {
                        recoveryMessage.value = "Playback output was interrupted; restarting capture in Fast at safe 48 kHz."
                        return EpochExit.SAFE_RETRY
                    }
                    recoveryMessage.value = "Playback output failed again. Svan returned to system effects."
                    break
                }
                if (labChanged && running) {
                    recoveryMessage.value = "Lab selection changed. Rebuffering capture with the same quality and permission."
                    return EpochExit.LAB_REOPEN
                }
                if (sourceChanged && running) {
                    changeSources(next)
                }
                recoveryFrames += n / 2
                // Check recovery independently of the two-second diagnostic window. A
                // late DSP block alone is not failure: queued audio can cover it.
                if (recoveryFrames >= rate / 4) {
                    val windowMs = (recoveryFrames * 1000 / rate).toInt()
                    val desired = recovery.nextSize(output.underrunCount, output.bufferSizeInFrames,
                        output.bufferCapacityInFrames, frames, windowMs)
                    val limited = spatialRecovery.observe(output.underrunCount, windowMs)
                    if (spatialCapable && limited != spatialLimited.get()) {
                        // Native setters are atomics: no lock on the audio thread (a normal-priority
                        // watcher holding it would otherwise stall playback during recovery).
                        spatialLimited.set(limited)
                        dsp.setSpatialMode(SvanRepository.settings.value.spatialMode)
                        dsp.setSpatialLoadLimited(limited)
                        spatialEpochDirty.set(true)
                        recoveryMessage.value = if (limited)
                            "Playback buffer ran short. Spatial processing is fading to Fast while capture keeps running; it can recover after ten seconds without new underruns."
                        else "Playback recovered. Your selected spatial mode is resuming smoothly."
                    }
                    recoveryFrames = 0
                    if (desired < 0) {
                        if (!safeFallback && rate != RatePolicy.SAFE_HZ) {
                            recoveryMessage.value = "Persistent underruns: restarting in Fast mode at safe 48 kHz. Your saved choices are unchanged."
                            return EpochExit.SAFE_RETRY
                        }
                        recoveryMessage.value = "Capture could not keep up on this output. Svan returned to system effects. Try Efficient quality before restarting capture."
                        EqController.log("capture: persistent underruns at maximum buffer; returning to system effects")
                        break
                    }
                    if (desired > output.bufferSizeInFrames) {
                        output.setBufferSizeInFrames(desired)
                        EqController.log("capture: underrun recovery buffer=${output.bufferSizeInFrames} frames")
                    }
                }
                if (levelFrames >= rate * 2) {
                    rateFacts = rateFacts?.copy(deviceReportedHz = output.routedDevice?.sampleRates?.toList().orEmpty(),
                        captureClientHz = input?.sampleRate,
                        captureDeviceHz = runCatching { input?.activeRecordingConfiguration?.format?.sampleRate }.getOrNull())
                    val played = headClock.unwrap(output.playbackHeadPosition)
                    val queued = (writtenFrames - played).coerceAtLeast(0)
                    stats = Stats(output.bufferSizeInFrames * 1000.0 / rate, queued * 1000.0 / rate,
                        dsp.latencyFrames * 1000.0 / rate, output.underrunCount,
                        dspNanos.toDouble() / (processedFrames * 1e9 / rate) * 100.0,
                        dsp.appliedGainDb, dsp.gainProtectionDb,
                        20.0 * kotlin.math.log10(maxOf(levelPeak.toDouble(), 1e-6)),
                        20.0 * kotlin.math.log10(maxOf(outputPeak.toDouble(), 1e-6)), dsp.detailedMix)
                    unmaskSnapshot = dsp.bassUnmaskDiagnostics() // audio-thread snapshot; UI never races DSP getters
                    traceWindow(capturedFrames, generatedFrames, stats!!.inputPeakDb, stats!!.outputPeakDb, stats!!.queuedMs,
                        stats!!.dspPercent, overBudgetBlocks, readWaitMaxNs / 1e6, writeWaitMaxNs / 1e6)
                    val muted = SessionRouter.snapshot.filter { it.owner == SessionRouter.Owner.ENGINE_B_MUTED }.joinToString { it.pkg }
                    EqController.log("capture level: peak=%.4f over %d frames; output queued=%.1f ms, underruns=%d, DSP=%.1f%%, muted=[%s], otherPlayers=%d, source=%s capturedFrames=%d generatedSilenceFrames=%d".format(levelPeak, levelFrames, stats!!.queuedMs, stats!!.underruns, stats!!.dspPercent, muted, otherActivePlayers,
                        if (allowed.isEmpty()) "none(no source admitted: output is generated silence)" else if (input == null) "waiting for recorder lease" else "captured", capturedFrames, generatedFrames))
                    EqController.log("capture timing: readWaitMaxMs=%.1f dspMaxMs=%.2f writeWaitMaxMs=%.1f overBudgetBlocks=%d blockMs=%.2f cushionTopUps=%d".format(readWaitMaxNs / 1e6, dspMaxNs / 1e6, writeWaitMaxNs / 1e6, overBudgetBlocks, frames * 1000.0 / rate, cushionTopUps))
                    levelPeak = 0f; outputPeak = 0f; levelFrames = 0; capturedFrames = 0; generatedFrames = 0; dspNanos = 0; processedFrames = 0
                    readWaitMaxNs = 0L; dspMaxNs = 0L; writeWaitMaxNs = 0L; overBudgetBlocks = 0; cushionTopUps = 0
                }
            }
        } catch (e: Exception) {
            EqController.log("capture: audio loop failed: $e")
        } finally {
            epochRunning.set(false)
            app.svan.listening.ProofRecorder.stop()
            runCatching { getSystemService(AudioManager::class.java).unregisterAudioPlaybackCallback(playbackCallback) }
            activeRecord = null
            record?.let { runCatching { it.stop() }; runCatching { it.release() } }
            recorderLease?.close()
            track?.let { runCatching { it.pause(); it.flush(); it.stop() }; it.release() }
            eqWatcher?.join(200)
            synchronized(engineLock) { current = null; engine?.close() }
            stats = null
            epoch = null
            unmaskSnapshot = null
            app.svan.listening.ClipRecorder.captureChanged(null)
        }
        return EpochExit.STOP
    }

    private fun openTrack(rate: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        require(min > 0) { "unsupported output format ($min)" }
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(maxOf(min, rate * OUTPUT_CAPACITY_MS / 1000 * 8))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).setTransferMode(AudioTrack.MODE_STREAM).build().also {
                if (it.state != AudioTrack.STATE_INITIALIZED) { it.release(); error("output not initialized") }
            }
    }

    @SuppressLint("MissingPermission")
    private fun startRecord(mp: MediaProjection, allowed: Set<Int>, rate: Int, lease: CaptureRecorderGate.Lease): AudioRecord {
        var opened: AudioRecord? = null
        try {
            opened = openRecord(mp, allowed, rate)
            check(opened.sampleRate == rate) { "capture client format differs from requested $rate Hz" }
            opened.startRecording()
            check(opened.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "capture did not start" }
            if (allowed.isNotEmpty()) app.svan.diag.EngineTrace.mark(app.svan.diag.EngineTrace.Mark.RECORDER_STARTED, "${allowed.size} UID(s) at $rate Hz")
            return opened
        } catch (e: Exception) {
            opened?.let { runCatching { it.stop() }; runCatching { it.release() } }
            lease.close()
            throw e
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(mp: MediaProjection, allowed: Set<Int>, rate: Int): AudioRecord {
        // An empty route list must yield silence, not an unrestricted capture.
        // Our own output opts out of capture in the manifest.
        val config = CaptureCompat.playbackConfig(mp, if (allowed.isEmpty()) listOf(Process.myUid()) else allowed)
        val minIn = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        require(minIn > 0) { "unsupported capture format ($minIn)" }
        return AudioRecord.Builder()
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build())
            // Capacity protects scheduling stalls; actual accumulated capture backlog is not yet measured.
            .setBufferSizeInBytes(maxOf(minIn, rate * CAPTURE_BACKLOG_MS / 1000 * 8))
            .setAudioPlaybackCaptureConfig(config).build().also {
                if (it.state != AudioRecord.STATE_INITIALIZED) { it.release(); error("capture not initialized") }
            }
    }

    private fun buildEngine(s: AudioSettings, rate: Int, spatialCapable: Boolean = s.spatialMode != SpatialMode.FAST,
        lab: app.svan.lab.CaptureLabControls? = null): NativeEngine =
        NativeEngine(
            rate, 2,
            oversample = s.oversampleAt(rate),
            stopbandDb = s.quality.stopbandDb,
            // Dither "off" means no word-length reduction at all: float goes straight out.
            ditherBits = if (s.dither == DitherChoice.OFF) 0 else s.outputBits,
            ditherMode = s.dither.nativeMode,
            autoHeadroom = s.effectiveFor(SvanRepository.eq.value).autoHeadroom,
            gainProtection = s.effectiveFor(SvanRepository.eq.value).gainProtection,
            spatialResidual = spatialCapable,
            lab = lab,
        ).also {
            it.setSpatialMode(s.spatialMode)
            it.setAnalysis(true) // Svaramanas listens to the source (preallocated mid/side analysis every 85 ms)
            applyEq(it, SvanRepository.eq.value)
            it.setBassUnmask(if (s.experimentalBassUnmask && SvanRepository.eq.value.enabled) 1.0 else 0.0)
        }

    private fun applyEq(engine: NativeEngine, eq: EqState, previous: EqState? = null) {
        // Preserve limiter history through adaptation; resetting it would release
        // attenuation abruptly every time Svaresa publishes a new curve.
        if (previous == null || eq.dynamicEq != previous.dynamicEq) engine.setDynamicEq(eq.dynamicEq)
        val bands = eq.effectiveBands()
        if (previous == null || bands != previous.effectiveBands()) engine.setBands(bands.map { it.toNative() })
        if (previous == null || eq.effectivePreampDb() != previous.effectivePreampDb()) engine.setPreampDb(eq.effectivePreampDb())
        if (previous == null || eq.bassCharacter != previous.bassCharacter || eq.bass.crossoverHz != previous.bass.crossoverHz)
            engine.setBassCharacter(eq.bassCharacter, eq.bass.crossoverHz)
        if (previous == null || eq.bassResolve != previous.bassResolve) engine.setBassResolve(eq.bassResolve)
        val v = eq.activeVocal
        val i = eq.activeInstrument
        if (previous == null || v != previous.activeVocal || i != previous.activeInstrument) engine.setStereoTuner(v.intimacy, v.warmth, v.smoothness, i.space, i.instruments, i.backingVocals, i.spatialDetail)
        val smart = eq.activeSmart
        val before = previous?.activeSmart
        if (previous == null || smart?.groundingRestraint != before?.groundingRestraint || smart?.groundingBody != before?.groundingBody) {
            engine.setGrounding(smart?.groundingRestraint ?: 0.0, smart?.groundingBody ?: 0.0)
            // Bass texture follows the house body weight: the bass gains odd harmonics where the voicing asks for weight.
            engine.setBassTexture(smart?.groundingBody ?: 0.0)
            // The sustained-shrill guard follows the same restraint weight as the transient restraint.
            engine.setShrillGuard(smart?.groundingRestraint ?: 0.0)
        }
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
        val inputPeakDb: Double, val outputPeakDb: Double, val detailedMix: Double)

    companion object {
        val startupMessage = kotlinx.coroutines.flow.MutableStateFlow("")
        val recoveryMessage = kotlinx.coroutines.flow.MutableStateFlow("")
        private val engineLock = Any()
        private val nextEpoch = java.util.concurrent.atomic.AtomicLong(0)
        @Volatile var epoch: CaptureEpoch? = null
            private set
        @Volatile var rateFacts: RateFacts? = null
            private set
        @Volatile private var current: NativeEngine? = null
        @Volatile private var unmaskSnapshot: DoubleArray? = null

        /** What Svaramanas heard (packed SourceFeatures), or null when Engine B isn't running. */
        fun analysis(): DoubleArray? = synchronized(engineLock) { current?.analysis() }
        fun bassUnmaskDiagnostics(): DoubleArray? = unmaskSnapshot?.copyOf()

        @Volatile var stats: Stats? = null
            private set
        private const val TAG = "CaptureService"
        private const val CHANNEL = "capture"
        private const val NOTIF_ID = 1
        private const val SILENCE_FAILOPEN_S = 4
        private const val STALL_FAILOPEN_S = 12
        private const val OUTPUT_CUSHION_MS = 80
        private const val OUTPUT_CAPACITY_MS = 240
        private const val CAPTURE_BACKLOG_MS = 250
        const val ACTION_STOP = "app.svan.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_QUALITY = "quality"

        @Volatile var isRunning = false
            private set

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
