package app.svan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.AudioEffect
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Owns system effects and session discovery while the activity is backgrounded. */
class SystemEqService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val syncPending = AtomicBoolean(false)
    private var callback: AudioManager.AudioPlaybackCallback? = null
    private val sessionReceiver = SessionReceiver()
    private val main = Handler(Looper.getMainLooper())
    private val rescan = object : Runnable {
        override fun run() {
            if (!isRunning) return
            sync()
            main.postDelayed(this, 5_000)
        }
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
        SvanRepository.init(this)
        if (app.svan.svaramanas.Svaramanas.bubble.value) app.svan.svaramanas.SvaramanasBubbleService.start(this)
        SessionRouter.init(this)
        SessionRouter.enable()
        isRunning = true
        ContextCompat.registerReceiver(this, sessionReceiver, IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }, ContextCompat.RECEIVER_EXPORTED)
        // Register even before DUMP is granted: a grant while this service is
        // alive must take effect without a force-stop or an app reinstall.
        callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) { sync() }
        }.also { getSystemService(AudioManager::class.java).registerAudioPlaybackCallback(it, null) }
        main.post(rescan)
        EqController.log("system service: started")
    }

    private fun sync() {
        if (!isRunning || !PlaybackSessions.hasDumpPermission(this)) return
        if (!syncPending.compareAndSet(false, true)) return
        executor.execute {
            try {
                PlaybackSessions.query(this)?.let { if (isRunning) SessionRouter.sync(it) }
            } finally { syncPending.set(false) }
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
        sync()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        main.removeCallbacks(rescan)
        unregisterReceiver(sessionReceiver)
        callback?.let { getSystemService(AudioManager::class.java).unregisterAudioPlaybackCallback(it) }
        executor.shutdown()
        stopService(Intent(this, CaptureService::class.java))
        SessionRouter.shutdown()
        EqController.log("system service: stopped")
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "system_eq"
        private const val STOP = "app.svan.STOP_EQ"
        @Volatile var isRunning = false
            private set

        fun startIfEnabled(context: Context) {
            if (context.getSharedPreferences(CHANNEL, MODE_PRIVATE).getBoolean("enabled", true)) start(context)
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
