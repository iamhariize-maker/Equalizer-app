package app.svan.diag

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.SystemClock
import app.svan.BuildConfig
import app.svan.CaptureService
import app.svan.CaptureUidPolicyReport
import app.svan.DetectionMonitor
import app.svan.EqController
import app.svan.PlaybackSessions
import app.svan.SessionReceiver
import app.svan.SessionRouter
import app.svan.SvanRepository
import app.svan.SystemEqService
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * End-to-end diagnostic for one player on one phone: environment, detection, effects, the audio server's own
 * view, and a live capture lab. It exists because the same symptom ("the player is not processed") has many
 * causes that differ by phone and by Android skin, and only measurements on that phone can tell them apart.
 *
 * It is read-only by default. It never uploads anything; the report stays on the device until the user shares it.
 */
object DiagnosticEngine {
    enum class Stage { IDLE, RUNNING, DONE }

    /** [watchSec]: how long a running audiophile engine is observed live (it produces a window every 2 s) before the report is built. */
    data class Request(val pkg: String, val runLab: Boolean = true, val includeDisruptive: Boolean = false, val startDelaySec: Int = 0,
                       val watchSec: Int = 6)

    class Result(val facts: DiagFacts, val findings: List<Finding>, val summary: String, val full: String,
                 val json: JSONObject, val atMs: Long, val file: File?)

    data class State(val stage: Stage = Stage.IDLE, val progress: String = "", val result: Result? = null, val error: String? = null)

    private val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable.asStateFlow()
    @Volatile private var running = false

    /** Starts a run on its own thread so it survives the screen being left. False if one is already running. */
    @Synchronized
    fun start(context: Context, request: Request): Boolean {
        if (running) return false
        running = true
        val app = context.applicationContext
        mutable.value = State(Stage.RUNNING, "Starting")
        Thread({
            try {
                val result = collect(app, request) { mutable.value = State(Stage.RUNNING, it) }
                mutable.value = State(Stage.DONE, "Finished", result)
            } catch (e: Throwable) {
                EqController.log("diagnostic: failed: $e")
                mutable.value = State(Stage.DONE, "Failed", null, "${e.javaClass.simpleName}: ${e.message}")
            } finally {
                running = false
            }
        }, "svan-diag").apply { isDaemon = true; start() }
        return true
    }

