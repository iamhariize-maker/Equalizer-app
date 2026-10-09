package app.svan.diag

import app.svan.diag.EngineTrace.Cat
import app.svan.diag.EngineTrace.Mark

/** What the live audiophile engine and its flight recorder show right now. Built by [DiagnosticEngine]; pure data. */
data class EngineFacts(
    val nowMs: Long,
    /** Saved engine mode is "system effects only": Engine B is off by choice. */
    val systemEffectsOnly: Boolean,
    val captureRunning: Boolean,
    val runStartedMs: Long?,
    val marks: Map<Mark, Long>,
    val restarts: Int,
    val events: List<EngineTrace.Event>,
    val samples: List<EngineTrace.Sample>,
    val startupMessage: String,
    val recoveryMessage: String,
    val rateSummary: String?,
    val previousRun: EngineTrace.PreviousRun?,
)

enum class StageStatus { PASS, FAIL, WARN, WAITING, SKIPPED, UNKNOWN, NEEDS_ENHANCED }

/** Whether a stage can be checked on every phone, or only when Svan can read the audio server's reports. */
enum class Evidence { ALWAYS, ENHANCED_REPORTS }

/** One link of the chain from "player makes sound" to "processed sound reaches the output". */
data class Stage(
    val id: String,
    val title: String,
    val status: StageStatus,
    val evidence: List<String> = emptyList(),
    val needs: Evidence = Evidence.ALWAYS,
    /** Set on capture-engine stages that should surface as a finding when they fail. */
    val code: String? = null,
    val advice: List<String> = emptyList(),
)

/**
 * The detection chain (D) and the capture chain (C) as an ordered checklist. Each stage says what was observed, from
 * which kind of evidence, and what to do. Detection stages are explained by [DiagRules]; the capture stages carry
 * their own findings, because no other rule looks at the running engine.
 */
object Pipeline {
    private const val SILENT_DB = -119.0
    private const val RECENT_WINDOWS = 5
    private val TRANSIENT_REASONS = setOf("CHECKING", "WAITING_FOR_PLAYBACK", "UID_GROUP_UNSAFE", "RECORDER_BUSY")

    fun evaluate(f: DiagFacts, e: EngineFacts): List<Stage> = detection(f) + capture(f, e)

    fun firstBroken(stages: List<Stage>): Stage? = stages.firstOrNull { it.status == StageStatus.FAIL }

    /** Findings from the capture stages. Failed stages are failures; WARN stages are warnings. */
    fun findings(stages: List<Stage>): List<Finding> = stages.filter { it.code != null && (it.status == StageStatus.FAIL || it.status == StageStatus.WARN) }
        .map { Finding(if (it.status == StageStatus.FAIL) Severity.FAIL else Severity.WARN, it.code!!, "${it.id} ${it.title}", it.evidence, it.advice) }

    /** What this phone's setup cannot show, so a missing finding is never mistaken for a clean bill of health. */
    fun unobservable(f: DiagFacts): List<String> = if (f.env.reportAccess) emptyList() else listOf(
        "Players whose audio session opened before Svan's service started (only announcements made while Svan runs are seen).",
        "Which app owns each audio session and its real state, as the audio server records it.",
        "Whether the audio server really keeps Svan's effect on the session, or another app's effect replaced it.",
        "The audio server's capture flags for the player's stream, and whether it copies the stream into the capture mix.",
        "App-wide capture opt-outs recorded by the audio policy.",
    )

    private fun hex(v: Int?) = v?.let { "0x" + it.toString(16) } ?: "unknown"
    private fun secs(ms: Long) = "%.1f s".format(ms / 1000.0)

    // ------------------------------------------------------------------------------------------ detection

