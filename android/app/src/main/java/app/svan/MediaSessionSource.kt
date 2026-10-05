package app.svan

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Optional, independent player recognition. Does not read notifications, metadata or audio. */
object MediaSessionSource {
    private val mutableSnapshot = MutableStateFlow(MediaPlayerSnapshot())
    val snapshot = mutableSnapshot.asStateFlow()
    fun component(context: Context) = ComponentName(context, PlayerRecognitionService::class.java)
    fun hasAccess(context: Context): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component(context))
    }.getOrDefault(false)

    /** Fresh per scan, never replay a remembered "playing" state after failure or revocation. */
    fun read(context: Context): MediaPlayerSnapshot {
        val access = hasAccess(context)
        val next = if (!access) MediaPlayerSnapshot() else runCatching {
            val controllers = context.getSystemService(MediaSessionManager::class.java).getActiveSessions(component(context))
            MediaPlayerSnapshot(access = true, available = true, players = controllers.mapNotNull { c ->
                if (c.packageName == context.packageName) return@mapNotNull null
                val uid = runCatching { context.packageManager.getApplicationInfo(c.packageName, 0).uid }.getOrDefault(-1)
                MediaPlayer(c.packageName, uid, c.playbackState?.state,
                    c.playbackInfo.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL)
            })
        }.getOrElse { MediaPlayerSnapshot(access = true, error = "${it.javaClass.simpleName}: player recognition unavailable") }
        mutableSnapshot.value = next
        return next
    }

    fun disconnected() { mutableSnapshot.value = MediaPlayerSnapshot(error = "Player recognition disconnected") }
}

/** Android binds this only after the user enables Notification access in Android settings. */
class PlayerRecognitionService : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())
    private val callbacks = mutableListOf<Pair<MediaController, MediaController.Callback>>()
    private var connected = false
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { refreshCallbacks() }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        runCatching { getSystemService(MediaSessionManager::class.java).addOnActiveSessionsChangedListener(sessionsChanged, MediaSessionSource.component(this), main) }
            .onFailure { EqController.log("media sessions: callback unavailable; periodic recognition remains active") }
        refreshCallbacks()
        EqController.log("media sessions: player recognition connected")
    }

    private fun clearCallbacks() {
        callbacks.forEach { (controller, callback) -> runCatching { controller.unregisterCallback(callback) } }
        callbacks.clear()
    }

    private fun refreshCallbacks() {
        if (!connected) return
        clearCallbacks()
        runCatching {
            getSystemService(MediaSessionManager::class.java).getActiveSessions(MediaSessionSource.component(this)).forEach { controller ->
                val cb = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) { SystemEqService.onSessionSignal() }
                    override fun onAudioInfoChanged(info: MediaController.PlaybackInfo) { SystemEqService.onSessionSignal() }
                    override fun onSessionDestroyed() { refreshCallbacks() }
                }
                controller.registerCallback(cb, main)
                callbacks.add(controller to cb)
            }
        }.onFailure { EqController.log("media sessions: player callbacks unavailable; periodic recognition remains active") }
        SystemEqService.onSessionSignal()
    }

    private fun disconnect() {
        connected = false
        clearCallbacks()
        runCatching { getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessionsChanged) }
        MediaSessionSource.disconnected()
        SystemEqService.onSessionSignal()
    }

    override fun onListenerDisconnected() { disconnect(); super.onListenerDisconnected() }
    override fun onDestroy() { disconnect(); super.onDestroy() }
}
