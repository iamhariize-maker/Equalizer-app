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
            navigationBarStyle = SystemBarStyle.dark(Color.BLACK),
        )
        super.onCreate(savedInstanceState)
        SvanRepository.init(this)
        SessionRouter.init(this)

        val dump = checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED
        EqController.log("DUMP permission: ${if (dump) "granted" else "not granted (adb shell pm grant $packageName android.permission.DUMP)"}")

        setContent {
            SvanTheme {
                SvanApp(
                    onStartCapture = { pendingQuality = null; startCapture() },
                    onStopCapture = ::stopCapture,
                    labActions = listOf(
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

    /**
     * Scriptable entry points for automated tests (output goes to logcat tag EqSpike):
     *   adb shell am start -n app.svan/.MainActivity --es cmd <command> [--es quality EFFICIENT]
     * Commands: probe, resolution, sessions, preset, start_capture, stop_capture,
     *           measure_mix, forget_verdicts
     */
    private fun handleCommand(intent: Intent?) {
        val cmd = intent?.getStringExtra("cmd") ?: return
        intent.getStringExtra("quality")?.let { pendingQuality = QualityMode.valueOf(it) }
        EqController.log("CMD $cmd")
        when (cmd) {
            "probe" -> thread { EqController.log(DynamicsProbe.run(this)) }
            "resolution" -> runResolutionProbe()
            "sessions" -> thread { EqController.log(sessionReport()) }
            "preset" -> {
                val n = EqController.loadPreset(EqController.SAMPLE_PRESET)
                val r = EqController.curveEngine.responseDb(doubleArrayOf(1000.0))[0]
                EqController.log("preset bands=$n response@1kHz=%.2f dB".format(r))
            }
            "start_capture" -> startCapture()
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
        startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
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
