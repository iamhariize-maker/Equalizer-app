package dev.equalizer.app

import android.content.Context
import android.media.projection.MediaProjection
import android.os.Process
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Gives every audio session to exactly one engine:
 *  - Engine B running and the app allows capture → mute the source (B renders it)
 *  - otherwise → Engine A (DynamicsProcessing EQ on the session)
 * Both use DynamicsProcessing, which is one shared engine per session, so
 * a session must never be in both.
 *
 * Sessions arrive from the OPEN/CLOSE broadcasts (no permission needed) and,
 * with DUMP granted, from the audio-service dump (catches apps that don't broadcast).
 */
object SessionRouter {

    enum class Owner { ENGINE_A, ENGINE_B_MUTED, PROBING }

    data class Route(val sessionId: Int, val pkg: String, val uid: Int, val owner: Owner, val playing: Boolean? = null)

    private val routes = ConcurrentHashMap<Int, Route>()
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var compatStore: CaptureCompat

    @Volatile private var projection: MediaProjection? = null
    private val muter = SourceMuter { sid -> worker.execute { onMuteLost(sid) } }

    val snapshot: Collection<Route> get() = routes.values

    fun init(context: Context) {
        if (!::compatStore.isInitialized) compatStore = CaptureCompat(context.applicationContext)
    }

    fun compat(): CaptureCompat = compatStore

    /** Engine B started: move every capturable session over to it. */
    fun onCaptureStarted(mp: MediaProjection) {
        projection = mp
        worker.execute { routes.values.toList().forEach { reroute(it.sessionId, it.pkg, it.uid, it.playing) } }
    }

    /** Engine B stopped: unmute everything and hand it back to Engine A. */
    fun onCaptureStopped() {
        projection = null
        worker.execute {
            muter.releaseAll()
            routes.values.toList().forEach { reroute(it.sessionId, it.pkg, it.uid, it.playing) }
        }
    }

    /** [playing] = null when unknown (broadcast path). */
    fun sessionOpened(sessionId: Int, pkg: String, uid: Int, playing: Boolean? = null) {
        if (sessionId <= 0 || uid == Process.myUid()) return
        val existing = routes[sessionId]
        if (existing != null) {
            // Re-route a session that was parked on Engine A only because it
            // wasn't playing yet (can't run the capture check on silence).
            val parked = existing.owner == Owner.ENGINE_A && existing.playing == false &&
                playing == true && projection != null && compatStore.cached(pkg) == null
            routes[sessionId] = existing.copy(playing = playing ?: existing.playing)
            if (parked) worker.execute { reroute(sessionId, pkg, uid, true) }
            return
        }
        worker.execute { reroute(sessionId, pkg, uid, playing) }
    }

    fun sessionClosed(sessionId: Int) {
        worker.execute {
            routes.remove(sessionId)
            muter.unmute(sessionId)
            EqController.globalEq.detach(sessionId)
        }
    }

    /** Reconciles with a full session list from the dump. */
    fun sync(active: List<PlaybackSession>) {
        val seen = active.filter { it.uid != Process.myUid() }.associateBy { it.sessionId }
        routes.keys.filter { it !in seen }.forEach(::sessionClosed)
        seen.values.forEach { s ->
            if (!s.usageCapturable) return@forEach // calls, alarms, notifications: leave alone
            if (s.flagsBlockCapture && compatStore.cached(s.packageName) == null) {
                compatStore.remember(s.packageName, CaptureCompat.Verdict.BLOCKED)
            }
            sessionOpened(s.sessionId, s.packageName, s.uid, playing = s.state == "started")
        }
    }

    // ---- worker thread only below ----

    private fun reroute(sid: Int, pkg: String, uid: Int, playing: Boolean?) {
        val mp = projection
        if (mp == null) {
            toEngineA(sid, pkg, uid, playing)
            return
        }
        when (compatStore.cached(pkg)) {
            CaptureCompat.Verdict.BLOCKED -> toEngineA(sid, pkg, uid, playing)
            CaptureCompat.Verdict.CAPTURABLE -> toEngineB(sid, pkg, uid, playing)
            null -> {
                if (playing == false) {
                    // Silence proves nothing; park on Engine A until it plays.
                    toEngineA(sid, pkg, uid, playing)
                    return
                }
                // Unknown app: mute it, then check its capture actually carries audio.
                EqController.globalEq.detach(sid)
                if (!muter.mute(sid)) {
                    toEngineA(sid, pkg, uid, playing)
                    return
                }
                routes[sid] = Route(sid, pkg, uid, Owner.PROBING, playing)
                val verdict = try {
                    compatStore.probe(mp, uid)
                } catch (e: Exception) {
                    // Inconclusive (e.g. a second capture refused): don't cache a guess.
                    EqController.log("capture check failed for $pkg: $e")
                    toEngineA(sid, pkg, uid, playing)
                    return
                }
                compatStore.remember(pkg, verdict)
                EqController.log("capture check: $pkg → $verdict")
                if (verdict == CaptureCompat.Verdict.CAPTURABLE) toEngineB(sid, pkg, uid, playing) else toEngineA(sid, pkg, uid, playing)
            }
        }
    }

    private fun toEngineA(sid: Int, pkg: String, uid: Int, playing: Boolean?) {
        muter.unmute(sid)
        EqController.globalEq.attach(sid)
        routes[sid] = Route(sid, pkg, uid, Owner.ENGINE_A, playing)
        EqController.log("route: $pkg (session $sid) → Engine A")
    }

    private fun toEngineB(sid: Int, pkg: String, uid: Int, playing: Boolean?) {
        EqController.globalEq.detach(sid)
        if (muter.mute(sid)) {
            routes[sid] = Route(sid, pkg, uid, Owner.ENGINE_B_MUTED, playing)
            EqController.log("route: $pkg (session $sid) → Engine B (source muted)")
        } else {
            toEngineA(sid, pkg, uid, playing)
        }
    }

    private fun onMuteLost(sid: Int) {
        val r = routes[sid] ?: return
        EqController.log("lost mute control of ${r.pkg} (session $sid): another effect app took over")
        muter.unmute(sid)
        // The other app now drives this session's DynamicsProcessing; adding
        // Engine A would fight it, so leave the session alone.
        routes.remove(sid)
    }
}
