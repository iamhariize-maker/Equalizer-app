package app.svan

import android.content.Context
import android.media.projection.MediaProjection
import android.os.Process
import android.os.SystemClock
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

    enum class Owner { ENGINE_A, ENGINE_B_MUTED, PROBING, UNPROCESSED, SHARED_OUTPUT }

    data class Route(val sessionId: Int, val pkg: String, val uid: Int, val owner: Owner, val playing: Boolean? = null)

    private val routes = ConcurrentHashMap<Int, Route>()
    private val worker = Executors.newSingleThreadExecutor()
    private var routingBatch = false // worker-only: publish complete routing transactions
    // Worker-owned lifecycle state. Successful scans are reconciled in order.
    private val absence = SessionAbsenceTracker()
    private val musicGate = MusicSourceGate()
    private val attachmentRetry = AttachmentRetry()
    private val missingRepair = MissingEffectRepair()
    private val healthRepair = MissingEffectRepair(firstDelayMs = 1_000)
    private val history = SessionConnectionHistory()
    private val sharedHandoff = SharedOutputHandoff()
    @Volatile var recentConnections: List<String> = emptyList()
        private set
    private val streamCaptureBlocked = mutableSetOf<Int>()
    private var lastSyncSummary = ""
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
        if (routingBatch) return
        val next = CapturePolicy.eligibleUids(routes.values, Process.myUid(), evidence.values.map { it.session })
        if (captureUids != next) captureUids = next
        if (projection != null) {
            // A muted source whose UID is no longer capturable (for example, the same app started a second,
            // not-yet-routed media session) would go silent. Hand just that source back to Engine A instead
            // of stopping capture for every app; the other sources keep the audiophile engine.
            val conflicting = routes.values.filter { it.owner == Owner.ENGINE_B_MUTED && it.uid !in next }
            if (conflicting.isNotEmpty()) worker.execute {
                conflicting.forEach { r ->
                    val live = routes[r.sessionId]
                    if (live != null && live.owner == Owner.ENGINE_B_MUTED && live.uid !in captureUids) {
                        EqController.log("capture: ${r.pkg} (session ${r.sessionId}) no longer capturable alone → Engine A; other sources continue")
                        toEngineA(live.sessionId, live.pkg, live.uid, live.playing)
                    }
                }
            }
        }
    }

    val snapshot: Collection<Route> get() = routes.values.toList()

    /** What the latest scan established about each session (path, owner, source). Replaced wholesale per scan. */
    @Volatile var evidence: Map<Int, LedgerSession> = emptyMap()
        private set
    /** Audio-server verification of Engine A's effect per session. */
    @Volatile var verification: Map<Int, Verification> = emptyMap()
        private set
    private val seenBy = mutableMapOf<Int, SessionSource>() // worker-owned

    /** Packages whose capture proved silent while playing: Engine A only, until the expiry (ms). */
    private val tempBlocked = ConcurrentHashMap<String, Long>()
    /** Fail-opens per package in this capture session; each one blocks Engine B for longer. */
    private val failOpens = ConcurrentHashMap<String, Int>()

    /**
     * Engine B muted sources but their capture stayed digital silence while other media played:
     * unmute them and give them to Engine A so the listener is never left in silence.
     * Not cached as BLOCKED — the cause may be transient (one stream, an ad, a track). Repeats in the
     * same capture session back off (see [FailOpenBackoff]) so a player that keeps capturing silence on
     * this phone is not muted for four seconds every few minutes.
     */
    fun onCaptureSilent() {
        worker.execute {
            routes.values.filter { it.owner == Owner.ENGINE_B_MUTED && it.playing != false }.forEach {
                val count = (failOpens[it.pkg] ?: 0) + 1
                failOpens[it.pkg] = count
                val blockMs = FailOpenBackoff.blockMs(count)
                tempBlocked[it.pkg] = if (blockMs == Long.MAX_VALUE) Long.MAX_VALUE else System.currentTimeMillis() + blockMs
                EqController.log("fail-open: ${it.pkg} (session ${it.sessionId}) → Engine A ${FailOpenBackoff.describe(count)}")
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
            EqController.globalEq.onEffectChanged = { sid, effect ->
                worker.execute {
                    if (enabled && EqController.globalEq.isCurrentEffect(sid, effect) && !EqController.globalEq.isHealthy(sid)) {
                        EqController.log("system effects: control/enable changed for session $sid; checking recovery")
                        if (sid == 0) disableSharedOnWorker("Shared-output control was lost. Per-player connections restored; retry explicitly.")
                        else repairOnWorker(sid)
                    }
                }
            }
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
            evidence = emptyMap()
            verification = emptyMap()
            absence.clear()
            musicGate.clear()
            attachmentRetry.clear()
            missingRepair.clear()
            healthRepair.clear()
            history.clear()
            sharedHandoff.cancel()
            recentConnections = emptyList()
            SharedOutput.publish(false, false, "Off. Per-player connections are used.")
            streamCaptureBlocked.clear()
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
            routingBatch = true
            try {
                if (SharedOutput.status.value.requested) {
                    projection = null
                    EqController.log("capture: shared-output EQ must be stopped first")
                    return@execute
                }
                routes.values.toList().forEach { reroute(it.sessionId, it.pkg, it.uid, it.playing) }
            } finally {
                routingBatch = false
                publishCaptureUids()
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
        // A new capture session starts fresh: the silence may have been specific to that session.
        failOpens.clear(); tempBlocked.clear()
        worker.execute {
            muter.releaseAll()
            if (sharedHandoff.captureStopped(enabled)) attachSharedOnWorker()
            else routes.values.toList().forEach { reroute(it.sessionId, it.pkg, it.uid, it.playing) }
        }
    }

    /** [playing] = null when unknown (broadcast path). [uid] < 0 = unknown, resolved here. */
    fun sessionOpened(sessionId: Int, pkg: String, uid: Int, playing: Boolean? = null) {
        if (!enabled || sessionId <= 0 || uid == Process.myUid() || MusicSourcePolicy.excludedPackage(pkg)) return
        worker.execute {
            if (!enabled) return@execute
            val observed = if (PlaybackSessions.hasReportAccess(appContext))
                PlaybackSessions.query(appContext)?.firstOrNull { it.sessionId == sessionId } else null
            if (observed != null && MusicSourcePolicy.exclusion(observed) != null) return@execute
            if (!SessionAnnouncement.consistent(uid, observed?.uid)) {
                // Any app can broadcast the standard open-session action naming another app. When the audio
                // service attributes this session to a different app, the claim is not trusted.
                EqController.log("route: ignored $pkg (session $sessionId): audio service reports uid ${observed?.uid}, claim $uid")
                return@execute
            }
            if (uid < 0) {
                val fromDump = observed?.uid

                if (fromDump == Process.myUid()) return@execute
                openOnWorker(sessionId, pkg, fromDump ?: -1, playing)
            } else {
                openOnWorker(sessionId, pkg, uid, playing)
            }
        }
    }

    private fun refreshRecentConnections() {
        recentConnections = history.recent(SystemClock.elapsedRealtime()).map { "${it.pkg}#${it.sessionId}:closed generation=${it.generation}" }
    }

    private fun openOnWorker(sessionId: Int, pkg: String, uid: Int, playing: Boolean?) {
        if (!enabled) return
        absence.forget(sessionId)
        val existing = routes[sessionId]
        if (existing != null && existing.uid >= 0 && uid >= 0 && existing.uid != uid) {
            // Retire the old owner's history before recording the replacement generation.
            closeOnWorker(sessionId)
            history.opened(sessionId, pkg, uid, SystemClock.elapsedRealtime())
            refreshRecentConnections()
            reroute(sessionId, pkg, uid, playing)
            return
        }
        val identityPkg = if (existing != null && pkg.startsWith("uid:") && !existing.pkg.startsWith("uid:")) existing.pkg else pkg
        val previousGeneration = history.active(sessionId)?.generation
        val nextGeneration = history.opened(sessionId, identityPkg, uid, SystemClock.elapsedRealtime())
        refreshRecentConnections()
        if (previousGeneration != nextGeneration) healthRepair.forget(sessionId)
        if (existing != null) {
            // Re-route a session that was parked on Engine A only because it
            // wasn't playing yet (can't run the capture check on silence).
            val parked = existing.owner == Owner.ENGINE_A && existing.playing == false &&
                playing == true && projection != null
            val next = existing.copy(pkg = if (pkg.startsWith("uid:") && !existing.pkg.startsWith("uid:")) existing.pkg else pkg,
                uid = if (uid >= 0) uid else existing.uid, playing = playing ?: existing.playing)
            routes[sessionId] = next
            val lostEffect = existing.owner == Owner.ENGINE_A && !EqController.globalEq.isHealthy(sessionId)
            val now = SystemClock.elapsedRealtime()
            val retry = (existing.owner == Owner.UNPROCESSED && attachmentRetry.ready(sessionId, now)) ||
                (lostEffect && healthRepair.shouldRepair(sessionId, Verification.MISSING, now))
            if (parked || retry) reroute(sessionId, next.pkg, next.uid, next.playing)
            return
        }
        reroute(sessionId, pkg, uid, playing)
    }

    fun sessionClosed(sessionId: Int, pkg: String) {
        worker.execute {
            val ownerPkg = routes[sessionId]?.pkg ?: evidence[sessionId]?.session?.packageName
            if (ownerPkg != pkg) {
                EqController.log("CLOSE ignored: package does not own session $sessionId")
                return@execute
            }
            closeOnWorker(sessionId, forgetEvidence = true)
        }
    }

    /** Broadcast-discovered sessions still recover even without enhanced detection. */
    fun repairKnownSessions() {
        worker.execute {
            if (!enabled) return@execute
            refreshRecentConnections()
            if (SharedOutput.status.value.requested && !EqController.globalEq.isHealthy(0) && SharedOutput.status.value.attached) {
                disableSharedOnWorker("Shared-output effect was lost. Per-player connections restored; retry explicitly.")
            }
            routes.values.toList().forEach { r ->
                repairOnWorker(r.sessionId)
            }
        }
    }

    private fun closeOnWorker(sessionId: Int, forgetEvidence: Boolean = false) {
        // CLOSE is definitive even when DUMP becomes unavailable. An older started
        // record must not look like an unmuted sibling when the player opens a new
        // session. Policy-rejected active records still retain their evidence.
        if (forgetEvidence) {
            evidence = evidence - sessionId
            verification = verification - sessionId
        }
        val prior = routes.remove(sessionId)
        prior?.let { history.closed(sessionId, history.active(sessionId)?.pkg ?: it.pkg, SystemClock.elapsedRealtime()) }
        refreshRecentConnections()
        seenBy.remove(sessionId)
        absence.forget(sessionId)
        musicGate.forget(sessionId)
        attachmentRetry.forget(sessionId)
        missingRepair.forget(sessionId)
        healthRepair.forget(sessionId)
        streamCaptureBlocked.remove(sessionId)
        publishCaptureUids()
        muter.unmute(sessionId)
        EqController.globalEq.detach(sessionId)
    }

    private fun repairOnWorker(sid: Int) {
        val r = routes[sid] ?: return
        val now = SystemClock.elapsedRealtime()
        val retry = when (r.owner) {
            Owner.UNPROCESSED -> attachmentRetry.ready(sid, now)
            Owner.ENGINE_A -> !EqController.globalEq.isHealthy(sid) &&
                healthRepair.shouldRepair(sid, Verification.MISSING, now)
            else -> false
        }
        if (retry) reroute(sid, r.pkg, r.uid, r.playing)
    }

    fun setSharedOutput(on: Boolean) {
        worker.execute {
            if (!on) { disableSharedOnWorker("Off. Per-player connections are used."); return@execute }
            if (sharedHandoff.waiting) return@execute
            if (!SharedOutputPolicy.allowed(true, enabled, projection != null || CaptureService.isRunning)) {
                SharedOutput.publish(false, false, "Stop the audiophile engine and start the system equalizer first.")
                return@execute
            }
            attachSharedOnWorker()
        }
    }

    /** Explicit UI choice for a hidden player: stop replay before releasing its source mutes. */
    fun switchToSharedOutput() {
        worker.execute {
            if (!enabled) {
                SharedOutput.publish(false, false, "Start the system equalizer before trying shared-output EQ.")
                return@execute
            }
            if (sharedHandoff.request(projection != null || CaptureService.isRunning)) {
                attachSharedOnWorker()
            } else {
                // Also blocks a new Engine B start while its old AudioTrack is closing.
                SharedOutput.publish(true, false, "Stopping Hi-Fi and finishing any recording before trying shared-output EQ.")
                appContext.stopService(android.content.Intent(appContext, CaptureService::class.java))
            }
        }
    }

    private fun attachSharedOnWorker() {
        if (!SharedOutputPolicy.allowed(true, enabled, projection != null || CaptureService.isRunning)) {
            SharedOutput.publish(false, false, "Shared-output EQ could not start while Hi-Fi was active. Try again after it stops.")
            return
        }
        // Capture's AudioTrack has been released before this acknowledgement. Never replay and unmute together.
        muter.releaseAll()
        EqController.globalEq.releaseAll()
        val ok = EqController.globalEq.attachOutputMix()
        SharedOutput.publish(ok, ok, if (ok)
            "Shared-output EQ attached. Player identity and this music's path are unverified."
            else "Android refused shared-output EQ. Per-player connections restored.")
        routes.values.toList().forEach { toEngineA(it.sessionId, it.pkg, it.uid, it.playing) }
        EqController.log("shared output: attached=$ok")
    }

    fun outputChanged() {
        worker.execute {
            if (SharedOutput.status.value.requested) disableSharedOnWorker(
                "Audio device connections changed. Shared-output EQ stopped; test the output before enabling it.")
            healthRepair.clear()
        }
    }

    private fun disableSharedOnWorker(message: String) {
        sharedHandoff.cancel()
        val wasShared = SharedOutput.status.value.requested || 0 in EqController.globalEq.attachedSessions
        SharedOutput.publish(false, false, message)
        if (!wasShared) return
        EqController.globalEq.detach(0)
        routes.values.toList().forEach { reroute(it.sessionId, it.pkg, it.uid, it.playing) }
        EqController.log("shared output: stopped")
    }

    /** Reconciles with a full session list from the dump. */
    fun sync(active: List<PlaybackSession>) = sync(
        active, playersOk = true, serverOk = false, evidence = emptyMap(), verification = emptyMap(),
    )

    /**
     * Reconciles with the fused ledger. A session that vanished is only counted as gone when a report
     * that could have listed it succeeded: a failed audio-server read must not evict a server-only session.
     */
    fun sync(
        active: List<PlaybackSession>,
        playersOk: Boolean,
        serverOk: Boolean,
        evidence: Map<Int, LedgerSession>,
        verification: Map<Int, Verification>,
    ) {
        val snapshot = active.toList()
        worker.execute {
            if (!enabled) return@execute
            if (SharedOutput.status.value.attached && !EqController.globalEq.isHealthy(0)) {
                disableSharedOnWorker("Shared-output effect was lost. Per-player connections restored; retry explicitly.")
            }
            routingBatch = true
            try {
                this.evidence = evidence
                this.verification = verification
                val seen = snapshot.filter { it.uid != Process.myUid() && it.state != "released" &&
                    musicGate.admit(it, SystemClock.elapsedRealtime(), routes.containsKey(it.sessionId) && routes[it.sessionId]?.uid == it.uid) }
                    .associateBy { it.sessionId }
                // An explicitly released or reclassified current record is definitive.
                snapshot.filter { it.state == "released" || MusicSourcePolicy.exclusion(it) != null }.forEach { closeOnWorker(it.sessionId) }
                // A failed report is unknown, not evidence that a pending player disappeared.
                if(playersOk && serverOk) musicGate.retain(snapshot.map { it.sessionId }.toSet())
                evidence.values.forEach { seenBy[it.session.sessionId] = it.source }
                val judged = routes.keys.filter { sid ->
                    when (seenBy[sid]) {
                        SessionSource.AUDIO_SERVICE -> playersOk
                        SessionSource.AUDIO_SERVER -> serverOk
                        else -> playersOk || serverOk
                    }
                }.toSet()
                absence.observe(judged, seen.keys, SystemClock.elapsedRealtime()).forEach { closeOnWorker(it) }
                routes.keys.filter { it !in seen && it in judged }.forEach { sid ->
                    routes[sid]?.let { routes[sid] = it.copy(playing = null) }
                }
                val summary = seen.values.sortedBy { it.sessionId }.joinToString { "${it.packageName}#${it.sessionId}:${it.state}" }
                if (summary != lastSyncSummary) {
                    EqController.log("sync: ${seen.size} session(s) in dump: $summary")
                    lastSyncSummary = summary
                }
                seen.values.forEach { s ->
                    if (s.flagsBlockCapture) streamCaptureBlocked.add(s.sessionId) else streamCaptureBlocked.remove(s.sessionId)
                    val playing = when (s.state) { "started" -> true; "paused", "stopped", "idle" -> false; else -> null }
                    if (s.flagsBlockCapture && routes[s.sessionId]?.owner == Owner.ENGINE_B_MUTED) {
                        toEngineA(s.sessionId, s.packageName, s.uid, playing)
                    } else openOnWorker(s.sessionId, s.packageName, s.uid, playing)
                    // The audio server says our effect is gone: rebuild it (with the usual capped back-off).
                    // Bounded: a report that keeps disagreeing with a working attach must not rebuild it every scan.
                    if (routes[s.sessionId]?.owner == Owner.ENGINE_A &&
                        missingRepair.shouldRepair(s.sessionId, verification[s.sessionId], SystemClock.elapsedRealtime())) {
                        EqController.log("verify: ${s.packageName} (session ${s.sessionId}) effect missing in the audio server; re-attaching")
                        EqController.globalEq.detach(s.sessionId)
                        reroute(s.sessionId, routes[s.sessionId]!!.pkg, routes[s.sessionId]!!.uid, playing)
                    }
                }
            } finally {
                routingBatch = false
                publishCaptureUids()
            }
        }
    }

    // ---- worker thread only below ----

    private fun reroute(sid: Int, pkg: String, uid: Int, playing: Boolean?) {
        if (!enabled) return
        val mp = projection
        if (mp == null || uid < 0 || pkg in captureSystemPackages || sid in streamCaptureBlocked || tempBlockedNow(pkg) ||
            evidence[sid]?.effectsPossible == false ||
            evidence[sid]?.session?.usage?.let { it != "USAGE_MEDIA" } == true) {
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
        if (SharedOutput.status.value.attached && EqController.globalEq.isHealthy(0)) {
            EqController.globalEq.detach(sid)
            routes[sid] = Route(sid, pkg, uid, Owner.SHARED_OUTPUT, playing)
            publishCaptureUids()
            return
        }
        val attached = EqController.globalEq.attach(sid)
        if (attached) attachmentRetry.forget(sid) else attachmentRetry.failed(sid, SystemClock.elapsedRealtime())
        routes[sid] = Route(sid, pkg, uid, if (attached) Owner.ENGINE_A else Owner.UNPROCESSED, playing)
        publishCaptureUids()
        EqController.log("route: $pkg (session $sid) → ${if (attached) "Engine A" else "unprocessed (system effects unavailable)"}")
    }

    private fun toEngineB(sid: Int, pkg: String, uid: Int, playing: Boolean?) {
        if (!enabled || projection == null) { toEngineA(sid, pkg, uid, playing); return }
        EqController.globalEq.detach(sid)
        if (muter.mute(sid)) {
            attachmentRetry.forget(sid)
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
