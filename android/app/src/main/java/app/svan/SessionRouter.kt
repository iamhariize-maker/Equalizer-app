package app.svan

import android.content.Context
import android.media.projection.MediaProjection
import android.os.Process
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    enum class Owner { ENGINE_A, ENGINE_B_MUTED, PROBING, UNPROCESSED }

    data class Route(val sessionId: Int, val pkg: String, val uid: Int, val owner: Owner, val playing: Boolean? = null)

    private val routes = ConcurrentHashMap<Int, Route>()
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var appContext: Context
    private lateinit var compatStore: CaptureCompat
    private lateinit var appEngines: AppEnginePreferences
    @Volatile private var enabled = false
    @Volatile private var captureSystemPackages: Set<String> = emptySet()

    @Volatile private var projection: MediaProjection? = null
    /** Standalone per-UID probes are safe only before Engine B opens its main AudioRecord. */
    @Volatile private var startupProbeWindow = false
    private val muter = SourceMuter { sid -> worker.execute { onMuteLost(sid) } }

    @Volatile var captureUids: Set<Int> = emptySet()
        private set

    private fun publishCaptureUids() {
        val next = CapturePolicy.eligibleUids(routes.values, Process.myUid())
        if (captureUids != next) captureUids = next
        if (projection != null && routes.values.any { it.owner == Owner.ENGINE_B_MUTED && it.uid !in next }) {
            EqController.log("capture: conflicting UID routes; stopping safely")
            appContext.stopService(android.content.Intent(appContext, CaptureService::class.java))
        }
    }

    val snapshot: Collection<Route> get() = routes.values.toList()

    /** Packages whose capture proved silent while playing: Engine A only, until the expiry (ms). */
    private val tempBlocked = ConcurrentHashMap<String, Long>()
    private const val TEMP_BLOCK_MS = 3 * 60_000L

    /**
     * Engine B muted sources but their capture stayed digital silence while other media played:
     * unmute them and give them to Engine A so the listener is never left in silence.
     * Not cached as BLOCKED — the cause may be transient (one stream, an ad, a track).
     */
    fun onCaptureSilent() {
        worker.execute {
            routes.values.filter { it.owner == Owner.ENGINE_B_MUTED && it.playing != false }.forEach {
                tempBlocked[it.pkg] = System.currentTimeMillis() + TEMP_BLOCK_MS
                EqController.log("fail-open: ${it.pkg} (session ${it.sessionId}) → Engine A for a while")
                toEngineA(it.sessionId, it.pkg, it.uid, it.playing)
            }
        }
    }

    private fun tempBlockedNow(pkg: String): Boolean {
        val until = tempBlocked[pkg] ?: return false
        if (System.currentTimeMillis() < until) return true
        tempBlocked.remove(pkg); return false
    }

    @Synchronized
    fun init(context: Context) {
        if (!::compatStore.isInitialized) {
            appContext = context.applicationContext
            compatStore = CaptureCompat(appContext)
            appEngines = AppEnginePreferences(appContext)
        }
    }

    fun compat(): CaptureCompat = compatStore
    fun appPreferences(): AppEnginePreferences = appEngines

    fun enable() { enabled = true }

    fun shutdown() {
        enabled = false
        projection = null
        captureUids = emptySet()
        worker.execute {
            if (!CaptureService.isRunning) muter.releaseAll()
            EqController.globalEq.releaseAll()
            routes.clear()
            publishCaptureUids()
        }
    }

    /**
     * Diagnostics for the capture/mute interaction on this device: capture one
     * UID unmuted, then muted, then muted while the main capture also runs.
     */
    fun diagnose(uid: Int, sessionId: Int) {
        val mp = projection ?: run { EqController.log("DIAG no projection"); return }
        worker.execute {
            muter.unmute(sessionId)
            EqController.globalEq.detach(sessionId)
            EqController.log("DIAG unmuted:  " + compatStore.measure(mp, uid, 3000))
            val ok = muter.mute(sessionId)
            EqController.log("DIAG muted($ok): " + compatStore.measure(mp, uid, 3000))
            muter.unmute(sessionId)
            EqController.log("DIAG done (session $sessionId left unmuted)")
        }
    }

    /**
     * Prepare Engine B's initial UID allowlist before it opens its main AudioRecord.
     * Android devices commonly refuse a second simultaneous playback-capture record;
     * after this startup window, unknown apps fail over to Engine A until the next start.
     */
    fun onCaptureStarted(mp: MediaProjection, systemPackages: Set<String>): Boolean {
        captureSystemPackages = systemPackages
        projection = mp
        startupProbeWindow = true
        val routed = CountDownLatch(1)
        worker.execute {
            try {
                routes.values.toList().forEach { reroute(it.sessionId, it.pkg, it.uid, it.playing) }
            } finally {
                startupProbeWindow = false
                routed.countDown()
            }
        }
        return try {
            routed.await(30, TimeUnit.SECONDS) && projection === mp
        } catch (e: InterruptedException) {
            startupProbeWindow = false
            Thread.currentThread().interrupt()
            false
        }.also { ready ->
            if (!ready) {
                startupProbeWindow = false
                EqController.log("capture: startup routing did not finish before the recorder opened")
            }
        }
    }

    /** Engine B stopped: unmute everything and hand it back to Engine A. */
    fun onCaptureStopped() {
        projection = null
        startupProbeWindow = false
        captureUids = emptySet()
        worker.execute {
            muter.releaseAll()
            routes.values.toList().forEach { reroute(it.sessionId, it.pkg, it.uid, it.playing) }
        }
    }

    /** [playing] = null when unknown (broadcast path). [uid] < 0 = unknown, resolved here. */
    fun sessionOpened(sessionId: Int, pkg: String, uid: Int, playing: Boolean? = null) {
        if (!enabled || sessionId <= 0 || uid == Process.myUid()) return
        if (uid < 0) {
            // Package lookup failed (visibility) — the audio service dump knows the uid.
            worker.execute {
                val fromDump = if (PlaybackSessions.hasDumpPermission(appContext)) {
                    PlaybackSessions.query(appContext)?.firstOrNull { it.sessionId == sessionId }?.uid
                } else null
                if (fromDump != null && fromDump != Process.myUid()) sessionOpened(sessionId, pkg, fromDump, playing)
                else {
                    EqController.log("uid unknown for $pkg (session $sessionId): Engine A only")
                    toEngineA(sessionId, pkg, -1, playing)
                }
            }
            return
        }
        val existing = routes[sessionId]
        if (existing != null) {
            // Re-route a session that was parked on Engine A only because it
            // wasn't playing yet (can't run the capture check on silence).
            val parked = existing.owner == Owner.ENGINE_A && existing.playing == false &&
                playing == true && projection != null
            routes[sessionId] = existing.copy(playing = playing ?: existing.playing)
            if (parked) worker.execute { reroute(sessionId, pkg, uid, true) }
            return
        }
        worker.execute { reroute(sessionId, pkg, uid, playing) }
    }

    fun sessionClosed(sessionId: Int) {
        worker.execute {
            routes.remove(sessionId)
            publishCaptureUids()
            muter.unmute(sessionId)
            EqController.globalEq.detach(sessionId)
        }
    }

    /** Reconciles with a full session list from the dump. */
    fun sync(active: List<PlaybackSession>) {
        val seen = active.filter { it.uid != Process.myUid() }.associateBy { it.sessionId }
        routes.keys.filter { it !in seen }.forEach(::sessionClosed)
        EqController.log("sync: ${seen.size} session(s) in dump: " + seen.values.joinToString { "${it.packageName}#${it.sessionId}:${it.state}" })
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
        if (!enabled) return
        val mp = projection
        if (mp == null || pkg in captureSystemPackages || tempBlockedNow(pkg)) {
            toEngineA(sid, pkg, uid, playing)
            return
        }
        when (compatStore.cached(pkg)) {
            CaptureCompat.Verdict.BLOCKED -> toEngineA(sid, pkg, uid, playing)
            CaptureCompat.Verdict.CAPTURABLE -> toEngineB(sid, pkg, uid, playing)
            null -> {
                if (uid < 0) {
                    // Never run the capture check without a real uid: it would hear silence
                    // and wrongly cache BLOCKED.
                    toEngineA(sid, pkg, uid, playing)
                    return
                }
                if (playing == false) {
                    // Silence proves nothing; park on Engine A until it plays.
                    toEngineA(sid, pkg, uid, playing)
                    return
                }
                if (!startupProbeWindow) {
                    // Never try to open a second playback AudioRecord beside Engine B's
                    // active recorder. Keep the source audible and let the user restart
                    // capture with this player already running to run the compatibility probe.
                    EqController.log("capture check deferred for $pkg while Engine B is active; using Engine A")
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
                publishCaptureUids()
                val verdict = try {
                    compatStore.probe(mp, uid, stillActive = { projection === mp && enabled })
                } catch (e: Exception) {
                    // Inconclusive (e.g. a second capture refused): don't cache a guess.
                    EqController.log("capture check failed for $pkg: $e")
                    toEngineA(sid, pkg, uid, playing)
                    return
                }
                if (projection !== mp || !enabled) {
                    if (enabled) toEngineA(sid, pkg, uid, playing) else muter.unmute(sid)
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
        if (!enabled) { routes.remove(sid); publishCaptureUids(); return }
        val attached = EqController.globalEq.attach(sid)
        routes[sid] = Route(sid, pkg, uid, if (attached) Owner.ENGINE_A else Owner.UNPROCESSED, playing)
        publishCaptureUids()
        EqController.log("route: $pkg (session $sid) → ${if (attached) "Engine A" else "unprocessed (system effects unavailable)"}")
    }

    private fun toEngineB(sid: Int, pkg: String, uid: Int, playing: Boolean?) {
        if (!enabled || projection == null) { toEngineA(sid, pkg, uid, playing); return }
        EqController.globalEq.detach(sid)
        if (muter.mute(sid)) {
            routes[sid] = Route(sid, pkg, uid, Owner.ENGINE_B_MUTED, playing)
            publishCaptureUids()
            EqController.log("route: $pkg (session $sid) → Engine B (source muted)")
        } else {
            toEngineA(sid, pkg, uid, playing)
        }
    }

    private fun onMuteLost(sid: Int) {
        val r = routes[sid] ?: return
        EqController.log("lost mute control of ${r.pkg} (session $sid): another effect app took over")
        // Fail closed: stop processed playback before releasing source mutes.
        // Keeping a broad capture running here would create an audible echo.
        appContext.stopService(android.content.Intent(appContext, CaptureService::class.java))
        // The other app now drives this session's DynamicsProcessing; adding
        // Engine A would fight it, so leave the session alone.
        routes.remove(sid)
        publishCaptureUids()
    }
}