    private fun detection(f: DiagFacts): List<Stage> {
        val d = f.detection
        val a = d.announcements
        val e = f.env
        val routed = d.routes.isNotEmpty()
        val out = mutableListOf<Stage>()

        out += Stage("D1", "Svan's detection service is running",
            if (e.serviceRunning) StageStatus.PASS else StageStatus.FAIL,
            listOf(if (e.serviceRunning) "running for ${(e.serviceUptimeMs ?: 0) / 1000} s" else "not running" + if (e.killedByAndroid) " (stopped by Android)" else ""))

        out += Stage("D2", "Broadcasts reach Svan on this phone", when {
            !e.serviceRunning -> StageStatus.SKIPPED
            !d.receiverSelfTestRan -> StageStatus.UNKNOWN
            d.receiverSelfTestMs != null -> StageStatus.PASS
            else -> StageStatus.FAIL
        }, listOf(when {
            !e.serviceRunning -> "the service is off"
            !d.receiverSelfTestRan -> "the self-test did not run"
            d.receiverSelfTestMs != null -> "Svan's own test announcement arrived in ${d.receiverSelfTestMs} ms"
            else -> "Svan's own test announcement never arrived within 1.5 s"
        }))

        out += Stage("D3", "The player announced an audio session", when {
            a.opens > 0 -> StageStatus.PASS
            routed -> StageStatus.PASS
            (d.publicActiveMedia ?: 0) > 0 -> StageStatus.FAIL
            else -> StageStatus.WAITING
        }, buildList {
            add("announcements from ${f.target.pkg}: opens=${a.opens} closes=${a.closes}")
            if (a.opens == 0 && routed) add("found without an announcement (an enhanced report listed it)")
            if (a.opens == 0 && !routed) {
                add("Android reports ${d.publicActiveMedia ?: "an unknown number of"} active media player(s)")
                if (d.otherAnnouncers.isNotEmpty()) add("other apps did announce: ${d.otherAnnouncers.keys.joinToString()}")
            }
        })

        out += Stage("D4", "Svan accepted the announcement", when {
            a.opens == 0 -> StageStatus.SKIPPED
            a.accepted > 0 -> StageStatus.PASS
            else -> StageStatus.FAIL
        }, buildList {
            add("accepted=${a.accepted} dropped=${a.dropped}")
            a.dropReasons.forEach { add("dropped: ${it.first} x${it.second}") }
        })

        out += Stage("D5", "Enhanced reports list sessions no announcement covers", when {
            !e.reportAccess -> StageStatus.NEEDS_ENHANCED
            d.reportsReadable == false -> StageStatus.FAIL
            d.reportsReadable == true -> StageStatus.PASS
            else -> StageStatus.UNKNOWN
        }, listOf(when {
            !e.reportAccess -> "enhanced reports are off: a session opened before Svan started cannot be found"
            d.reportsReadable == false -> "enhanced reports are on (${e.reportRoute}) but the last scan could not read them"
            d.reportsReadable == true -> "readable via ${e.reportRoute}"
            else -> "no scan has finished yet"
        }), Evidence.ENHANCED_REPORTS)

        out += Stage("D6", "The session was routed to an engine", when {
            routed -> StageStatus.PASS
            a.accepted > 0 || d.reportsReadable == true && d.audioServiceState != null -> StageStatus.FAIL
            else -> StageStatus.SKIPPED
        }, listOf(if (routed) d.routes.joinToString { "session ${it.sessionId} → ${it.owner}" + (it.reason?.let { r -> " ($r)" } ?: "") } else "no route for this player"))

        val owner = d.routes.firstOrNull()?.owner
        out += Stage("D7", "Svan's effect is attached to the session", when {
            !routed -> StageStatus.SKIPPED
            owner == "ENGINE_B_MUTED" -> StageStatus.PASS
            f.effects.attachedHere && f.effects.healthy != false -> StageStatus.PASS
            else -> StageStatus.FAIL
        }, listOf(if (owner == "ENGINE_B_MUTED") "not needed: the audiophile engine carries this player and mutes the original"
        else "attached=${f.effects.attachedHere} healthy=${f.effects.healthy}"))

        out += Stage("D8", "The audio server confirms the effect is processing", when {
            !routed || owner == "ENGINE_B_MUTED" -> StageStatus.SKIPPED
            !e.reportAccess -> StageStatus.NEEDS_ENHANCED
            f.effects.verification == "PROCESSING" -> StageStatus.PASS
            f.effects.verification == null -> StageStatus.UNKNOWN
            else -> StageStatus.FAIL
        }, listOf(if (!e.reportAccess) "needs the audio server's own effect list" else "verification: ${f.effects.verification ?: "not reported"}"),
            Evidence.ENHANCED_REPORTS)
        return out
    }

    // ------------------------------------------------------------------------------------------ capture