    /** Blocking. Used by [start] and by the scripted test command. */
    fun collect(context: Context, request: Request, progress: (String) -> Unit): Result {
        val pkg = request.pkg
        SignalLedger.record(SignalLedger.Kind.SERVICE, "diagnostic run for $pkg (lab=${request.runLab}, disruptive=${request.includeDisruptive})")

        progress("Reading the phone, its build and Svan's permissions")
        val props = EnvProbe.buildProps()
        val env = EnvProbe.collect(context, props)
        val target = EnvProbe.target(context, pkg)

        progress("Testing Svan's own announcement receiver")
        val selfTest = receiverSelfTest(context)

        progress("Reading detection state")
        val uid = target.uid ?: -1
        val routes = SessionRouter.snapshot.filter { it.pkg == pkg }
        val evidence = SessionRouter.evidence.values.filter { it.session.uid == uid || it.session.packageName == pkg }
        val primary = evidence.firstOrNull { it.session.state == "started" } ?: evidence.firstOrNull()
        val sid = routes.firstOrNull()?.sessionId ?: primary?.session?.sessionId
        val events = SignalLedger.snapshot()
        val announcements = AnnouncementAnalysis.summarize(events, pkg)
        val status = DetectionMonitor.status.value
        val detection = DetectionFacts(
            announcements = announcements,
            otherAnnouncers = AnnouncementAnalysis.announcers(events).filterKeys { it != pkg },
            routes = routes.map { RouteFacts(it.sessionId, it.owner.name, it.playing, SessionRouter.reasonFor(it.pkg)?.name) },
            audioServiceState = primary?.session?.state,
            audioServiceUsage = primary?.session?.usage,
            audioServiceFlags = primary?.session?.flags,
            playerType = primary?.session?.playerType?.ifEmpty { null },
            path = primary?.pathLabel?.ifEmpty { null },
            publicActiveMedia = DetectionMonitor.publicActiveCount(context),
            playbackRecordLines = if (uid >= 0) PlaybackRecords.forUid(PlaybackSessions.report.value.allConfigLines, uid) else emptyList(),
            reportsReadable = if (env.reportAccess) (status.playersOk || status.serverOk) else null,
            receiverSelfTestRan = selfTest.first,
            receiverSelfTestMs = selfTest.second,
        )

        val fx = DetectionMonitor.lastAfSnapshot?.effects?.filter { it.sessionId == sid }.orEmpty()
        val effects = EffectFacts(
            attachedHere = sid != null && sid in EqController.globalEq.attachedSessions,
            healthy = sid?.let { EqController.globalEq.isHealthy(it) },
            verification = sid?.let { status.verification[it]?.name },
            sessionEffectNames = fx.map { "${it.name} (enabled=${it.enabled}, suspended=${it.suspended}, clients=${it.clientPids})" },
            installedOemEffects = EnvProbe.oemEffects(EnvProbe.installedEffects()),
        )

        progress("Reading the audio server's view of this player")
        var policyTable = emptyList<String>()
        var flingerStatic = emptyList<String>()
        val policy = if (env.reportAccess && uid >= 0) {
            val read = runCatching { PlaybackSessions.readService("media.audio_policy", 2_500L, 2 * 1024 * 1024) }.getOrNull()
            val text = read?.text
            if (text != null) {
                policyTable = PolicyDump.capturePolicyTable(text)
                PolicyFacts(true, null, PolicyDump.forUid(text, uid), CaptureUidPolicyReport.parse(text)?.policies?.get(uid))
            } else PolicyFacts(false, read?.error ?: "unreadable", emptyList(), null)
        } else PolicyFacts(false, if (uid < 0) "target uid unknown" else "enhanced reports are off", emptyList(), null)
        if (env.reportAccess && sid != null) {
            flingerStatic = runCatching {
                PlaybackSessions.readService("media.audio_flinger", 3_500L, 3 * 1024 * 1024, keepPartial = true).text
                    ?.let { FlingerView.forSession(it, sid) }.orEmpty()
            }.getOrDefault(emptyList())
        }

        val lab = if (!request.runLab) LabFacts(false, "not requested", null, null, null, emptyList(), null, emptyList(), emptyList())
        else {
            if (request.startDelaySec > 0) {
                for (left in request.startDelaySec downTo 1) {
                    progress("Capture trials start in $left s. Switch to ${target.label ?: pkg} now and keep it playing.")
                    SystemClock.sleep(1_000)
                }
            }
            CaptureLab.run(context, pkg, uid, CaptureLab.Options(request.includeDisruptive), progress)
        }

        if (CaptureService.isRunning && request.watchSec > 0) {
            for (left in request.watchSec downTo 1) {
                progress("Watching the live audiophile engine ($left s)")
                SystemClock.sleep(1_000)
            }
        }

        progress("Evaluating")
        val facts = DiagFacts(env, target, detection, effects, policy, lab)
        val now = System.currentTimeMillis()
        val engine = engineFacts(now)
        val stages = Pipeline.evaluate(facts, engine)
        val findings = (DiagRules.evaluate(facts, now) + Pipeline.findings(stages))
            .sortedWith(compareByDescending<Finding> { it.severity }.thenBy { it.code })
        val extras = DiagExtras(
            generatedAtMs = now,
            svanVersion = BuildConfig.VERSION_NAME,
            device = deviceLines(),
            buildProps = props,
            audioFacts = EnvProbe.audioFacts(context),
            outputDevices = EnvProbe.outputDevices(context),
            installedEffects = EnvProbe.installedEffects(),
            setupSummary = EnvProbe.setupSummary(),
            notificationsEnabled = EnvProbe.notificationsEnabled(context),
            processUptimeMs = EnvProbe.processUptimeMs(),
            routeEvidence = evidence.map {
                val s = it.session
                "session ${s.sessionId} ${s.state} ${s.usage} flags=0x${s.flags.toString(16)} source=${it.source} path=${it.pathLabel.ifEmpty { "?" }} " +
                    "devices=${it.devices.ifEmpty { "?" }} type=${s.playerType.ifEmpty { "?" }} content=${s.contentType} pid=${it.pid}"
            },
            captureReport = runCatching { SessionRouter.captureReport(pkg) }.getOrNull(),
            scanSummary = "scans=${DetectionMonitor.scans} health=${status.health} headline=\"${status.headline}\"",
            policyTable = policyTable,
            flingerStatic = flingerStatic,
            ledger = events,
            svanLog = synchronized(EqController.log) { EqController.log.toString() }.lines().takeLast(120),
            pipeline = stages,
            engineEvents = EngineTrace.events(),
            engineSamples = EngineTrace.samples(),
            engineRunStartedMs = engine.runStartedMs,
            unobservable = Pipeline.unobservable(facts),
            allRoutes = SessionRouter.snapshot.map { r ->
                "${r.pkg} session ${r.sessionId} uid ${r.uid} → ${r.owner.name} playing=${r.playing}" +
                    (SessionRouter.reasonFor(r.pkg)?.let { " reason=${it.name}" } ?: "")
            },
            previousRun = EngineTrace.previousRun,
        )
        val summary = DiagReport.summary(facts, findings, extras)
        val full = DiagReport.full(facts, findings, extras)
        val json = DiagReport.json(facts, findings, extras)
        val file = save(context, full, json)
        return Result(facts, findings, summary, full, json, now, file)
    }

