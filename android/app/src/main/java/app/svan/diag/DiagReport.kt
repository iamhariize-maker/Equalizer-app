package app.svan.diag

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything in a report that is raw text rather than a typed fact. */
data class DiagExtras(
    val generatedAtMs: Long,
    val svanVersion: String,
    val device: List<String>,
    val buildProps: Map<String, String>,
    val audioFacts: Map<String, String>,
    val outputDevices: List<String>,
    val installedEffects: List<String>,
    val setupSummary: String,
    val notificationsEnabled: Boolean?,
    val processUptimeMs: Long,
    val routeEvidence: List<String>,
    val captureReport: String?,
    val scanSummary: String,
    val policyTable: List<String>,
    val flingerStatic: List<String>,
    val ledger: List<SignalLedger.Event>,
    val svanLog: List<String>,
)

/** Text and JSON renderings of one diagnostic run. Pure: no Android calls, so it is unit-tested. */
object DiagReport {
    const val FORMAT = 1

    private fun stamp(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
    private fun clock(ms: Long) = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))
    private fun hex(v: Int?) = v?.let { "0x" + it.toString(16) } ?: "unknown"
    private fun dur(ms: Long?) = ms?.let { if (it < 90_000) "${it / 1000} s" else "${it / 60_000} min" } ?: "unknown"

    private fun trialLine(t: TrialResult): String {
        val outcome = when {
            !t.started -> "NOT STARTED (${t.error ?: "unknown"})"
            t.heardAudio -> "HEARD AUDIO (peak ${"%.4f".format(t.peak)}, first at ${t.firstAudioMs} ms)"
            else -> "silent"
        }
        return "${t.id.padEnd(24)} $outcome | frames=${t.frames} | ${t.scope}, usage ${t.usages}, ${t.format}" +
            (t.disruption?.let { " | $it" } ?: "")
    }

    private fun findingBlock(f: Finding, withAdvice: Boolean = true): List<String> = buildList {
        add("[${f.severity.name}] ${f.code}: ${f.title}")
        f.evidence.forEach { add("    - $it") }
        if (withAdvice) f.advice.forEach { add("    > $it") }
    }

    private fun identity(f: DiagFacts, x: DiagExtras): List<String> {
        val t = f.target
        return listOf(
            "Phone: ${x.device.firstOrNull() ?: "unknown"} · Android API ${f.env.sdk} · ${f.env.oem.family}",
            "Svan ${x.svanVersion} · service ${if (f.env.serviceRunning) "running ${dur(f.env.serviceUptimeMs)}" else "NOT running"}" +
                " · enhanced reports: ${if (f.env.reportAccess) f.env.reportRoute ?: "yes" else "off"}" +
                " · developer options: ${f.env.developerOptionsOn ?: "unknown"}",
            "Target: ${t.label ?: t.pkg} ${t.versionName ?: ""} (${t.pkg}, uid ${t.uid ?: "?"}, ${t.installer ?: "install source unknown"})".replace("  ", " "),
        )
    }

    /** Short enough to paste into a chat message. Verdict first, then the evidence that matters. */
    fun summary(f: DiagFacts, findings: List<Finding>, x: DiagExtras): String = buildString {
        appendLine("SVAN DIAGNOSTIC SUMMARY (format $FORMAT) · ${stamp(x.generatedAtMs)}")
        identity(f, x).forEach { appendLine(it) }
        appendLine()
        appendLine("VERDICT: ${DiagRules.headline(findings)}")
        appendLine()
        val shown = findings.filter { it.severity >= Severity.WARN }
        if (shown.isEmpty()) appendLine("No warnings or failures.")
        shown.forEach { fnd -> findingBlock(fnd).forEach { appendLine(it) } }
        val others = findings.filter { it.severity < Severity.WARN }
        if (others.isNotEmpty()) appendLine("Other notes: " + others.joinToString("; ") { "${it.code} (${it.severity.name})" })
        if (f.lab.ran) {
            appendLine()
            appendLine("Capture ladder (player ${when (f.lab.targetPlayingBefore) { true -> "was playing"; false -> "was NOT playing"; null -> "state unknown" }}):")
            f.lab.trials.forEach { appendLine("  " + trialLine(it)) }
            f.lab.livePolicy?.clients?.firstOrNull()?.let { c ->
                appendLine("Audio server view while capturing: attributes=${c.attributesLine ?: "?"}")
                appendLine("  secondary outputs (capture mixes): ${if (c.hasSecondaryLine) c.secondaryOutputs.ifEmpty { listOf("none") } else listOf("line absent")}")
            }
        }
        appendLine()
        appendLine("(Full report has the raw audio-server lines, signal timeline and build properties.)")
    }

    fun full(f: DiagFacts, findings: List<Finding>, x: DiagExtras): String = buildString {
        appendLine("SVAN FULL DIAGNOSTIC (format $FORMAT) · ${stamp(x.generatedAtMs)}")
        identity(f, x).forEach { appendLine(it) }
        appendLine()
        appendLine("== VERDICT ==")
        appendLine(DiagRules.headline(findings))
        appendLine()
        appendLine("== FINDINGS (${findings.size}) ==")
        findings.forEach { fnd -> findingBlock(fnd).forEach { appendLine(it) } }

        appendLine()
        appendLine("== DEVICE AND BUILD ==")
        x.device.forEach { appendLine(it) }
        appendLine("OEM family: ${f.env.oem.family} (aggressive background limits typical: ${f.env.oem.aggressiveBackground})")
        x.audioFacts.forEach { (k, v) -> appendLine("$k: $v") }
        appendLine("Output devices:")
        x.outputDevices.forEach { appendLine("  - $it") }
        appendLine("Registered audio effects (${x.installedEffects.size}):")
        x.installedEffects.forEach { appendLine("  - $it") }

        appendLine()
        appendLine("== SVAN, PERMISSIONS AND BACKGROUND LIMITS ==")
        val e = f.env
        appendLine("record-audio permission: ${e.recordAudioGranted} · app-op: ${e.recordAudioOp}")
        appendLine("DUMP granted: ${e.dumpGranted} · Shizuku installed/running/permitted: ${e.shizukuInstalled}/${e.shizukuRunning}/${e.shizukuPermitted}")
        appendLine("Enhanced report route: ${e.reportRoute ?: "none"}")
        appendLine("Developer options: ${e.developerOptionsOn} · USB debugging: ${e.adbEnabled} · wireless debugging: ${e.wirelessAdbEnabled}")
        appendLine("Battery optimisation ignored: ${e.batteryOptimizationIgnored} · background restricted: ${e.backgroundRestricted} · standby bucket: ${e.standbyBucket} · power saver: ${e.powerSave}")
        appendLine("run-in-background app-op: ${e.runInBackgroundOp} · notifications enabled: ${x.notificationsEnabled}")
        appendLine("System equalizer service running: ${e.serviceRunning} (uptime ${dur(e.serviceUptimeMs)}) · stopped by Android earlier: ${e.killedByAndroid} · capture engine running: ${e.captureRunning}")
        appendLine("Svan process uptime: ${dur(x.processUptimeMs)} · ledger started ${clock(SignalLedger.processStartMs)}")
        appendLine("Detection setup: ${x.setupSummary}")

        appendLine()
        appendLine("== TARGET APP ==")
        val t = f.target
        appendLine("${t.label ?: "?"} · ${t.pkg} · installed: ${t.installed}")
        appendLine("version ${t.versionName} (code ${t.versionCode}) · target SDK ${t.targetSdk} · uid ${t.uid} · system app: ${t.system} · enabled: ${t.enabled} · stopped state: ${t.stopped}")
        appendLine("install source: ${t.installer ?: "unknown"} · signing certificate (SHA-256 prefix): ${t.signerPrefix ?: "unknown"}")
        appendLine("playback capture in manifest: ${t.manifestSummary ?: "unavailable"}")

        appendLine()
        appendLine("== DETECTION ==")
        val d = f.detection
        val a = d.announcements
        appendLine("Session announcements from ${t.pkg} since ${clock(SignalLedger.processStartMs)}: opens=${a.opens} closes=${a.closes} accepted=${a.accepted} dropped=${a.dropped}" +
            (a.lastOpenMs?.let { " last at ${clock(it)}" } ?: ""))
        a.dropReasons.forEach { appendLine("  dropped: ${it.first} x${it.second}") }
        appendLine("Other apps that announced: ${d.otherAnnouncers.entries.joinToString { "${it.key} x${it.value}" }.ifEmpty { "none" }}")
        appendLine("Receiver self-test: ${if (!d.receiverSelfTestRan) "not run" else d.receiverSelfTestMs?.let { "delivered in $it ms" } ?: "NOT DELIVERED"}")
        appendLine("Routes: ${d.routes.joinToString { "session ${it.sessionId} ${it.owner} playing=${it.playing} reason=${it.reason}" }.ifEmpty { "none" }}")
        appendLine("Android public API: active media players=${d.publicActiveMedia}")
        appendLine("Audio-service view: state=${d.audioServiceState} usage=${d.audioServiceUsage} flags=${hex(d.audioServiceFlags)} type=${d.playerType} path=${d.path}")
        appendLine("Scan: ${x.scanSummary}")
        x.routeEvidence.forEach { appendLine("  $it") }
        if (d.playbackRecordLines.isNotEmpty()) { appendLine("dumpsys audio records for this uid:"); d.playbackRecordLines.forEach { appendLine("  $it") } }

        appendLine()
        appendLine("== EFFECTS ==")
        val fx = f.effects
        appendLine("attached here: ${fx.attachedHere} · healthy: ${fx.healthy} · verification: ${fx.verification}")
        appendLine("Effects on the session (audio server): ${fx.sessionEffectNames.joinToString().ifEmpty { "none listed" }}")
        appendLine("Vendor effects registered: ${fx.installedOemEffects.joinToString().ifEmpty { "none" }}")

        appendLine()
        appendLine("== AUDIO SERVER VIEW (media.audio_policy, before the lab) ==")
        appendPolicy(f.policy, x)

        appendLine()
        appendLine("== CAPTURE LAB ==")
        val l = f.lab
        if (!l.ran) appendLine("Did not run: ${l.skipReason}")
        else {
            appendLine("Player playing before/after: ${l.targetPlayingBefore}/${l.targetPlayingAfter} · other active players: ${l.otherActivePlayers}")
            l.trials.forEach { t ->
                appendLine(trialLine(t))
                appendLine("    state=${t.recorderState} elapsed=${t.elapsedMs} ms recorder-silenced observations=${t.silencedSeen} Svan process=${t.ownImportance}")
                t.notes.forEach { appendLine("    $it") }
            }
            appendLine("Audio server view WHILE a capture recorder was live:")
            l.livePolicy?.let { appendPolicy(it, x) } ?: appendLine("  unavailable (enhanced reports are off)")
            if (l.liveFlingerLines.isNotEmpty()) { appendLine("media.audio_flinger lines for this session and capture taps:"); l.liveFlingerLines.forEach { appendLine("  $it") } }
            if (l.liveMixSections.isNotEmpty()) { appendLine("Registered policy mixes:"); l.liveMixSections.forEach { appendLine(it.prependIndent("  ")) } }
        }

        if (x.flingerStatic.isNotEmpty()) { appendLine(); appendLine("== AUDIO SERVER TRACKS (last scan) =="); x.flingerStatic.forEach { appendLine(it) } }
        x.captureReport?.let { appendLine(); appendLine("== SVAN CAPTURE REPORT =="); appendLine(it) }

        appendLine()
        appendLine("== SIGNAL TIMELINE (oldest first; ${x.ledger.size} events) ==")
        x.ledger.takeLast(160).forEach { appendLine("${clock(it.atMs)} ${it.kind.name.padEnd(9)} ${it.pkg ?: "-"} ${it.detail}") }

        appendLine()
        appendLine("== BUILD PROPERTIES (filtered: ${x.buildProps.size}) ==")
        x.buildProps.forEach { (k, v) -> appendLine("$k=$v") }

        appendLine()
        appendLine("== RECENT SVAN LOG ==")
        x.svanLog.forEach { appendLine(it) }
    }

    private fun StringBuilder.appendPolicy(p: PolicyFacts, x: DiagExtras) {
        if (!p.readable) { appendLine("  not readable: ${p.error ?: "enhanced reports are off"}"); return }
        appendLine("  UID-wide capture policy mask: ${hex(p.uidPolicyMask)}")
        if (p.clients.isEmpty()) appendLine("  no active track client for this uid in the policy")
        p.clients.forEach { c ->
            appendLine("  client port=${c.portId} session=${c.sessionId} uid=${c.uid} active=${c.active}")
            appendLine("    effective attribute flags: ${hex(c.attributeFlags)} (NO_MEDIA_PROJECTION=${c.blocksMediaProjection}, NO_SYSTEM_CAPTURE=${c.blocksSystemCapture}) usage=${c.usage}")
            appendLine("    attached to a capture mix: ${c.attachedToCaptureMix} (secondary outputs ${c.secondaryOutputs})")
            c.raw.forEach { appendLine("      | $it") }
        }
        if (x.policyTable.isNotEmpty()) x.policyTable.forEach { appendLine(it.prependIndent("  ")) }
    }

    fun json(f: DiagFacts, findings: List<Finding>, x: DiagExtras): JSONObject {
        val trials = JSONArray()
        f.lab.trials.forEach { t ->
            trials.put(JSONObject().put("id", t.id).put("scope", t.scope).put("usages", t.usages).put("format", t.format)
                .put("started", t.started).put("heardAudio", t.heardAudio).put("frames", t.frames).put("peak", t.peak.toDouble())
                .put("firstAudioMs", t.firstAudioMs ?: JSONObject.NULL).put("error", t.error ?: JSONObject.NULL)
                .put("silencedSeen", JSONArray(t.silencedSeen.toList())).put("disruption", t.disruption ?: JSONObject.NULL))
        }
        val fnd = JSONArray()
        findings.forEach {
            fnd.put(JSONObject().put("severity", it.severity.name).put("code", it.code).put("title", it.title)
                .put("evidence", JSONArray(it.evidence)).put("advice", JSONArray(it.advice)))
        }
        val c = f.lab.livePolicy?.clients?.firstOrNull()
        return JSONObject()
            .put("format", FORMAT).put("generatedAtMs", x.generatedAtMs).put("svan", x.svanVersion)
            .put("verdict", DiagRules.headline(findings))
            .put("device", JSONArray(x.device)).put("oem", f.env.oem.family).put("sdk", f.env.sdk)
            .put("target", JSONObject().put("pkg", f.target.pkg).put("version", f.target.versionName ?: JSONObject.NULL)
                .put("installer", f.target.installer ?: JSONObject.NULL).put("signer", f.target.signerPrefix ?: JSONObject.NULL))
            .put("reportAccess", f.env.reportAccess).put("developerOptions", f.env.developerOptionsOn ?: JSONObject.NULL)
            .put("announcements", JSONObject().put("opens", f.detection.announcements.opens).put("accepted", f.detection.announcements.accepted)
                .put("dropped", f.detection.announcements.dropped))
            .put("selfTest", JSONObject().put("ran", f.detection.receiverSelfTestRan).put("ms", f.detection.receiverSelfTestMs ?: JSONObject.NULL))
            .put("lab", JSONObject().put("ran", f.lab.ran).put("skipReason", f.lab.skipReason ?: JSONObject.NULL))
            .put("findings", fnd).put("trials", trials)
            .put("liveClient", if (c == null) JSONObject.NULL else JSONObject().put("attributes", c.attributesLine ?: JSONObject.NULL)
                .put("attributeFlags", c.attributeFlags ?: JSONObject.NULL).put("secondaryOutputs", JSONArray(c.secondaryOutputs)))
    }
}
