package app.svan

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only queries media-session package/playback state. Notification contents are never read. */
class PlayerRecognitionService : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())
    private val manager by lazy { getSystemService(MediaSessionManager::class.java) }
    private val watched = mutableMapOf<MediaController, MediaController.Callback>()
    // A queued callback can describe an older list than our initial query. Read
    // the current list so that a delayed empty snapshot cannot erase a live player.
    private val listener = MediaSessionManager.OnActiveSessionsChangedListener {
        if (listening) runCatching { refresh() }.onFailure {
            clear(); EqController.log("player recognition unavailable: ${it.javaClass.simpleName}")
        }
    }
    private var listening = false

    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching {
            manager.addOnActiveSessionsChangedListener(listener, ComponentName(this, PlayerRecognitionService::class.java), main)
            listening = true
            refresh()
            PlayerRecognition.setConnected(true)
        }.onFailure { clear(); EqController.log("player recognition unavailable: ${it.javaClass.simpleName}") }
    }

    private fun refresh() = update(manager.getActiveSessions(ComponentName(this, PlayerRecognitionService::class.java)))

    private fun update(controllers: List<MediaController>) {
        watched.forEach { (controller, cb) -> runCatching { controller.unregisterCallback(cb) } }
        watched.clear()
        controllers.filter { it.packageName != packageName && !MusicSourcePolicy.excludedPackage(it.packageName) }.forEach { controller ->
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) { publish() }
                override fun onSessionDestroyed() {
                    watched.remove(controller)?.let { runCatching { controller.unregisterCallback(it) } }
                    publish()
                }
            }
            runCatching { controller.registerCallback(cb, main); watched[controller] = cb }
        }
        publish()
    }

    private fun publish() {
        PlayerRecognition.setPlayers(watched.keys.map {
            RecognizedPlayer(it.packageName, runCatching { it.playbackState?.state == PlaybackState.STATE_PLAYING }.getOrDefault(false))
        }.groupBy { it.packageName }.map { (pkg, sessions) -> RecognizedPlayer(pkg, sessions.any { it.playing }) })
        SystemEqService.onSessionSignal()
    }

    private fun clear() {
        if (listening) runCatching { manager.removeOnActiveSessionsChangedListener(listener) }
        listening = false
        watched.forEach { (controller, cb) -> runCatching { controller.unregisterCallback(cb) } }
        watched.clear()
        PlayerRecognition.setConnected(false)
    }
    override fun onListenerDisconnected() { clear(); super.onListenerDisconnected() }
    override fun onDestroy() { clear(); super.onDestroy() }
}

object PlayerRecognition {
    private val mutablePlayers = MutableStateFlow<List<RecognizedPlayer>>(emptyList())
    val players = mutablePlayers.asStateFlow()
    private val mutableConnected = MutableStateFlow(false)
    val connected = mutableConnected.asStateFlow()
    fun enabled(context: Context): Boolean = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    internal fun setConnected(value: Boolean) { mutableConnected.value = value; if (!value) mutablePlayers.value = emptyList() }
    internal fun setPlayers(value: List<RecognizedPlayer>) { mutablePlayers.value = value }
}
