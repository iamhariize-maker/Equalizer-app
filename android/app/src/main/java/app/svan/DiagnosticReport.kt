package app.svan

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * One text report that answers "why isn't my player processed?" without guessing: what the phone
 * is, what Svan may do, what each Android report contained, and what Svan decided. It stays on the
 * device until the user chooses to copy or share it. It names player apps and output devices, but
 * contains no audio, no track titles, no account data and no full hardware addresses (masked below).
 */
object DiagnosticReport {
    /** Hardware addresses (for example Bluetooth MACs) keep only their last byte. */
    private val hardwareAddress = Regex("""\b(?:[0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})\b""")
    fun maskHardwareAddresses(text: String): String =
        hardwareAddress.replace(text) { "**:**:**:**:**:${it.groupValues[1]}" }

    fun build(context: Context): String = maskHardwareAddresses(buildRaw(context))

    private fun buildRaw(context: Context): String = buildString {
        val st = DetectionMonitor.status.value
        val eq = SvanRepository.eq.value
        val settings = SvanRepository.settings.value
        val pm = context.packageManager
        val info = runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
        appendLine("Svan diagnostic report — ${DateFormat.getDateTimeInstance().format(Date())}")
        appendLine("Svan ${info?.versionName ?: "?"} (code ${info?.longVersionCode ?: "?"})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Build: ${Build.DISPLAY}")
        appendLine()
        appendLine("== Permissions and background ==")
        appendLine("Audio-report access (app grant or shell): ${st.dumpPermission}")
        appendLine(DetectionSetup.diagnostics())
        appendLine("RECORD_AUDIO: ${context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED}")
        appendLine("Battery optimisation ignored: ${runCatching { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }.getOrNull()}")
        appendLine("System equalizer service running: ${SystemEqService.isRunning} · last started ${SystemEqService.lastStartedMs(context).let { if (it == 0L) "never" else DateFormat.getTimeInstance().format(Date(it)) }}")
        appendLine("Stopped by Android (not by Svan): ${SystemEqService.wasKilledByAndroid(context)}")
        appendLine("Capture engine running: ${CaptureService.isRunning}")
        appendLine("Requested spatial mode: ${settings.spatialMode.title}")
        appendLine("Capture epoch: ${CaptureService.epoch ?: "none"}")
        appendLine(CaptureService.rateFacts?.summary() ?: "Capture/client rates unavailable; original source rate=unknown; DAC rate=unknown")
        appendLine("Capture measurements: ${CaptureService.stats ?: "none"}")
        appendLine("Capture recovery: ${CaptureService.recoveryMessage.value.ifBlank { "none" }}")
        appendLine()
        appendLine("== Current EQ and controller snapshot ==")
        appendLine("Saved/requested snapshot; this does not prove these settings reached a particular player's signal path.")
        appendLine("EQ enabled=${eq.enabled} · workspace=${eq.workspaceMode} · preset=${eq.presetName} · requested preamp=${db(eq.preampDb)} dB · current effective preamp=${db(eq.effectivePreampDb())} dB")
        appendLine("Saved manual curve (${eq.manualBands().size} bands): ${formatBands(eq.manualBands())}")
        val effectiveBands = eq.effectiveBands()
        appendLine("Current effective curve (${effectiveBands.size} bands): ${formatBands(effectiveBands).ifBlank { "none (EQ off)" }}")
        appendLine("Headphone correction layer requested=${eq.tuning?.enabled == true} · bands=${eq.tuning?.takeIf { it.enabled }?.bands?.size ?: 0} (model name omitted)")
        val activeVocal = eq.activeVocal
        val activeInstrument = eq.activeInstrument
        appendLine("Bass requested: level=${db(eq.bass.amountDb)} dB focus=${hz(eq.bass.focusHz)} Hz character=${number(eq.bass.character)} Resolve=${pct(eq.bass.resolve)}%${if (eq.bass.resolveAuto) " Auto" else " Manual"}; effective while EQ ${if (eq.enabled) "on" else "off"}: level=${db(if (eq.enabled) eq.bass.amountDb else 0.0)} dB character=${number(eq.bassCharacter)} Resolve=${pct(eq.bassResolve)}%")
        appendLine("Vocal tuner requested: intimacy=${pct(eq.vocal.intimacy)}% warmth=${pct(eq.vocal.warmth)}% smoothness=${pct(eq.vocal.smoothness)}%; current effective: intimacy=${pct(activeVocal.intimacy)}% warmth=${pct(activeVocal.warmth)}% smoothness=${pct(activeVocal.smoothness)}%")
        appendLine("Instrument tuner requested: space=${number(eq.instrument.space)} instruments=${pct(eq.instrument.instruments)}% backingVocals=${pct(eq.instrument.backingVocals)}% spatialDetail=${pct(eq.instrument.spatialDetail)}%; current effective: space=${number(activeInstrument.space)} instruments=${pct(activeInstrument.instruments)}% backingVocals=${pct(activeInstrument.backingVocals)}% spatialDetail=${pct(activeInstrument.spatialDetail)}%")
        val smart = eq.smart
        appendLine("Latest controller snapshot: layer=${if (smart == null) "none" else "published"} · active=${eq.activeSmart != null} · bypass=${eq.smartBypass} · enabled=${eq.enabled}" +
            (smart?.let { " · smart bands=${it.bands.size} · preamp trim=${db(if (eq.activeSmart != null) it.preampDb else 0.0)} dB · dynamic EQ requested=${pct(it.dynamicEq)}% effective=${pct(eq.dynamicEq)}% · space=${number(it.space)} · instruments=${pct(it.instruments)}% · protectEngine=${it.protectEngine}" } ?: ""))
        appendLine("Capture DSP delay=${CaptureService.epoch?.let { "${it.latencyFrames} frames @ ${it.sampleRate} Hz (%.2f ms)".format(Locale.US, it.latencyFrames * 1000.0 / it.sampleRate) } ?: "unavailable"}; this excludes capture, platform mixing and output transport.")
        appendLine("Spatial pipeline available at capture start=${CaptureService.epoch?.let { it.detailed.toString() } ?: "unavailable"} · applied mode=${CaptureService.epoch?.appliedSettings?.spatialMode?.title ?: "none"} · current Detailed blend=${CaptureService.stats?.let { "%.0f%%".format(Locale.US, it.detailedMix * 100.0) } ?: "unavailable"}. The blend is a live controller value, not a quality score.")
        appendLine()
        appendLine("== Verdict ==")
        appendLine("${st.health}: ${st.headline}")
        appendLine("Enhanced audio-report access: ${if (st.dumpPermission) "available" else "unavailable"}")
        appendLine("Basic route snapshot at last scan (${st.basicRoutes.size}): ${st.basicRoutes.joinToString { "${it.packageName} ${it.engine} playing=${it.playing ?: "unknown"}" }.ifBlank { "none" }}")
        if (st.advice.isNotBlank()) appendLine(st.advice)
        appendLine("Scans so far: ${DetectionMonitor.scans} · last scan ${if (st.atMs == 0L) "never" else DateFormat.getTimeInstance().format(Date(st.atMs))}")
        appendLine("Player list (dumpsys audio): ${if (!st.dumpPermission) "NOT REQUESTED: enhanced report access unavailable" else if (st.playersOk) "ok" else "FAILED ${st.playersError ?: ""}"}")
        appendLine("Audio server (dumpsys media.audio_flinger): ${if (!st.dumpPermission) "NOT REQUESTED: enhanced report access unavailable" else if (st.serverOk) "ok${if (st.serverPartial) " (partial)" else ""}" else "FAILED ${st.serverError ?: ""}"}")
        appendLine("Android public API says active players: ${st.publicActive ?: "unavailable"}")
        appendLine("== Sessions Svan found (${st.sessions.size}) ==")
        st.sessions.forEach {
            val s = it.session
            appendLine("- ${s.packageName} uid=${s.uid} pid=${it.pid} session=${s.sessionId} ${s.state} ${s.usage} flags=0x${s.flags.toString(16)} " +
                "source=${it.source} path=${it.pathLabel.ifEmpty { "?" }} devices=${it.devices.ifEmpty { "?" }} type=${s.playerType.ifEmpty { "?" }} " +
                "content=${s.contentType} excluded=${MusicSourcePolicy.exclusion(s) ?: "no"} " +
                "verify=${st.verification[s.sessionId] ?: "n/a"}")
        }
        if (st.unresolved.isNotEmpty()) {
            appendLine("Players listed by Android with no attachable session:")
            st.unresolved.forEach { appendLine("- ${it.packageName} uid=${it.uid} pid=${it.pid} type=${it.playerType.ifEmpty { "?" }} ${it.state}") }
        }
        appendLine()
        appendLine("== Svan routes ==")
        SessionRouter.snapshot.forEach { appendLine("- ${it.pkg} session=${it.sessionId} ${it.owner} playing=${it.playing}") }
        appendLine("Capture verdicts: ${SessionRouter.compat().all()}")
        appendLine("Shared output: ${SharedOutput.status.value}")
        appendLine("Recently closed connections (not current/capture authority): ${SessionRouter.recentConnections}")
        appendLine()
        appendLine("== Output devices (public API) ==")
        runCatching {
            context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .forEach { appendLine("- type=${it.type} ${it.productName} ${it.address}".trimEnd()) }
        }
        appendLine()
        appendLine("== Global (session 0) effect probe ==")
        appendLine(EqController.globalEq.probeGlobalMix())
        appendLine()
        appendLine("== Android player records (dumpsys audio) ==")
        val cfg = PlaybackSessions.report.value.allConfigLines
        if (cfg.isEmpty()) appendLine("(none)") else cfg.forEach { appendLine(it.take(400)) }
        appendLine()
        appendLine("== Audio server excerpt (dumpsys media.audio_flinger) ==")
        appendLine(DetectionMonitor.lastAfExcerpt.ifBlank { "(none)" })
        appendLine()
        appendLine("== Recent Svan log ==")
        val lines = synchronized(EqController.log) { EqController.log.toString() }.lines().takeLast(80)
        appendLine(lines.joinToString("\n"))
        appendLine("Own pid/uid: ${Process.myPid()}/${Process.myUid()}")
    }

    private fun db(value: Double): String = "%.2f".format(Locale.US, value)
    private fun hz(value: Double): String = "%.1f".format(Locale.US, value)
    private fun pct(value: Double): String = "%.0f".format(Locale.US, value * 100.0)
    private fun number(value: Double): String = "%.2f".format(Locale.US, value)
    private fun formatBands(bands: List<app.svan.model.Band>): String = bands.joinToString { band ->
        "${band.type}@${hz(band.freqHz)}Hz ${db(band.gainDb)}dB Q=${db(band.q)}${if (band.enabled) "" else " off"}"
    }.ifBlank { "none" }
}
