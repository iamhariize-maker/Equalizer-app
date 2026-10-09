package app.svan.diag

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Process
import android.os.SystemClock
import app.svan.CaptureCompat
import app.svan.CaptureUidPolicyReport
import app.svan.DetectionMonitor
import app.svan.PlaybackSessions
import app.svan.SessionRouter
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class Disruption { NONE, EFFECT_OFF, MUTED }

/** One capture attempt: which audio the recorder asks Android for, and in what format. */
class TrialSpec(
    val id: String,
    val title: String,
    /** Usage rules, or null for none (the capture then matches any capturable usage). */
    val usages: IntArray?,
    val allApps: Boolean,
    val encoding: Int,
    val rate: Int,
    val disruption: Disruption = Disruption.NONE,
) {
    val usageText: String get() = usages?.joinToString("+") { usageName(it) } ?: "none"
    val formatText: String get() = (if (encoding == AudioFormat.ENCODING_PCM_16BIT) "int16 " else "float32 ") + rate + " Hz"

    private fun usageName(u: Int) = when (u) {
        AudioAttributes.USAGE_MEDIA -> "MEDIA"
        AudioAttributes.USAGE_GAME -> "GAME"
        AudioAttributes.USAGE_UNKNOWN -> "UNKNOWN"
        else -> "usage $u"
    }
}

/** What the audio server showed while a capture recorder was live. */
class LiveView(val policy: PolicyFacts?, val flinger: List<String>, val mixes: List<String>, val error: String?)

/**
 * Runs a ladder of capture attempts against one app and records, for each, whether any audio arrived. The ladder
 * is arranged so the pattern of results points at a cause (usage rule, UID filter, format, ordering, or the audio
 * server not attaching the stream at all). Trials are audible and non-destructive unless [Options.includeDisruptive].
 */
object CaptureLab {
    data class Options(val includeDisruptive: Boolean, val trialMs: Long = 3_500L)

