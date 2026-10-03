package dev.equalizer.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/** Bare diagnostics UI for the spike: no design, just buttons and a log. */
class MainActivity : Activity() {

    private lateinit var logView: TextView
    private var quality = NativeEngine.Quality.AUDIOPHILE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        logView = TextView(this).apply { setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE }

        fun button(label: String, onClick: (Button) -> Unit) =
            Button(this).apply { text = label; setOnClickListener { onClick(this) } }.also { root.addView(it) }

        button("1. Probe DynamicsProcessing band limits") {
            append("Probing…")
            thread { val r = DynamicsProbe.run(this); runOnUiThread { append(r) } }
        }
        button("1b. Measure audible band resolution (plays tones, volume low!)") {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
                append("Grant microphone permission (needed by Visualizer), then tap again.")
                return@button
            }
            append("Measuring… (~1 min)")
            thread {
                val r = ResolutionProbe.timeBulkSet(this) + ResolutionProbe.run(this)
                runOnUiThread { append(r) }
            }
        }
        button("2. Engine A: load sample AutoEq preset") {
            val n = EqController.loadPreset(EqController.SAMPLE_PRESET)
            append("Loaded $n bands into ${EqController.globalEq.bandCount}-band DynamicsProcessing; " +
                "sessions attached: ${EqController.globalEq.attachedSessions}")
        }
        button("3. Engine B quality: $quality") { b ->
            quality = NativeEngine.Quality.entries[(quality.ordinal + 1) % NativeEngine.Quality.entries.size]
            b.text = "3. Engine B quality: $quality"
        }
        button("4. Engine B: start capture") { startCapture() }
        button("5. Engine B: stop capture") {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
        }
        button("6. Session discovery diagnostics") {
            thread {
                val r = buildString {
                    val dump = PlaybackSessions.hasDumpPermission(this@MainActivity)
                    appendLine("DUMP granted: $dump")
                    val sessions = PlaybackSessions.query(this@MainActivity)
                    if (sessions == null) appendLine("dump: unavailable (${PlaybackSessions.lastError})")
                    else sessions.forEach {
                        appendLine("  sid=${it.sessionId} ${it.packageName} ${it.usage} ${it.state} flags=0x${it.flags.toString(16)}" +
                            if (it.flagsBlockCapture) " (capture opt-out)" else "")
                    }
                    SessionRouter.init(this@MainActivity)
                    appendLine("routes: " + SessionRouter.snapshot.joinToString { "${it.pkg}#${it.sessionId}=${it.owner}" })
                    appendLine("capture verdicts: ${SessionRouter.compat().all()}")
                }
                runOnUiThread { append(r) }
            }
        }
        button("7. Forget per-app capture verdicts") {
            SessionRouter.init(this)
            SessionRouter.compat().clear()
            append("Capture verdicts cleared")
        }
        button("Refresh log") { refresh() }

        val dump = checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED
        append("DUMP permission: ${if (dump) "granted" else "not granted (adb shell pm grant $packageName android.permission.DUMP)"}")

        root.addView(ScrollView(this).apply { addView(logView) })
        setContentView(root)
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

    @Deprecated("Spike uses the framework Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJECTION && resultCode == RESULT_OK && data != null) {
            CaptureService.start(this, resultCode, data, quality)
            append("Capture requested ($quality)")
        } else if (requestCode == REQ_PROJECTION) {
            append("Capture permission denied")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startCapture()
    }

    private fun refresh() {
        logView.text = synchronized(EqController.log) { EqController.log.toString() }
    }

    private fun append(line: String) {
        EqController.log(line)
        refresh()
    }

    private companion object {
        const val REQ_PROJECTION = 1
        const val REQ_PERMS = 2
        const val REQ_MIC = 3
    }
}
