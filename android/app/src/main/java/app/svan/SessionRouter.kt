package app.svan

import android.content.Context
import android.media.projection.MediaProjection
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.Callable
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

    /** Capture checks for players that appear after the engine started run here, off the routing worker. */
    private val probeWorker = Executors.newSingleThreadExecutor()
    private val lateProbes = LateProbeSchedule() // worker-only
    private var probeInFlight = false // worker-only
    private var probeSerial = 0L
    private val confirmedCapture = mutableSetOf<String>() // positive checks belong to this projection
    private val muteCheckFailed = mutableSetOf<String>() // no repeated disruptive mute checks on the same stream
    private val reasons = ConcurrentHashMap<String, RouteReason>()

    /** Reasons backed by direct evidence that the app (not silence) forbids capture. */
    private val OPT_OUT_REASONS = setOf(RouteReason.STREAM_NOT_CAPTURABLE, RouteReason.UID_CAPTURE_DISABLED, RouteReason.APP_CAPTURE_DISABLED)

    /** Why this package is on system effects while the audiophile engine runs, for the Hi-Fi screen. */
    internal fun reasonFor(pkg: String): RouteReason? = reasons[pkg]

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
                        val reason = if (live.playing == false) RouteReason.WAITING_FOR_PLAYBACK else RouteReason.UID_GROUP_UNSAFE
                        EqController.log("capture: ${r.pkg} (session ${r.sessionId}) → Engine A (${reason.name}); other sources continue")
                        toEngineA(live.sessionId, live.pkg, live.uid, live.playing, reason)
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
     * A single event may be transient (one stream, an ad, a track), so it only backs off within the capture
     * session (see [FailOpenBackoff]). Silence does not establish a permanent app capture policy.
     */
    fun onCaptureSilent(noData: Boolean = false) {
        worker.execute {
            routes.values.filter { it.owner == Owner.ENGINE_B_MUTED && it.playing != false }.forEach {
                val count = (failOpens[it.pkg] ?: 0) + 1
                failOpens[it.pkg] = count
                val blockMs = FailOpenBackoff.blockMs(count)
                tempBlocked[it.pkg] = if (blockMs == Long.MAX_VALUE) Long.MAX_VALUE else System.currentTimeMillis() + blockMs
                confirmedCapture.remove(it.pkg)
                EqController.log("fail-open: ${it.pkg} (session ${it.sessionId}) → Engine A ${FailOpenBackoff.describe(count)}")
                toEngineA(it.sessionId, it.pkg, it.uid, it.playing,
                    if (noData) RouteReason.NO_CAPTURE_DATA else RouteReason.SILENT_RECENTLY)
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

    /** Forgets every stored capture verdict and silence strike, and the retry schedule, so each player is checked afresh. */
    fun forgetCaptureVerdicts() {
        compatStore.clear()
        CaptureUidPolicies.forget()
        worker.execute { lateProbes.clear(); tempBlocked.clear(); failOpens.clear(); muteCheckFailed.clear(); reasons.clear() }
    }

    /** Explicit recovery needs no app update, reinstall, or global reset. */
    fun retryCapture(pkg: String) {
        CaptureUidPolicies.forget()
        worker.execute {
            if (routes.values.any { it.pkg == pkg && it.owner == Owner.PROBING }) return@execute
            compatStore.forget(pkg)
            confirmedCapture.remove(pkg)
            muteCheckFailed.remove(pkg)
            lateProbes.reset(pkg)
            tempBlocked.remove(pkg)
            failOpens.remove(pkg)
            routes.values.filter { it.pkg == pkg && it.playing != false && it.owner == Owner.ENGINE_A }
                .forEach { reroute(it.sessionId, it.pkg, it.uid, it.playing) }
        }
    }

    fun captureReport(pkg: String): String = buildString {
        appendLine("Svan ${BuildConfig.VERSION_NAME}; Android API ${android.os.Build.VERSION.SDK_INT}")
        snapshot.filter { it.pkg == pkg }.forEach { r ->
            appendLine("$pkg sid=${r.sessionId} uid=${r.uid} playing=${r.playing} owner=${r.owner}")
            val installedUid = runCatching { appContext.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull()
            appendLine("installedUid=${installedUid ?: "unknown"} captureUid=${Process.myUid()} sourceUser=${r.uid / 100_000} captureUser=${Process.myUid() / 100_000}")
            appendLine("reason=${reasonFor(pkg)?.name ?: "none"}; ${compatStore.declaration(pkg, r.uid)?.summary() ?: "app manifest unavailable"}")
            appendLine(CaptureUidPolicies.describe(r.uid))
            evidence[r.sessionId]?.session?.let { appendLine("usage=${it.usage} flags=0x${it.flags.toUInt().toString(16)} captureOptOut=${it.flagsBlockCapture}") }
        }
        appendLine("admittedUIDs=${captureUids.size}; recorder=${CaptureCompat.recorders.currentPurpose ?: "none"}")
        append(compatStore.diagnosticFor(pkg))
    }
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
            lateProbes.clear()
            probeInFlight = false
            probeSerial++
            confirmedCapture.clear()
            muteCheckFailed.clear()
            reasons.clear()
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
            if (captureUids.isNotEmpty() || routes[sessionId]?.owner != Owner.ENGINE_A) {
                EqController.log("DIAG requires an idle audiophile engine and a source on system effects")
                return@execute
            }
            muter.unmute(sessionId)
            EqController.globalEq.detach(sessionId)
            try {
                EqController.log("DIAG unmuted:  " + compatStore.measure(mp, uid, 3000))
                val ok = muter.mute(sessionId)
                EqController.log("DIAG muted($ok): " + compatStore.measure(mp, uid, 3000))
            } catch (e: Exception) { EqController.log("DIAG inconclusive: ${e.javaClass.simpleName}") }
            finally {
                toEngineA(sessionId, routes[sessionId]?.pkg ?: "uid:$uid", uid, routes[sessionId]?.playing)
                EqController.log("DIAG done (system effects restored)")
            }
        }
    }

    /**
     * Prepare Engine B's initial UID allowlist before it opens its main AudioRecord.
     * Android devices commonly refuse a second simultaneous playback-capture record;
     * later checks share the recorder lease after the idle main recorder has closed.
     */
    fun onCaptureStarted(mp: MediaProjection, systemPackages: Set<String>): Boolean {
        CaptureUidPolicies.forget()
        captureSystemPackages = systemPackages
        // The window opens before the projection is visible, so no late check can start in between.
        startupProbeWindow = true
        projection = mp
        val routed = CountDownLatch(1)
        worker.execute {
            routingBatch = true
            try {
                lateProbes.clear()
                probeSerial++
                probeInFlight = false
                confirmedCapture.clear()
                muteCheckFailed.clear()
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
        failOpens.clear(); tempBlocked.clear(); reasons.clear()
        worker.execute {
            lateProbes.clear()
            probeSerial++
            probeInFlight = false
            confirmedCapture.clear()
            muteCheckFailed.clear()
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
            confirmedCapture.remove(pkg)
            CaptureUidPolicies.forget()
            history.opened(sessionId, pkg, uid, SystemClock.elapsedRealtime())
            refreshRecentConnections()
            reroute(sessionId, pkg, uid, playing)
            return
        }
        val identityPkg = if (existing != null && pkg.startsWith("uid:") && !existing.pkg.startsWith("uid:")) existing.pkg else pkg
        val previousGeneration = history.active(sessionId)?.generation
        val nextGeneration = history.opened(sessionId, identityPkg, uid, SystemClock.elapsedRealtime())
        refreshRecentConnections()
        if (previousGeneration != nextGeneration) {
            CaptureUidPolicies.forget()
            confirmedCapture.remove(identityPkg)
            healthRepair.forget(sessionId)
            lateProbes.reset(identityPkg)
            tempBlocked.remove(identityPkg)
            failOpens.remove(identityPkg)
            muteCheckFailed.remove(identityPkg)
        }
        if (existing != null) {
            // Re-route a session that was parked on Engine A only because it
            // wasn't playing yet (can't run the capture check on silence).
            val parked = existing.owner == Owner.ENGINE_A && existing.playing == false &&
                playing == true && projection != null
            if (parked) {
                CaptureUidPolicies.forget()
                confirmedCapture.remove(identityPkg)
                lateProbes.reset(identityPkg)
                tempBlocked.remove(identityPkg)
                failOpens.remove(identityPkg)
                muteCheckFailed.remove(identityPkg)
            }
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
            retryParkedPlayers()
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
        lateProbes.forgetSession(sessionId)
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
            // Connection changes may change an OEM capture path. Do not carry an inconclusive negative across them.
            lateProbes.clear(); tempBlocked.clear(); failOpens.clear(); muteCheckFailed.clear()
            CaptureUidPolicies.forget()
            retryParkedPlayers()
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
                    if (s.flagsBlockCapture) {
                        streamCaptureBlocked.add(s.sessionId)
                        compatStore.noteOptOut(s.packageName, RouteReason.STREAM_NOT_CAPTURABLE.name)
                    } else streamCaptureBlocked.remove(s.sessionId)
                    val playing = when (s.state) { "started" -> true; "paused", "stopped", "idle" -> false; else -> null }
                    if (s.flagsBlockCapture && routes[s.sessionId]?.owner == Owner.ENGINE_B_MUTED) {
                        toEngineA(s.sessionId, s.packageName, s.uid, playing, RouteReason.STREAM_NOT_CAPTURABLE)
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
                retryParkedPlayers()
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
        if (mp == null) { toEngineA(sid, pkg, uid, playing); return }
        val gate = when {
            uid < 0 -> RouteReason.PLAYER_UNIDENTIFIED
            pkg in captureSystemPackages -> RouteReason.SYSTEM_ONLY
            sid in streamCaptureBlocked -> RouteReason.STREAM_NOT_CAPTURABLE
            pkg in muteCheckFailed -> RouteReason.SILENT_AFTER_MUTE
            tempBlockedNow(pkg) -> RouteReason.SILENT_RECENTLY
            evidence[sid]?.session?.usage?.let { it != "USAGE_MEDIA" } == true -> RouteReason.NOT_MUSIC_USAGE
            compatStore.declaration(pkg, uid)?.allowed == false -> RouteReason.APP_CAPTURE_DISABLED
            else -> null
        }
        if (gate != null || evidence[sid]?.effectsPossible == false) {
            if (gate == null) reasons.remove(pkg)
            toEngineA(sid, pkg, uid, playing, gate)
            return
        }
        when {
            pkg in confirmedCapture -> toEngineB(sid, pkg, uid, playing)
            else -> {
                if (playing == false) {
                    // Silence proves nothing; park on Engine A until it plays.
                    toEngineA(sid, pkg, uid, playing, RouteReason.WAITING_FOR_PLAYBACK)
                    return
                }
                if (!startupProbeWindow) {
                    // A second playback AudioRecord must not disturb audio Engine B already carries, so the check runs
                    // later, while no source is captured. The source stays audible on system effects until then.
                    toEngineA(sid, pkg, uid, playing, if (lateProbes.gaveUp(pkg)) RouteReason.INCONCLUSIVE else RouteReason.CHECKING)
                    requestLateProbe(sid, pkg, uid)
                    return
                }
                // Unknown app. First listen without touching the source: a player that forbids capture is then never
                // silenced. Only audio that was heard is worth muting for, and the mute is confirmed below.
                val active = { projection === mp && enabled }
                val unmuted = try {
                    compatStore.probe(mp, uid, stillActive = active, pkg = pkg, sid = sid, phase = "unmuted")
                } catch (e: Exception) {
                    // Inconclusive (e.g. a second capture refused): don't cache a guess.
                    EqController.log("capture check failed for $pkg: $e")
                    toEngineA(sid, pkg, uid, playing, probeFailureReason(e))
                    return
                }
                if (!active()) { if (enabled) toEngineA(sid, pkg, uid, playing); return }
                if (unmuted != CaptureCompat.Verdict.CAPTURABLE) {
                    silentCheck(sid, pkg, uid, playing, "no audio reached capture while it was audible")
                    return
                }
                EqController.globalEq.detach(sid)
                if (!muter.mute(sid)) {
                    toEngineA(sid, pkg, uid, playing, RouteReason.MUTE_UNAVAILABLE)
                    return
                }
                routes[sid] = Route(sid, pkg, uid, Owner.PROBING, playing)
                publishCaptureUids()
                val muted = try {
                    compatStore.probe(mp, uid, stillActive = active, pkg = pkg, sid = sid, phase = "muted")
                } catch (e: Exception) {
                    EqController.log("capture check failed for $pkg: $e")
                    toEngineA(sid, pkg, uid, playing, probeFailureReason(e))
                    return
                }
                if (!active()) {
                    if (enabled) toEngineA(sid, pkg, uid, playing) else muter.unmute(sid)
                    return
                }
                if (muted == CaptureCompat.Verdict.CAPTURABLE) {
                    compatStore.remember(pkg, CaptureCompat.Verdict.CAPTURABLE)
                    confirmedCapture.add(pkg)
                    lateProbes.settled(pkg)
                    EqController.log("capture check: $pkg → CAPTURABLE")
                    toEngineB(sid, pkg, uid, playing)
                } else {
                    silentCheck(sid, pkg, uid, playing, "audio was captured before the source was muted, but none after", RouteReason.SILENT_AFTER_MUTE)
                }
            }
        }
    }

    /** Silence retries within this capture session; no permanent or app-version BLOCKED verdict is written. */
    private fun silentCheck(sid: Int, pkg: String, uid: Int, playing: Boolean?, detail: String,
                            reason: RouteReason = RouteReason.SILENT_RECENTLY) {
        confirmedCapture.remove(pkg)
        if (reason == RouteReason.SILENT_AFTER_MUTE) muteCheckFailed.add(pkg)
        EqController.log("capture check: $pkg → silent ($detail); not a permanent verdict")
        lateProbes.silent(pkg, SystemClock.elapsedRealtime())
        toEngineA(sid, pkg, uid, playing, reason)
    }

    /**
     * Re-examines players parked on system effects: an unknown one gets its capture check once the engine is idle,
     * and one that failed open earlier is promoted again when its back-off ends. Worker thread; rate-limited per session.
     */
    private fun retryParkedPlayers() {
        if (!enabled || projection == null || startupProbeWindow || labActive) return
        val now = SystemClock.elapsedRealtime()
        routes.values.toList().forEach { r ->
            if (r.owner != Owner.ENGINE_A || r.playing != true || r.uid < 0) return@forEach
            val worthRetry = !tempBlockedNow(r.pkg) && !probeInFlight && captureUids.isEmpty() &&
                r.pkg !in muteCheckFailed && reasons[r.pkg] != RouteReason.APP_CAPTURE_DISABLED && reasons[r.pkg] != RouteReason.STREAM_NOT_CAPTURABLE &&
                lateProbes.due(r.pkg, now)
            if (worthRetry && lateProbes.tickReady(r.sessionId, now)) reroute(r.sessionId, r.pkg, r.uid, r.playing)
        }
    }

    /**
     * Phase 1 of a late capture check: listen to [pkg]'s UID on [probeWorker] without muting it. Unmuted, so a player
     * that turns out not to allow capture is never silenced; the engine must be idle, so the check cannot disturb
     * audio it is already carrying. Phase 2 ([beginMutedCheck]) confirms capture survives the mute.
     */
    private fun requestLateProbe(sid: Int, pkg: String, uid: Int) {
        val mp = projection ?: return
        if (labActive) return // the diagnostic lab owns the playback recorder
        val now = SystemClock.elapsedRealtime()
        if (probeInFlight || captureUids.isNotEmpty() || !lateProbes.due(pkg, now)) return
        val generation = history.active(sid)?.generation
        lateProbes.started(pkg)
        probeInFlight = true
        EqController.log("capture check: $pkg starting (source stays audible; engine idle)")
        runLateProbe(mp, sid, pkg, uid, Owner.ENGINE_A) { settleUnmutedCheck(mp, sid, pkg, uid, generation, it) }
    }

    private fun runLateProbe(mp: MediaProjection, sid: Int, pkg: String, uid: Int, expect: Owner,
                             settle: (Result<CaptureCompat.Verdict>) -> Unit) {
        val stillOurs = { projection === mp && enabled && captureUids.isEmpty() &&
            routes[sid]?.let { it.uid == uid && it.owner == expect && it.playing != false } == true }
        val serial = ++probeSerial
        try {
            probeWorker.execute {
                val outcome = try { Result.success(compatStore.probe(mp, uid, stillActive = stillOurs,
                    pkg = pkg, sid = sid, phase = if (expect == Owner.PROBING) "muted" else "unmuted")) }
                catch (e: Exception) { Result.failure(e) }
                worker.execute { if (probeSerial == serial) settle(outcome) }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            probeInFlight = false
            lateProbes.cancelled(pkg)
        }
    }

    /**
     * The route as it was when a late check started, or null if anything changed meanwhile (capture stopped or restarted,
     * the session closed, was reused by another generation, or was routed elsewhere): a stale result applies nothing.
     */
    private fun lateCheckRoute(mp: MediaProjection, sid: Int, uid: Int, generation: Long?, expect: Owner): Route? {
        val live = routes[sid] ?: return null
        return live.takeIf { enabled && projection === mp && it.uid == uid && it.owner == expect && history.active(sid)?.generation == generation }
    }

    private fun settleUnmutedCheck(mp: MediaProjection, sid: Int, pkg: String, uid: Int, generation: Long?,
                                   outcome: Result<CaptureCompat.Verdict>) {
        probeInFlight = false
        val failure = outcome.exceptionOrNull()
        val live = lateCheckRoute(mp, sid, uid, generation, Owner.ENGINE_A)
        if (failure is ProbeCancelled || live == null) { lateProbes.cancelled(pkg); return }
        val now = SystemClock.elapsedRealtime()
        if (failure != null) {
            lateProbes.refused(pkg, now)
            EqController.log("capture check failed for $pkg: $failure")
            reasons[pkg] = probeFailureReason(failure, lateProbes.gaveUp(pkg))
            return
        }
        if (outcome.getOrNull() == CaptureCompat.Verdict.CAPTURABLE) {
            beginMutedCheck(mp, sid, live, generation)
            return
        }
        EqController.log("capture check: $pkg → silent while unmuted; policy unproven, source remains audible")
        lateProbes.silent(pkg, now)
        reasons[pkg] = if (lateProbes.gaveUp(pkg)) RouteReason.INCONCLUSIVE else RouteReason.SILENT_RECENTLY
    }

    /** Phase 2: audio was heard, so mute the source and confirm capture still carries it before switching engines. */
    private fun beginMutedCheck(mp: MediaProjection, sid: Int, live: Route, generation: Long?) {
        val pkg = live.pkg
        EqController.globalEq.detach(sid)
        if (!muter.mute(sid)) {
            lateProbes.refused(pkg, SystemClock.elapsedRealtime())
            toEngineA(sid, pkg, live.uid, live.playing, RouteReason.MUTE_UNAVAILABLE)
            return
        }
        routes[sid] = live.copy(owner = Owner.PROBING)
        publishCaptureUids()
        probeInFlight = true
        reasons[pkg] = RouteReason.CHECKING
        EqController.log("capture check: $pkg heard audio; confirming capture after muting the source")
        runLateProbe(mp, sid, pkg, live.uid, Owner.PROBING) { settleMutedCheck(mp, sid, pkg, live.uid, generation, it) }
    }

    private fun settleMutedCheck(mp: MediaProjection, sid: Int, pkg: String, uid: Int, generation: Long?,
                                 outcome: Result<CaptureCompat.Verdict>) {
        probeInFlight = false
        val failure = outcome.exceptionOrNull()
        val live = lateCheckRoute(mp, sid, uid, generation, Owner.PROBING)
        if (live == null) {
            // A check that outlived its session or capture must never leave a source muted.
            lateProbes.cancelled(pkg)
            routes[sid]?.takeIf { projection === mp && it.uid == uid && history.active(sid)?.generation == generation && it.owner == Owner.PROBING }?.let {
                if (enabled) toEngineA(sid, it.pkg, it.uid, it.playing) else muter.unmute(sid)
            }
            return
        }
        when {
            failure is ProbeCancelled -> { lateProbes.cancelled(pkg); toEngineA(sid, pkg, uid, live.playing, RouteReason.CHECKING) }
            failure != null -> {
                lateProbes.refused(pkg, SystemClock.elapsedRealtime())
                EqController.log("capture check failed for $pkg: $failure")
                toEngineA(sid, pkg, uid, live.playing, probeFailureReason(failure, lateProbes.gaveUp(pkg)))
            }
            outcome.getOrNull() == CaptureCompat.Verdict.CAPTURABLE -> {
                compatStore.remember(pkg, CaptureCompat.Verdict.CAPTURABLE)
                confirmedCapture.add(pkg)
                lateProbes.settled(pkg)
                EqController.log("capture check: $pkg → CAPTURABLE")
                toEngineB(sid, pkg, uid, live.playing)
            }
            else -> silentCheck(sid, pkg, uid, live.playing, "audio was captured before the source was muted, but none after", RouteReason.SILENT_AFTER_MUTE)
        }
    }

    private fun toEngineA(sid: Int, pkg: String, uid: Int, playing: Boolean?, reason: RouteReason? = null) {
        reason?.let {
            reasons[pkg] = it
            if (it in OPT_OUT_REASONS) compatStore.noteOptOut(pkg, it.name)
        }
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

    private fun probeFailureReason(failure: Throwable, gaveUp: Boolean = false): RouteReason = when {
        failure is ProbePolicyDenied -> RouteReason.UID_CAPTURE_DISABLED
        failure is ProbeUnavailable && failure.androidSilenced -> RouteReason.CAPTURE_SILENCED
        failure is ProbeUnavailable -> RouteReason.NO_CAPTURE_DATA
        gaveUp -> RouteReason.INCONCLUSIVE
        else -> RouteReason.RECORDER_BUSY
    }

    private fun toEngineB(sid: Int, pkg: String, uid: Int, playing: Boolean?) {
        if (!enabled || projection == null) { toEngineA(sid, pkg, uid, playing); return }
        EqController.globalEq.detach(sid)
        if (muter.mute(sid)) {
            attachmentRetry.forget(sid)
            reasons.remove(pkg)
            routes[sid] = Route(sid, pkg, uid, Owner.ENGINE_B_MUTED, playing)
            publishCaptureUids()
            EqController.log("route: $pkg (session $sid) → Engine B (source muted)")
        } else {
            toEngineA(sid, pkg, uid, playing, RouteReason.MUTE_UNAVAILABLE)
        }
    }

    // ---- diagnostic lab hooks (see app.svan.diag.CaptureLab). Read-only unless a disruptive trial is requested.

    /** While true, Svan's own background capture checks stand down so they cannot take the recorder or retry. */
    @Volatile internal var labActive = false

    /** The capture permission token held by the running audiophile engine, or null when it is not running. */
    internal fun labProjection(): MediaProjection? = projection

    /** Why the lab cannot use the playback recorder right now, or null when it is free. */
    internal fun labBusyReason(): String? = when {
        projection == null -> "The audiophile engine is not running, so Svan has no capture permission. Start it in Hi-Fi first."
        captureUids.isNotEmpty() -> "The audiophile engine is already capturing another app. Stop it and start it again with only the target playing."
        CaptureCompat.recorders.currentPurpose != null -> "A capture check is using the recorder (${CaptureCompat.recorders.currentPurpose}). Try again in a moment."
        else -> null
    }

    /**
     * Runs [block] with the session's Svan effect detached (and, with [mute], the source muted), then always
     * restores system effects. Returns null when the session is not on system effects or could not be prepared.
     */
    internal fun <T> labDisrupted(sid: Int, mute: Boolean, block: () -> T): T? {
        val prepared = runCatching {
            worker.submit(Callable {
                val route = routes[sid] ?: return@Callable false
                if (route.owner != Owner.ENGINE_A) return@Callable false
                routes[sid] = route.copy(owner = Owner.PROBING) // keeps the router's own repair/retry away from it
                EqController.globalEq.detach(sid)
                if (mute && !muter.mute(sid)) { toEngineA(sid, route.pkg, route.uid, route.playing); return@Callable false }
                true
            }).get(8, TimeUnit.SECONDS)
        }.getOrDefault(false)
        if (!prepared) return null
        try {
            return block()
        } finally {
            runCatching {
                worker.submit(Callable { routes[sid]?.let { toEngineA(sid, it.pkg, it.uid, it.playing) } }).get(8, TimeUnit.SECONDS)
            }.onFailure { EqController.log("diagnostic: could not restore system effects for session $sid: $it") }
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