    fun ladder(includeDisruptive: Boolean): List<TrialSpec> {
        val f32 = AudioFormat.ENCODING_PCM_FLOAT
        val media = intArrayOf(AudioAttributes.USAGE_MEDIA)
        val all3 = intArrayOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN)
        return buildList {
            add(TrialSpec(TrialIds.UID_MEDIA_F32, "This app, MEDIA usage (Svan 0.5.10 check)", media, false, f32, 48_000))
            add(TrialSpec(TrialIds.UID_ANY_F32, "This app, no usage rule (0.5.5/0.5.6 check)", null, false, f32, 48_000))
            add(TrialSpec(TrialIds.UID_ALL3_F32, "This app, MEDIA+GAME+UNKNOWN (0.5.5 capture)", all3, false, f32, 48_000))
            add(TrialSpec(TrialIds.UID_MEDIA_S16, "This app, MEDIA, 16-bit", media, false, AudioFormat.ENCODING_PCM_16BIT, 48_000))
            add(TrialSpec(TrialIds.UID_MEDIA_F32_44K, "This app, MEDIA, 44.1 kHz", media, false, f32, 44_100))
            add(TrialSpec(TrialIds.ALL_MEDIA_F32, "Every app's MEDIA audio except Svan", media, true, f32, 48_000))
            add(TrialSpec(TrialIds.ALL_ANY_F32, "Every app, any usage, except Svan", null, true, f32, 48_000))
            if (includeDisruptive) {
                add(TrialSpec(TrialIds.UID_ANY_EFFECT_OFF, "This app, no usage rule, Svan effect detached", null, false, f32, 48_000, Disruption.EFFECT_OFF))
                add(TrialSpec(TrialIds.UID_ANY_MUTED, "This app, no usage rule, muted first (0.5.6 order)", null, false, f32, 48_000, Disruption.MUTED))
            }
        }
    }

    fun run(context: Context, pkg: String, uid: Int, options: Options, progress: (String) -> Unit): LabFacts {
        val route = SessionRouter.snapshot.firstOrNull { it.pkg == pkg && it.uid == uid }
        val playingBefore = route?.playing
        fun skipped(reason: String) = LabFacts(false, reason, playingBefore, null, null, emptyList(), null, emptyList(), emptyList())
        if (uid < 0) return skipped("The app's UID is unknown.")
        SessionRouter.labBusyReason()?.let { return skipped(it) }
        val mp = SessionRouter.labProjection() ?: return skipped("No capture permission.")
        val specs = ladder(options.includeDisruptive && route != null)
        val results = mutableListOf<TrialResult>()
        var live: LiveView? = null
        val helper = Executors.newSingleThreadExecutor { r -> Thread(r, "svan-diag-live").apply { isDaemon = true } }
        SessionRouter.labActive = true
        try {
            specs.forEachIndexed { i, spec ->
                progress("Capture trial ${i + 1} of ${specs.size}: ${spec.title}")
                val sid = route?.sessionId ?: 0
                val wantLive = live == null
                var pending: java.util.concurrent.Future<LiveView>? = null
                val onMidpoint = {
                    if (wantLive && pending == null) pending = helper.submit(Callable { liveView(context, uid, sid) })
                }
                val result = when (spec.disruption) {
                    Disruption.NONE -> trial(context, mp, spec, uid, options.trialMs, onMidpoint)
                    else -> SessionRouter.labDisrupted(sid, mute = spec.disruption == Disruption.MUTED) {
                        trial(context, mp, spec, uid, options.trialMs, onMidpoint)
                    } ?: failedTrial(spec, "Could not prepare this test: the app is not on system effects right now.")
                }
                results += result
                if (wantLive && result.started) live = runCatching { pending?.get(6, TimeUnit.SECONDS) }.getOrNull()
                SystemClock.sleep(600) // let Android tear the previous capture mix down before the next one
            }
        } finally {
            SessionRouter.labActive = false
            helper.shutdownNow()
        }
        val after = SessionRouter.snapshot.firstOrNull { it.pkg == pkg && it.uid == uid }?.playing
        val others = DetectionMonitor.publicActiveCount(context)?.let { (it - 1).coerceAtLeast(0) }
        return LabFacts(true, null, playingBefore, after, others, results, live?.policy, live?.flinger.orEmpty(), live?.mixes.orEmpty())
    }

    private fun failedTrial(spec: TrialSpec, error: String) = TrialResult(
        spec.id, spec.title, if (spec.allApps) "all apps except Svan" else "target app", spec.usageText, spec.formatText,
        started = false, error = error, frames = 0, nonZeroSamples = 0, peak = 0f, firstAudioMs = null, elapsedMs = 0,
        silencedSeen = emptySet(), recorderState = -1, disruption = spec.disruption.name)

    /** One capture attempt. Nonblocking reads, so the deadline is real even if the recorder supplies nothing. */
    private fun trial(context: Context, mp: MediaProjection, spec: TrialSpec, uid: Int, durationMs: Long, onMidpoint: () -> Unit): TrialResult {
        var record: AudioRecord? = null
        var frames = 0L
        var nonZero = 0L
        var peak = 0f
        var firstMs: Long? = null
        var started = false
        var state = -1
        var error: String? = null
        val silenced = linkedSetOf<String>()
        val notes = mutableListOf<String>()
        val importance = EnvProbe.ownImportance(context)
        val began = SystemClock.elapsedRealtime()
        val gate = runCatching { CaptureCompat.recorders.await("diagnostic lab", 8_000) { true } }
        if (gate.isFailure) return failedTrial(spec, "The playback recorder is occupied: ${gate.exceptionOrNull()?.message}")
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(mp).apply {
                spec.usages?.forEach { addMatchingUsage(it) }
                if (spec.allApps) excludeUid(Process.myUid()) else addMatchingUid(uid)
            }.build()
            val format = AudioFormat.Builder().setEncoding(spec.encoding).setSampleRate(spec.rate)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
            @Suppress("MissingPermission") // RECORD_AUDIO is checked before the engine can run
            val rec = AudioRecord.Builder().setAudioFormat(format).setAudioPlaybackCaptureConfig(config).build()
            record = rec
            check(rec.state == AudioRecord.STATE_INITIALIZED) { "recorder not initialised" }
            rec.startRecording()
            state = rec.recordingState
            started = state == AudioRecord.RECORDSTATE_RECORDING
            check(started) { "recorder did not start (state $state)" }
            val floats = FloatArray(1024)
            val shorts = ShortArray(1024)
            val t0 = SystemClock.elapsedRealtime()
            var lastConfigPoll = 0L
            var midpointFired = false
            while (SystemClock.elapsedRealtime() - t0 < durationMs) {
                val now = SystemClock.elapsedRealtime() - t0
                val n = if (spec.encoding == AudioFormat.ENCODING_PCM_16BIT) rec.read(shorts, 0, shorts.size, AudioRecord.READ_NON_BLOCKING)
                    else rec.read(floats, 0, floats.size, AudioRecord.READ_NON_BLOCKING)
                if (n < 0) { error = "read failed ($n)"; break }
                frames += n / 2
                for (i in 0 until n) {
                    val v = if (spec.encoding == AudioFormat.ENCODING_PCM_16BIT) shorts[i] / 32768f else floats[i]
                    if (!v.isFinite()) continue
                    if (v != 0f) { nonZero++; if (firstMs == null) firstMs = now }
                    val a = kotlin.math.abs(v)
                    if (a > peak) peak = a
                }
                if (now - lastConfigPoll >= 250) {
                    lastConfigPoll = now
                    val cfg = runCatching { rec.activeRecordingConfiguration }.getOrNull()
                    silenced += cfg?.isClientSilenced?.toString() ?: "no-config"
                    if (now >= 1000 && notes.none { it.startsWith("recording config") } && cfg != null) {
                        notes += "recording config: clientSource=${runCatching { cfg.clientAudioSource }.getOrNull()} " +
                            "silenced=${cfg.isClientSilenced} format=${cfg.format.sampleRate} Hz/${cfg.format.channelMask}"
                    }
                }
                if (!midpointFired && now >= 1000) { midpointFired = true; onMidpoint() }
                Thread.sleep(5)
            }
        } catch (e: Exception) {
            error = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            record?.let { runCatching { it.stop() }; runCatching { it.release() } }
            gate.getOrNull()?.close()
        }
        return TrialResult(
            spec.id, spec.title, if (spec.allApps) "all apps except Svan" else "uid $uid", spec.usageText, spec.formatText,
            started, error, frames, nonZero, peak, firstMs, SystemClock.elapsedRealtime() - began, silenced, state,
            spec.disruption.takeIf { it != Disruption.NONE }?.name, importance, notes)
    }

    /** The audio server's own books, read while a capture recorder is live. */
    private fun liveView(context: Context, uid: Int, sessionId: Int): LiveView {
        if (!PlaybackSessions.hasReportAccess(context)) return LiveView(null, emptyList(), emptyList(), "Enhanced reports are not enabled.")
        val policyRead = runCatching { PlaybackSessions.readService("media.audio_policy", 2_500L, 2 * 1024 * 1024) }.getOrNull()
        val text = policyRead?.text
        val policy = if (text != null) PolicyFacts(true, null, PolicyDump.forUid(text, uid), CaptureUidPolicyReport.parse(text)?.policies?.get(uid))
            else PolicyFacts(false, policyRead?.error ?: "unreadable", emptyList(), null)
        val flingerRead = runCatching { PlaybackSessions.readService("media.audio_flinger", 3_500L, 3 * 1024 * 1024, keepPartial = true) }.getOrNull()
        val flinger = flingerRead?.text?.let { FlingerView.forSession(it, sessionId) + FlingerView.captureTaps(it) }.orEmpty()
        return LiveView(policy, flinger, text?.let { PolicyDump.mixSections(it) }.orEmpty(), null)
    }
}