    private fun capture(f: DiagFacts, e: EngineFacts): List<Stage> {
        val pkg = f.target.pkg
        val route = f.detection.routes.firstOrNull()
        val onB = route?.owner == "ENGINE_B_MUTED"
        val runStart = e.runStartedMs ?: 0L
        val inRun = e.events.filter { it.atMs >= runStart }
        val mine = inRun.filter { it.pkg == pkg }
        val windows = e.samples.filter { it.atMs >= runStart }
        val recent = windows.filter { pkg in it.mutedPackages }.takeLast(RECENT_WINDOWS)
        val failures = inRun.filter { it.cat == Cat.LIFECYCLE && (it.text.contains("startup failed") || it.text.contains("start blocked") || it.text.contains("audio loop failed")) }
        val out = mutableListOf<Stage>()
        val notRunning = !e.captureRunning
        fun skippedIfIdle(reason: String = "the audiophile engine is not running") = if (notRunning) listOf(reason) else emptyList()

        out += Stage("C1", "The audiophile engine is running", when {
            e.systemEffectsOnly -> StageStatus.SKIPPED
            e.captureRunning -> StageStatus.PASS
            failures.isNotEmpty() || e.startupMessage.isNotBlank() -> StageStatus.FAIL
            else -> StageStatus.WAITING
        }, buildList {
            when {
                e.systemEffectsOnly -> add("engine mode is System effects only")
                e.captureRunning -> add("running since ${secs(e.nowMs - runStart)} ago, ${e.restarts} restart(s)")
                else -> {
                    add("not running")
                    if (e.startupMessage.isNotBlank()) add("start message: ${e.startupMessage}")
                    failures.takeLast(2).forEach { add(it.text) }
                    if (failures.isEmpty() && e.startupMessage.isBlank()) add("start it from Hi-Fi to test the capture chain")
                }
            }
        }, code = "ENGINE_B_START_FAILED", advice = listOf("The reason is the line above. Start the audiophile engine again from Hi-Fi after fixing it."))

        val m = e.marks
        fun at(mark: Mark): String = m[mark]?.let { "+${secs(it - runStart)}" } ?: "not reached"
        val fg = m[Mark.FOREGROUND]; val pr = m[Mark.PROJECTION]
        out += Stage("C2", "Foreground service and capture permission token", when {
            notRunning && pr == null -> StageStatus.SKIPPED
            pr != null -> StageStatus.PASS
            fg != null && e.nowMs - fg > 4_000 -> StageStatus.FAIL
            else -> StageStatus.WAITING
        }, listOf("foreground ${at(Mark.FOREGROUND)} · permission token ${at(Mark.PROJECTION)}") + skippedIfIdle(),
            code = "PROJECTION_NOT_GRANTED",
            advice = listOf("Android refused to hand Svan a capture token. Start the audiophile engine again and accept the screen-capture prompt; on some skins the prompt is blocked while another app overlays the screen."))

        val routed = m[Mark.ROUTING_DONE]
        out += Stage("C3", "Startup routing finished before the first recorder", when {
            notRunning && routed == null -> StageStatus.SKIPPED
            routed != null -> StageStatus.PASS
            pr != null && e.nowMs - pr > 12_000 -> StageStatus.FAIL
            else -> StageStatus.WAITING
        }, listOf("routing done ${at(Mark.ROUTING_DONE)}") + skippedIfIdle(),
            code = "STARTUP_ROUTING_STUCK",
            advice = listOf("Startup waits for the first capture checks. If this stays stuck, the capture check of a playing app never finished: look at the engine timeline for 'capture check' lines."))

        val rejected = inRun.filter { it.text.startsWith("capture: rate") }
        out += Stage("C4", "Recorder and output format negotiated", when {
            m[Mark.RATE_NEGOTIATED] != null -> StageStatus.PASS
            notRunning && rejected.isEmpty() -> StageStatus.SKIPPED
            rejected.isNotEmpty() && notRunning -> StageStatus.FAIL
            else -> StageStatus.WAITING
        }, buildList {
            add("negotiated ${at(Mark.RATE_NEGOTIATED)}")
            e.rateSummary?.let { add(it) }
            rejected.takeLast(4).forEach { add(it.text) }
        }, code = "RECORDER_FORMAT_REJECTED",
            advice = listOf("Every candidate rate was refused by the recorder or output. This points at the audio HAL; send the full report."))

        out += Stage("C5", "The player is on the audiophile engine", when {
            route == null -> StageStatus.SKIPPED
            notRunning -> StageStatus.SKIPPED
            onB -> StageStatus.PASS
            route.reason == "SYSTEM_ONLY" -> StageStatus.SKIPPED
            route.reason in TRANSIENT_REASONS -> StageStatus.WAITING
            else -> StageStatus.FAIL
        }, buildList {
            if (route == null) add("no route for $pkg")
            else add("owner=${route.owner}" + (route.reason?.let { " reason=$it" } ?: ""))
            mine.filter { it.cat == Cat.ROUTE || it.cat == Cat.FAILOPEN }.map { it.text }.distinct().takeLast(3).forEach { add(it) }
        }, code = "TARGET_NOT_ON_ENGINE_B",
            advice = listOf("The reason above is why Svan kept this player on system effects. Playing a different app, or pausing and restarting the track, triggers a new capture check."))

        val probe = mine.lastOrNull { it.cat == Cat.PROBE }
        out += Stage("C6", "Capture check verdict for the player", when {
            probe == null -> if (notRunning) StageStatus.SKIPPED else StageStatus.UNKNOWN
            probe.text.contains("CAPTURABLE") -> StageStatus.PASS
            probe.text.contains("failed for") -> StageStatus.FAIL
            probe.text.contains("silent") -> StageStatus.FAIL
            else -> StageStatus.WAITING
        }, listOf(probe?.text ?: "no capture check has run for this player in this run"),
            code = "CAPTURE_CHECK_NEGATIVE",
            advice = listOf("The check listened to the player and did not hear it (or could not open a recorder). The Capture ladder in this report shows which request, if any, can hear it."))

        val recStart = m[Mark.RECORDER_STARTED]
        val waitingLease = windows.lastOrNull()?.sourceState?.contains("waiting for recorder lease") == true
        out += Stage("C7", "A playback recorder is running for the muted sources", when {
            !onB -> StageStatus.SKIPPED
            recStart != null -> StageStatus.PASS
            else -> if (routed != null && e.nowMs - routed > 6_000) StageStatus.FAIL else StageStatus.WAITING
        }, buildList {
            add("recorder started ${at(Mark.RECORDER_STARTED)}")
            if (waitingLease) add("the engine is waiting for the recorder lease (another capture check holds it)")
        }, code = "RECORDER_NOT_STARTED",
            advice = listOf("The engine routed the player to Engine B but never got a playback recorder. A capture check may be holding the single recorder slot."))

        val gotFrames = recent.any { it.capturedFrames > 0 }
        val noFrameWindows = recent.size >= 2 && recent.none { it.capturedFrames > 0 }
        out += Stage("C8", "Frames arrive from the recorder", when {
            !onB -> StageStatus.SKIPPED
            gotFrames -> StageStatus.PASS
            noFrameWindows || (recent.isEmpty() && recStart != null && e.nowMs - recStart > 8_000) -> StageStatus.FAIL
            else -> StageStatus.WAITING
        }, buildList {
            add("first frame ${at(Mark.FIRST_FRAME)}")
            add("last ${recent.size} window(s): captured frames " + recent.joinToString { it.capturedFrames.toString() }.ifEmpty { "none yet" })
            if (recent.isEmpty() && recStart != null && e.nowMs - recStart > 8_000) add("no 2-second window completed: the recorder delivers nothing at all")
        }, code = "NO_FRAMES_DELIVERED",
            advice = listOf("The recorder is open but the audio server hands it no data. The Capture ladder shows whether another request format receives data.",
                "Svan fails the player back to system effects after 2.5 s of this, so the listener is never left in silence."))

        val heard = recent.any { it.inputPeakDb > SILENT_DB }
        val silencedFlags = recent.mapNotNull { it.clientSilenced }
        out += Stage("C9", "The frames contain sound", when {
            !onB || !gotFrames -> StageStatus.SKIPPED
            heard -> StageStatus.PASS
            recent.size >= 2 -> StageStatus.FAIL
            else -> StageStatus.WAITING
        }, buildList {
            add("first sound ${at(Mark.FIRST_AUDIO)}" + if (m[Mark.FIRST_AUDIO] != null && recStart != null) " (${m[Mark.FIRST_AUDIO]!! - recStart} ms after the recorder started)" else "")
            add("input peak per window (dB): " + recent.joinToString { "%.0f".format(it.inputPeakDb) })
        }, code = "CAPTURE_DELIVERS_SILENCE",
            advice = listOf("Frames flow but every sample is zero while the source is muted. Either the audio is not being tapped, or the muted stream is not the one being captured.",
                "Svan fails the player back after 4 s of this (only when another app is playing, because a paused player looks the same)."))

        out += Stage("C10", "Android is not silencing the recorder", when {
            !onB || recent.isEmpty() -> StageStatus.SKIPPED
            silencedFlags.any { it } -> StageStatus.FAIL
            silencedFlags.isEmpty() -> StageStatus.UNKNOWN
            else -> StageStatus.PASS
        }, listOf("isClientSilenced per window: " + recent.joinToString { it.clientSilenced?.toString() ?: "?" }),
            code = "ENGINE_RECORDER_SILENCED",
            advice = listOf("Android itself reports the recorder is being silenced (background capture limits, or the foreground-service/permission state). Keep Svan out of battery restrictions and keep its notification visible."))

        val failOpens = inRun.count { it.cat == Cat.FAILOPEN && it.text.startsWith("fail-open") && (it.pkg == pkg || it.pkg == null) }
        val lostMute = inRun.any { it.cat == Cat.MUTE }
        out += Stage("C11", "The source stays muted and owned by Svan", when {
            lostMute -> StageStatus.FAIL
            failOpens >= 2 -> StageStatus.FAIL
            failOpens == 1 -> StageStatus.WARN
            !onB -> StageStatus.SKIPPED
            else -> StageStatus.PASS
        }, buildList {
            add("fail-opens for this player this run: $failOpens · lost mute control: $lostMute")
            inRun.filter { it.cat == Cat.FAILOPEN || it.cat == Cat.MUTE }.takeLast(3).forEach { add(it.text) }
        }, code = if (lostMute) "MUTE_CONTROL_LOST" else if (failOpens >= 2) "FAIL_OPEN_REPEATED" else "FAIL_OPEN_HAPPENED",
            advice = listOf(if (lostMute) "Another app took over the player's effect, so Svan stopped the audiophile engine to avoid an echo. Turn off other equalizer apps."
            else "Svan handed the player back to system effects because capture went silent or stopped. Repeats back off for longer each time."))

        val dspMax = recent.maxOfOrNull { it.dspPercent }
        val over = recent.sumOf { it.overBudgetBlocks }
        out += Stage("C12", "The DSP keeps up with real time", when {
            !onB || recent.isEmpty() -> StageStatus.SKIPPED
            (dspMax ?: 0.0) > 90 || over > 20 -> StageStatus.FAIL
            (dspMax ?: 0.0) > 70 || over > 0 -> StageStatus.WARN
            else -> StageStatus.PASS
        }, listOf("DSP load max ${dspMax?.let { "%.0f".format(it) }}% · blocks over budget $over · read wait max ${recent.maxOfOrNull { it.readWaitMaxMs }?.let { "%.1f".format(it) }} ms"),
            code = "DSP_OVERLOADED",
            advice = listOf("Choose Efficient quality, or a lighter spatial mode; this phone's CPU cannot run the selected chain in real time."))

        // Output health matters whether or not a player is carried: an idle engine that underruns will not carry one.
        val outWindows = windows.takeLast(RECENT_WINDOWS)
        val underrunsGrow = outWindows.zipWithNext().count { (a, b) -> b.underruns > a.underruns }
        val writeFailed = inRun.any { it.text.contains("output write failed") }
        out += Stage("C13", "The output plays without gaps", when {
            writeFailed -> StageStatus.FAIL
            outWindows.isEmpty() -> StageStatus.SKIPPED
            underrunsGrow >= 3 -> StageStatus.FAIL
            underrunsGrow > 0 -> StageStatus.WARN
            else -> StageStatus.PASS
        }, listOf("underruns " + outWindows.joinToString { it.underruns.toString() } + " · queued ms " + outWindows.joinToString { "%.0f".format(it.queuedMs) }) +
            (if (outWindows.lastOrNull()?.sourceState == "no source admitted") listOf("no player was being carried: the engine was only playing silence") else emptyList()) +
            inRun.filter { it.cat == Cat.OUTPUT }.map { it.text }.distinct().takeLast(2),
            code = if (writeFailed) "OUTPUT_WRITE_FAILED" else "OUTPUT_UNDERRUNS",
            advice = listOf("Gaps in the output: the audio thread is being starved. Svan raises its buffer first, then returns to system effects. Battery restrictions and background limits are the usual cause.",
                "If this happens with nothing carried, the selected capture quality is more than this phone sustains: try Efficient quality."))

        val previous = e.previousRun
        val killed = previous != null && !previous.cleanStop && previous.events.any { it.cat == Cat.LIFECYCLE && it.text.startsWith("capture run") }
        out += Stage("C14", "The engine runs without restarts or being killed", when {
            killed -> StageStatus.WARN
            e.restarts >= 2 -> StageStatus.FAIL
            e.restarts == 1 -> StageStatus.WARN
            else -> StageStatus.PASS
        }, buildList {
            add("restarts this run: ${e.restarts}")
            inRun.filter { it.text.contains("reopening") }.takeLast(3).forEach { add(it.text) }
            if (e.recoveryMessage.isNotBlank()) add("last recovery message: ${e.recoveryMessage}")
            if (killed) add("the previous Svan process ended while a capture run was active, without a clean stop (saved ${previous!!.savedAtMs}): the system probably killed it")
        }, code = if (killed && e.restarts == 0) "PREVIOUS_RUN_KILLED" else "ENGINE_RESTARTING",
            advice = listOf(if (killed && e.restarts == 0) "Android ended Svan while it was capturing. Remove Svan from battery restrictions and lock it in the recents list."
            else "The engine had to reopen with the safe format. The recovery message above says why."))
        return out
    }
}