    private fun engineFacts(now: Long) = EngineFacts(
        nowMs = now,
        systemEffectsOnly = SvanRepository.settings.value.engineMode == app.svan.model.EngineMode.SYSTEM_ONLY,
        captureRunning = CaptureService.isRunning,
        runStartedMs = EngineTrace.runStartedMs,
        marks = EngineTrace.marks(),
        restarts = EngineTrace.restarts,
        events = EngineTrace.events(),
        samples = EngineTrace.samples(),
        startupMessage = CaptureService.startupMessage.value,
        recoveryMessage = CaptureService.recoveryMessage.value,
        rateSummary = CaptureService.rateFacts?.summary(),
        previousRun = EngineTrace.previousRun,
    )

    private fun deviceLines(): List<String> = buildList {
        add("${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        add("brand ${Build.BRAND} · hardware ${Build.HARDWARE} · board ${Build.BOARD}")
        add("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · security patch ${Build.VERSION.SECURITY_PATCH}")
        add("build ${Build.DISPLAY}")
        add("fingerprint ${Build.FINGERPRINT}")
        if (Build.VERSION.SDK_INT >= 31) add("SoC ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        add("ABIs ${Build.SUPPORTED_ABIS.joinToString()}")
    }

    /**
     * Sends Svan a test announcement addressed to itself. If it arrives, broadcasts can reach the receiver on this
     * phone, so a player's silence is the player's. Returns (ran, milliseconds or null when it never arrived).
     */
    private fun receiverSelfTest(context: Context): Pair<Boolean, Long?> {
        if (!SystemEqService.isRunning) return false to null
        val token = java.lang.Long.toString(System.nanoTime(), 36)
        val began = SystemClock.elapsedRealtime()
        context.sendBroadcast(Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            .setPackage(context.packageName).putExtra(SessionReceiver.EXTRA_SELF_TEST, token))
        while (SystemClock.elapsedRealtime() - began < 1_500) {
            if (SignalLedger.snapshot().any { it.kind == SignalLedger.Kind.SELFTEST && it.detail == "token=$token" })
                return true to (SystemClock.elapsedRealtime() - began)
            SystemClock.sleep(25)
        }
        return true to null
    }

    private fun save(context: Context, full: String, json: JSONObject): File? = runCatching {
        val dir = File(context.filesDir, "diag").apply { mkdirs() }
        dir.listFiles()?.filter { it.name.startsWith("svan-diagnostic") }?.forEach { it.delete() } // only the latest report is kept
        File(dir, "svan-diagnostic.json").writeText(json.toString(2))
        File(dir, "svan-diagnostic.txt").apply { writeText(full) }
    }.getOrNull()
}
