package app.svan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.AudioEffect
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/** Owns system effects and session discovery while the activity is backgrounded. */
class SystemEqService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val scanGate = ScanRequestGate()
    @Volatile private var alive = false
    private var callback: AudioManager.AudioPlaybackCallback? = null
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { SessionRouter.outputChanged(); recover("output connected") }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { SessionRouter.outputChanged(); recover("output disconnected") }
    }
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { recover("playback route or screen wake") }
    }
    private val sessionReceiver = SessionReceiver()
    private val main = Handler(Looper.getMainLooper())
    private val rescan = object : Runnable {
        override fun run() {
            if (!alive) return
            sync()
            main.postDelayed(this, 5_000)
        }
    }
    private val recoveryScan = Runnable { sync() }

    /** Bluetooth route/session creation is asynchronous; check again after it settles. */
    private fun recover(reason: String) {
        if (!alive) return
        EqController.log("detection: $reason; checking now and after route settles")
        sync()
        main.removeCallbacks(recoveryScan)
        longArrayOf(350, 1_000, 2_500, 5_000, 10_000).forEach { main.postDelayed(recoveryScan, it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "System equalizer", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 0, Intent(this, SystemEqService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val smart = PendingIntent.getActivity(this, 1, Intent(this, app.svan.svaramanas.SvaramanasActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        startForeground(2, Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Svan equalizer active")
            .setContentText("System effects stay active in the background")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Svaramanas", smart).build())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        DetectionSetup.init(this)
        ShizukuAudioReports.connect(this)
        SvanRepository.init(this)
        if (app.svan.svaramanas.Svaramanas.bubble.value) app.svan.svaramanas.SvaramanasBubbleService.start(this)
        SessionRouter.init(this)
        SessionRouter.enable()
        alive = true
        isRunning = true
        instance = this
        consumePanelRequest()
        // A start that finds this flag still "dirty" means Android (or the OEM's cleaner) killed the last run.
        prefs().edit().putBoolean(KEY_CLEAN, false).putLong(KEY_STARTED, System.currentTimeMillis()).apply()
        ContextCompat.registerReceiver(this, sessionReceiver, IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }, ContextCompat.RECEIVER_EXPORTED)
        // Register even before DUMP is granted: a grant while this service is
        // alive must take effect without a force-stop or an app reinstall.
        callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) { recover("playback changed") }
        }.also { cb ->
            runCatching { getSystemService(AudioManager::class.java).registerAudioPlaybackCallback(cb, main) }
                .onFailure { EqController.log("detection: playback callback unavailable; periodic scans remain active: $it") }
        }
        runCatching { getSystemService(AudioManager::class.java).registerAudioDeviceCallback(deviceCallback, main) }
            .onFailure { EqController.log("detection: route callback unavailable: $it") }
        ContextCompat.registerReceiver(this, wakeReceiver, IntentFilter().apply {
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        main.post(rescan)
        EqController.log("system service: started")
    }

    private fun sync() {
        if (!alive) return
        if (!scanGate.request()) return
        executor.execute {
            do {
                try {
                    if (alive) {
                        val outcome = DetectionMonitor.scan(this)
                        if (alive) {
                            val st = outcome.status
                            // Permission may be revoked while the activity stays open; do not leave setup saying READY.
                            main.post {
                                if ((DetectionSetup.state.value.stage == DetectionSetup.Stage.READY) != st.dumpPermission) DetectionSetup.refresh()
                                if (st.dumpPermission || st.knownAudioSessions > 0) CaptureService.startupMessage.value = ""
                            }
                            if (st.dumpPermission && (st.playersOk || outcome.serverRead)) {
                                SessionRouter.sync(
                                    // Partial reports supply positive evidence, never proof of absence.
                                    outcome.ledger.sessions.map { it.session }, st.playersOk, outcome.serverRead && outcome.af?.partial != true,
                                    outcome.ledger.sessions.associateBy { it.session.sessionId }, st.verification,
                                )
                            } else SessionRouter.repairKnownSessions()
                            MixFallback.evaluate(this)
                        }
                    }
                } catch (e: Exception) {
                    EqController.log("detection: scan failed; automatic retry remains active: $e")
                }
            } while (scanGate.complete())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            getSharedPreferences(CHANNEL, MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
            stopService(Intent(this, CaptureService::class.java))
            SessionRouter.shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        consumePanelRequest()
        recover("service start or manual refresh")
        return START_STICKY
    }

    private fun prefs() = getSharedPreferences(CHANNEL, MODE_PRIVATE)
    private fun consumePanelRequest() {
        panelRequest.getAndSet(null)?.let { SessionRouter.sessionOpened(it.sid, it.pkg, it.uid) }
    }

    override fun onDestroy() {
        alive = false
        prefs().edit().putBoolean(KEY_CLEAN, true).apply()
        if (instance === this) { instance = null; isRunning = false }
        main.removeCallbacks(rescan)
        main.removeCallbacks(recoveryScan)
        unregisterReceiver(sessionReceiver)
        unregisterReceiver(wakeReceiver)
        callback?.let { runCatching { getSystemService(AudioManager::class.java).unregisterAudioPlaybackCallback(it) } }
        runCatching { getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(deviceCallback) }
        executor.shutdownNow()
        Thread { EqController.globalEq.setMixFallback(false) }.start()
        stopService(Intent(this, CaptureService::class.java))
        SessionRouter.shutdown()
        EqController.log("system service: stopped")
        super.onDestroy()
    }

    companion object {
        private data class PanelRequest(val sid: Int, val pkg: String, val uid: Int)
        private val panelRequest = java.util.concurrent.atomic.AtomicReference<PanelRequest?>()
        private const val CHANNEL = "system_eq"
        private const val STOP = "app.svan.STOP_EQ"
        private const val KEY_CLEAN = "clean_shutdown"
        private const val KEY_STARTED = "last_started_ms"

        /** True if the user wants the service on but Android stopped it without Svan's own shutdown. */
        fun wasKilledByAndroid(context: Context): Boolean {
            val p = context.getSharedPreferences(CHANNEL, MODE_PRIVATE)
            return !isRunning && p.getBoolean("enabled", true) && p.contains(KEY_CLEAN) && !p.getBoolean(KEY_CLEAN, true)
        }

        fun lastStartedMs(context: Context): Long = context.getSharedPreferences(CHANNEL, MODE_PRIVATE).getLong(KEY_STARTED, 0L)

        /** Scan right now (screen on, app opened, user pressed refresh). */
        fun requestScanNow() { instance?.let { s -> s.main.post { s.recover("manual or foreground refresh") } } }
        @Volatile var isRunning = false
            private set
        @Volatile private var instance: SystemEqService? = null

        fun onSessionSignal() {
            instance?.let { service -> service.main.post { service.recover("player session signal") } }
        }

        fun startIfEnabled(context: Context) {
            if (context.getSharedPreferences(CHANNEL, MODE_PRIVATE).getBoolean("enabled", true)) start(context)
        }

        fun openEffectPanel(context: Context, sid: Int, pkg: String, uid: Int) {
            if (!context.getSharedPreferences(CHANNEL, MODE_PRIVATE).getBoolean("enabled", true)) return
            panelRequest.set(PanelRequest(sid, pkg, uid))
            startIfEnabled(context)
            instance?.let { s -> s.main.post { s.consumePanelRequest() } }
        }

        fun refreshDetection(context: Context) {
            // Called from the visible activity after permission setup. Preserve
            // an explicit stop chosen by the user.
            startIfEnabled(context)
        }

        fun start(context: Context) {
            context.getSharedPreferences(CHANNEL, MODE_PRIVATE).edit().putBoolean("enabled", true).apply()
            context.startForegroundService(Intent(context, SystemEqService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SystemEqService::class.java).setAction(STOP))
        }
    }
}
