package app.svan

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import java.text.DateFormat
import java.util.Date

/**
 * One text report that answers "why isn't my player processed?" without guessing: what the phone
 * is, what Svan may do, what each Android report contained, and what Svan decided. It stays on the
 * device until the user chooses to copy or share it, and contains no audio and no account data.
 */
object DiagnosticReport {
    fun build(context: Context): String = buildString {
        val st = DetectionMonitor.status.value
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
        appendLine("Requested spatial mode: ${SvanRepository.settings.value.spatialMode}")
        appendLine("Capture epoch: ${CaptureService.epoch ?: "none"}")
        appendLine(CaptureService.rateFacts?.summary() ?: "Capture/client rates unavailable; original source rate=unknown; DAC rate=unknown")
        appendLine("Capture measurements: ${CaptureService.stats ?: "none"}")
        appendLine("Capture recovery: ${CaptureService.recoveryMessage.value.ifBlank { "none" }}")
        appendLine()
        appendLine("== Verdict ==")
        appendLine("${st.health}: ${st.headline}")
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
}
