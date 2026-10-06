package app.svan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.svan.model.QualityMode
import app.svan.ui.SvanApp
import app.svan.ui.SvanTheme
import kotlin.concurrent.thread
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Quality forced by a scripted start_capture; null = the saved setting. */
    private var pendingQuality: QualityMode? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(0xFF0C0A08.toInt()), // Svan.Black: warm charcoal
        )
        super.onCreate(savedInstanceState)
        val priorSound = getSharedPreferences("svan", MODE_PRIVATE)
        val onboarding = OnboardingAndroid.prefs(this)
        val freshDefaults = FirstRunPolicy.needsFlatDefault(onboarding.getBoolean("defaults_checked", false),
            priorSound.contains("eq") || priorSound.contains("settings") || priorSound.contains("presets"),
            priorSound.getBoolean("smartEqDefaultApplied", false))
        SvanRepository.init(this)
        // Use the existing complete Flat action only on a brand-new install.
        // Updates, restores and saved user sound are never reset by onboarding.
        if (freshDefaults) SvanRepository.resetSound()
        onboarding.edit().putBoolean("defaults_checked", true).apply()
        SessionRouter.init(this)
        DetectionSetup.init(this)
        SystemEqService.startIfEnabled(this)

        val dump = checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED
        EqController.log("DUMP permission: ${if (dump) "granted" else "not granted (adb shell pm grant $packageName android.permission.DUMP)"}")

        setContent {
            SvanTheme {
                SvanApp(
                    onStartCapture = { pendingQuality = null; startCapture() },
                    onStopCapture = ::stopCapture,
                    labActions = listOf(
                        "Blind listening" to { app.svan.listening.BlindLab.open.value=true },
                        "Run engine checks" to { thread { runCatching { app.svan.listening.QualityLab.verify(this) }.onFailure { EqController.log("QUALITY_LAB_FAILED ${it.message}") } } },
                        "Probe band limits" to { thread { EqController.log(DynamicsProbe.run(this)) } },
                        "Audible resolution" to ::runResolutionProbe,
                        "Sessions" to { thread { EqController.log(sessionReport()) } },
                        "Forget capture verdicts" to { SessionRouter.compat().clear(); EqController.log("Capture verdicts cleared") },
                    ),
                )
            }
        }
        handleCommand(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCommand(intent)
    }

    override fun onResume() {
        super.onResume()
        DetectionSetup.refresh()
        SystemEqService.refreshDetection(this)
        if (app.svan.svaramanas.Svaramanas.bubble.value) app.svan.svaramanas.SvaramanasBubbleService.start(this)
    }

    /**
     * Scriptable entry points for automated tests (output goes to logcat tag EqSpike):
     *   adb shell am start -n app.svan/.MainActivity --es cmd <command> [--es quality EFFICIENT]
     * Commands: probe, resolution, sessions, preset, start_capture, stop_capture,
     *           measure_mix, forget_verdicts
     */
    private fun handleCommand(intent: Intent?) {
        if (!BuildConfig.PHONE_PREVIEW) return
        val cmd = intent?.getStringExtra("cmd") ?: return
        intent.getStringExtra("quality")?.let { pendingQuality = QualityMode.valueOf(it) }
        EqController.log("CMD $cmd")
        when (cmd) {
            "onboarding_state" -> {
                val state = OnboardingAndroid.working(this)
                val snapshot = OnboardingAndroid.wizard(this)
                val data = org.json.JSONObject()
                    .put("kind", state.kind.name).put("prompt", state.promptKey(OnboardingAndroid.dismissed(this)) != null)
                    .put("dump", snapshot.dumpGranted).put("wizard", snapshot.step.name)
                    .put("usb", snapshot.debugging.usb.name).put("wireless", snapshot.debugging.wireless.name)
                    .put("system", SystemEqService.isRunning).put("capture", CaptureService.isRunning)
                    .put("engineMode", SvanRepository.settings.value.engineMode.name)
                    .put("preset", SvanRepository.eq.value.presetName).put("preamp", SvanRepository.eq.value.preampDb)
                    .put("smart", app.svan.svaramanas.Svaramanas.request.value.enabled)
                java.io.File(filesDir, "onboarding-state.json").writeText(data.toString())
                EqController.log("ONBOARDING_STATE_READY")
            }
            "onboarding_fixture" -> {
                if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                    app.svan.ui.OnboardingUi.fixture.value = intent.getStringExtra("fixture")
                    app.svan.ui.OnboardingUi.panel.value = app.svan.ui.HelpPanel.DETECTION
                }
            }
            "onboarding_close" -> {
                if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                    app.svan.ui.OnboardingUi.fixture.value = null
                    app.svan.ui.OnboardingUi.panel.value = app.svan.ui.HelpPanel.NONE
                }
            }
            "onboarding_reset_prompts" -> OnboardingAndroid.resetPrompts(this)
            "blind_lab" -> app.svan.listening.BlindLab.open.value=true
            "quality_lab" -> thread { runCatching {app.svan.listening.QualityLab.verify(this)}.onFailure {EqController.log("QUALITY_LAB_FAILED ${it.message}")} }
            "probe" -> thread { EqController.log(DynamicsProbe.run(this)) }
            "resolution" -> runResolutionProbe()
            "sessions" -> thread { EqController.log(sessionReport()) }
            "preset" -> {
                val n = EqController.loadPreset(EqController.SAMPLE_PRESET)
                val r = EqController.curveEngine.responseDb(doubleArrayOf(1000.0))[0]
                EqController.log("preset bands=$n response@1kHz=%.2f dB".format(r))
            }
            "reset_sound" -> SvanRepository.resetSound()
            "state" -> EqController.log("EQ_STATE " + SvanRepository.eq.value.toJson().toString())
            "setup_detection" -> DetectionSetup.enable()
            "refresh_detection" -> SystemEqService.refreshDetection(this)
            "gain_settings" -> SvanRepository.updateSettings {
                it.copy(autoHeadroom = intent.getBooleanExtra("headroom", true), gainProtection = intent.getBooleanExtra("protection", true))
            }
            "eq_band" -> SvanRepository.editEq {
                it.copy(mode = app.svan.model.EqMode.PARAMETRIC, bands = listOf(app.svan.model.Band(freqHz = intent.getFloatExtra("frequency", 1000f).toDouble(), gainDb = intent.getFloatExtra("gain", 0f).toDouble())), preampDb = 0.0, tuning = null, bass = app.svan.model.BassTuner(), vocal = app.svan.model.VocalTuner(), instrument = app.svan.model.InstrumentTuner())
            }
            "eq_control" -> {
                SvanRepository.setSmartEqControl(intent.getBooleanExtra("auto",true))
                SvanRepository.setEqMode(if (intent.getBooleanExtra("graphic",false)) app.svan.model.EqMode.GRAPHIC else app.svan.model.EqMode.PARAMETRIC,
                    intent.getIntExtra("count",31).takeIf { it in app.svan.model.GraphicLayout.COUNTS } ?: 31)
                val state=SvanRepository.eq.value
                EqController.log("eq control: auto=${state.smartEqControl} mode=${state.workspaceMode} appliedBands=${state.smart?.bands?.size} manualBands=${state.manualBands().size} " +
                    "response@1kHz=%.2f dB fitRms=%.3f".format(EqController.curveEngine.responseDb(doubleArrayOf(1000.0))[0],state.smart?.graphicFitRmsDb ?: 0.0))
            }
            "eq_workspace" -> {
                val s=SvanRepository.eq.value
                val o=org.json.JSONObject().put("state",s.toJson())
                    .put("protectionRequested",SvanRepository.settings.value.toJson())
                    .put("protectionEffective",SvanRepository.settings.value.effectiveFor(s).toJson())
                    .put("smartProtection",s.smartProtection)
                    .put("smartBands",org.json.JSONArray().apply { s.smart?.bands?.forEach { put(it.toJson()) } })
                    .put("appliedBands",org.json.JSONArray().apply { s.effectiveBands().forEach { put(it.toJson()) } })
                    .put("smartPreamp",s.smart?.preampDb ?: 0.0)
                    .put("response",EqController.curveEngine.responseDb(doubleArrayOf(1000.0))[0])
                // A 31/64-band report exceeds logcat's per-message limit. Keep the
                // complete diagnostic in app-private storage; debug tests use run-as.
                java.io.File(filesDir,"eq-workspace.json").writeText(o.toString())
                EqController.log("EQ_WORKSPACE_READY")
            }
            "eq_personal_gain" -> SvanRepository.adjustSmartEq(intent.getIntExtra("index",0),intent.getFloatExtra("gain",0f).toDouble())
            "eq_undo" -> SvanRepository.undoEq()
            "graphic_test" -> SvanRepository.editEq {
                it.copy(mode = app.svan.model.EqMode.GRAPHIC, graphicCount = 10,
                    graphicGains = List(10) { band -> if (band == 5) 6.0 else 0.0 }, preampDb = 0.0,
                    tuning = null, bass = app.svan.model.BassTuner(), vocal = app.svan.model.VocalTuner(), instrument = app.svan.model.InstrumentTuner())
            }
            "bypass" -> SvanRepository.update { it.copy(enabled = !intent.getBooleanExtra("off", true)) }
            "app_engine" -> {
                val pkg = intent.getStringExtra("pkg") ?: return
                SessionRouter.appPreferences().setSystemOnly(pkg, intent.getBooleanExtra("system_only", false))
            }
            "engine_mode" -> SvanRepository.updateSettings {
                it.copy(engineMode = if (intent.getBooleanExtra("system_only", false)) app.svan.model.EngineMode.SYSTEM_ONLY else app.svan.model.EngineMode.AUTO)
            }
            "start_system" -> SystemEqService.start(this)
            "stop_system" -> SystemEqService.stop(this)
            "test_drop_system_effects" -> {
                // Emulator regression: mimic lost effects without closing the player's
                // session. This fault-injection command is disabled in release APKs.
                if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                    EqController.globalEq.releaseAll()
                    EqController.log("test: system effects dropped; waiting for automatic recovery")
                }
            }
            "test_blind_reports" -> {
                // Debug-only: pretend one Android report is unreadable (--ez players true --ez server true).
                if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                    DetectionMonitor.debugBlindPlayers = intent.getBooleanExtra("players", false)
                    DetectionMonitor.debugBlindServer = intent.getBooleanExtra("server", false)
                    EqController.log("test: blind player list=${DetectionMonitor.debugBlindPlayers} audio server=${DetectionMonitor.debugBlindServer}")
                    SystemEqService.requestScanNow()
                }
            }
            "start_capture" -> {
                SvanRepository.updateSettings { it.copy(engineMode = app.svan.model.EngineMode.AUTO) }
                startCapture()
            }
            "stop_capture" -> stopCapture()
            "measure_mix" -> thread {
                EqController.log(try { MixMeter.measure(intent.getFloatExtra("seconds", 3f).toDouble()) } catch (e: Exception) { "MIX error $e" })
            }
            "forget_verdicts" -> SessionRouter.compat().clear()
            "diag_capture" -> {
                // --es pkg <package>: capture that app unmuted vs muted (Engine B must be running)
                val pkg = intent.getStringExtra("pkg") ?: return
                val uid = runCatching { packageManager.getApplicationInfo(pkg, 0).uid }.getOrDefault(-1)
                val route = SessionRouter.snapshot.firstOrNull { it.pkg == pkg }
                if (uid < 0 || route == null) EqController.log("DIAG no route for $pkg (uid=$uid)")
                else SessionRouter.diagnose(uid, route.sessionId)
            }
            "bass" -> {
                // --es preset "Punchy": apply a bass-tuner preset (both engines)
                val name = intent.getStringExtra("preset") ?: "Off"
                val preset = app.svan.model.BassTuner.PRESETS.firstOrNull { it.first == name }?.second ?: return
                SvanRepository.update { it.copy(bass = preset) }
                val r = EqController.curveEngine.responseDb(doubleArrayOf(1000.0))[0]
                EqController.log("bass preset=$name bands=${preset.bands().size} response@1kHz=%.2f dB".format(r))
            }
            "tuners" -> {
                // --ef intimacy/warmth/smooth/space/instruments <value>: set the vocal tuner and orchestral amplifier
                fun f(k: String) = intent.getFloatExtra(k, 0f).toDouble()
                SvanRepository.update {
                    it.copy(
                        vocal = app.svan.model.VocalTuner(f("intimacy"), f("warmth"), f("smooth")),
                        instrument = app.svan.model.InstrumentTuner(f("space"), f("instruments")),
                    )
                }
                val r = EqController.curveEngine.responseDb(doubleArrayOf(1000.0))[0]
                EqController.log("tuners vocal=${SvanRepository.eq.value.vocal} inst=${SvanRepository.eq.value.instrument} response@1kHz=%.2f dB".format(r))
            }
            "tune" -> {
                // --es query "Sennheiser HD 650" [--es source oratory1990] [--es sig HARMAN]: fetch from AutoEq and apply
                val q = intent.getStringExtra("query") ?: return
                val src = intent.getStringExtra("source")
                val sig = app.svan.tuning.Signature.valueOf(intent.getStringExtra("sig") ?: "HARMAN")
                kotlinx.coroutines.MainScope().launch {
                    val entry = runCatching {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            app.svan.tuning.AutoEqSource.search(this@MainActivity, q).firstOrNull { src == null || it.source == src }
                        }
                    }.getOrElse { EqController.log("tune: index failed: $it"); return@launch }
                    if (entry == null) { EqController.log("tune: no match for $q"); return@launch }
                    app.svan.tuning.TuningController.build(this@MainActivity,
                        app.svan.tuning.TuningController.Request(entry, sig, 0.0, 0.0, 64))
                        .onSuccess { t ->
                            SvanRepository.update { it.copy(tuning = t) }
                            val r = EqController.curveEngine.responseDb(doubleArrayOf(1000.0))[0]
                            EqController.log("tune: ${t.headphone} (${t.source}) -> ${t.signature}: ${t.bands.size} bands rms=%.2f dB response@1kHz=%.2f dB".format(t.fitRmsDb, r))
                        }
                        .onFailure { EqController.log("tune: failed: $it") }
                }
            }
            // Svaramanas: --ez on true --es mode SVARESA --es feel BRIGHT --es picks VOCALS,GUITARS --ef strength 1.0
            "svaramanas" -> {
                val mode = intent.getStringExtra("mode")?.let { m -> app.svan.svaramanas.SmartMode.entries.firstOrNull { it.name == m.uppercase() } }
                val feel = intent.getStringExtra("feel")?.let { f -> app.svan.svaramanas.Feel.entries.firstOrNull { it.name == f.uppercase() } }
                val picks = intent.getStringExtra("picks")?.split(',')?.mapNotNull { n ->
                    app.svan.svaramanas.Category.entries.firstOrNull { it.name == n.trim().uppercase() }
                }
                val strength = intent.getFloatExtra("strength", -1f)
                val night = intent.getStringExtra("night")?.let { n -> app.svan.svaramanas.NightMode.entries.firstOrNull { it.name == n.uppercase() } }
                app.svan.svaramanas.Svaramanas.update { r ->
                    r.copy(
                        enabled = intent.getBooleanExtra("on", true), mode = mode ?: r.mode, feel = feel ?: r.feel, picks = picks ?: r.picks,
                        strength = if (strength >= 0) strength.toDouble() else r.strength,
                        night = night ?: r.night,
                        volumeAware = if (intent.hasExtra("volume_aware")) intent.getBooleanExtra("volume_aware", true) else r.volumeAware,
                        routeAware = if (intent.hasExtra("route_aware")) intent.getBooleanExtra("route_aware", true) else r.routeAware,
                        autoHeadphone = if (intent.hasExtra("auto_headphone")) intent.getBooleanExtra("auto_headphone", true) else r.autoHeadphone,
                    )
                }
            }
            "svaramanas_panel" -> app.svan.svaramanas.SvaramanasActivity.open(this)
            "dump_lines" -> thread {
                PlaybackSessions.query(this)
                EqController.log("DUMP size=${PlaybackSessions.lastDumpSize} err=${PlaybackSessions.lastError}\n${PlaybackSessions.lastConfigLines}")
            }
        }
    }

    private fun runResolutionProbe() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            EqController.log("Grant microphone permission (needed by Visualizer), then run again.")
            return
        }
        EqController.log("Measuring… (~1 min, plays tones)")
        thread { EqController.log(ResolutionProbe.timeBulkSet(this) + ResolutionProbe.run(this)) }
    }

    private fun sessionReport(): String = buildString {
        appendLine("DUMP granted: ${PlaybackSessions.hasDumpPermission(this@MainActivity)}")
        val sessions = PlaybackSessions.query(this@MainActivity)
        if (sessions == null) appendLine("dump: unavailable (${PlaybackSessions.lastError})")
        else sessions.forEach {
            appendLine("  sid=${it.sessionId} ${it.packageName} ${it.usage} ${it.state} flags=0x${it.flags.toString(16)}" +
                if (it.flagsBlockCapture) " (capture opt-out)" else "")
        }
        appendLine("routes: " + SessionRouter.snapshot.joinToString { "${it.pkg}#${it.sessionId}=${it.owner}" })
        appendLine("capture verdicts: ${SessionRouter.compat().all()}")
    }

    private fun startCapture() {
        if (CaptureService.isRunning) return
        if (SvanRepository.settings.value.engineMode == app.svan.model.EngineMode.SYSTEM_ONLY) {
            EqController.log("capture: system-only mode; not started")
            return
        }
        SystemEqService.start(this)
        val blocker = CapturePolicy.startupBlock(PlaybackSessions.hasDumpPermission(this), SessionRouter.snapshot, android.os.Process.myUid())
        if (blocker != null) {
            CaptureService.startupMessage.value = blocker
            DetectionSetup.refresh()
            EqController.log("capture: start blocked — $blocker")
            return
        }
        CaptureService.startupMessage.value = ""
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    private fun stopCapture() {
        stopService(Intent(this, CaptureService::class.java))
    }

    @Deprecated("Uses the framework result API so scripted tests keep working")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION) return
        if (resultCode == RESULT_OK && data != null) {
            CaptureService.start(this, resultCode, data, pendingQuality)
            EqController.log("Capture requested (${pendingQuality ?: SvanRepository.settings.value.quality})")
        } else {
            EqController.log("Capture permission denied")
        }
    }

    @Deprecated("Uses the framework permission API")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startCapture()
    }

    private companion object {
        const val REQ_PROJECTION = 1
        const val REQ_PERMS = 2
        const val REQ_MIC = 3
    }
}
