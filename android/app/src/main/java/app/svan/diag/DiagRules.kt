package app.svan.diag

/** Trial ids of the capture lab. Rules reason about these by name, so they are fixed. */
object TrialIds {
    const val UID_MEDIA_F32 = "uid_media_f32"          // what Svan 0.5.10 listens with
    const val UID_ANY_F32 = "uid_any_f32"              // what 0.5.5 and 0.5.6 listened with (no usage rule)
    const val UID_ALL3_F32 = "uid_all3_f32"            // media + game + unknown, as the 0.5.5 main capture
    const val UID_MEDIA_S16 = "uid_media_s16"
    const val UID_MEDIA_F32_44K = "uid_media_f32_44k"
    const val ALL_MEDIA_F32 = "all_media_f32"          // every app's media except Svan
    const val ALL_ANY_F32 = "all_any_f32"
    const val UID_ANY_EFFECT_OFF = "uid_any_f32_effect_off" // disruptive: Svan's own effect detached
    const val UID_ANY_MUTED = "uid_any_f32_muted"           // disruptive: muted first, as 0.5.6 did
}

/**
 * Turns measurements into conclusions. Each rule states what it saw, what that means, and what to try, and
 * hedges where the measurement cannot prove a cause. The rules never claim a vendor or app "blocks" anything
 * unless the audio server's own report says so.
 */
object DiagRules {
    private val BYPASS_PATHS = setOf("offload", "direct", "bit-perfect", "exclusive/MMAP")

    fun evaluate(f: DiagFacts, nowMs: Long = System.currentTimeMillis()): List<Finding> {
        val out = mutableListOf<Finding>()
        environment(f, out)
        detection(f, out)
        effects(f, out)
        staticCapture(f, out)
        lab(f, out)
        return out.sortedWith(compareByDescending<Finding> { it.severity }.thenBy { it.code })
    }

    /** One line a person can read first. */
    fun headline(findings: List<Finding>): String {
        val top = findings.firstOrNull { it.severity == Severity.FAIL } ?: findings.firstOrNull { it.severity == Severity.WARN }
        return top?.let { "${it.severity.name}: ${it.title}" } ?: "No fault found in what was measured."
    }

    // ---------------------------------------------------------------- environment

    private fun environment(f: DiagFacts, out: MutableList<Finding>) {
        val e = f.env
        if (!e.serviceRunning) {
            out += Finding(Severity.FAIL, "SERVICE_NOT_RUNNING", "Svan's detection service is not running",
                listOf("System equalizer service running: no", if (e.killedByAndroid) "Android stopped it without Svan's own shutdown" else "Not stopped by Android"),
                listOf("Open Svan and start the system equalizer. Nothing can be detected while the service is off."))
        } else if (e.killedByAndroid) {
            out += Finding(Severity.WARN, "SERVICE_KILLED_BEFORE", "Android stopped Svan's service in an earlier run",
                listOf("The previous run did not end through Svan's own shutdown"),
                e.oem.tips.ifEmpty { listOf("Exempt Svan from battery optimisation and lock it in recents.") })
        }
        val restricted = e.backgroundRestricted == true || e.batteryOptimizationIgnored == false ||
            e.standbyBucket in setOf("RESTRICTED", "RARE")
        if (e.oem.aggressiveBackground && restricted) {
            out += Finding(Severity.WARN, "OEM_BACKGROUND_LIMITS", "${e.oem.family} limits background apps, and Svan is not exempt",
                listOf("battery optimisation ignored: ${e.batteryOptimizationIgnored}", "background restricted: ${e.backgroundRestricted}", "standby bucket: ${e.standbyBucket}"),
                listOf("These limits are typical causes of missed player announcements on this kind of skin; they are not proven to be the cause here.") + e.oem.tips)
        }
        if (e.developerOptionsOn == false && !e.reportAccess) {
            out += Finding(Severity.INFO, "ENHANCED_DETECTION_OFF", "Enhanced detection is unavailable: developer options are off",
                listOf("Shizuku and wireless debugging need developer options", "Enhanced reports: not available"),
                listOf("Without them Svan relies on the player announcing its session. Turn developer options back on and reconnect Shizuku for enhanced detection."))
        }
        if (!e.recordAudioGranted) {
            out += Finding(Severity.FAIL, "NO_RECORD_AUDIO", "Microphone permission is off, so the audiophile engine cannot capture",
                listOf("RECORD_AUDIO: not granted"), listOf("Grant the permission in Android settings. Svan uses it only to capture playback."))
        } else if (e.recordAudioOp != null && e.recordAudioOp != "allow") {
            out += Finding(Severity.WARN, "RECORD_AUDIO_OP_RESTRICTED", "The record-audio app-op is not plain 'allow'",
                listOf("android:record_audio mode: ${e.recordAudioOp}"),
                listOf("A restricted op can make Android silence a recorder. Set the permission to allow in app settings."))
        }
    }

    // ---------------------------------------------------------------- detection

    private fun detection(f: DiagFacts, out: MutableList<Finding>) {
        val t = f.target
        val d = f.detection
        val a = d.announcements
        if (!t.installed) {
            out += Finding(Severity.FAIL, "TARGET_NOT_VISIBLE", "${t.pkg} is not installed or not visible to Svan",
                listOf("Package lookup failed"), listOf("Install it, or check that Svan's package-visibility list includes it."))
            return
        }
        if (t.stopped == true) {
            out += Finding(Severity.WARN, "TARGET_STOPPED", "${t.label ?: t.pkg} is in Android's 'stopped' state",
                listOf("A stopped app is not running and cannot announce its session"), listOf("Open the app and start playback."))
        }
        val routed = d.routes.isNotEmpty()
        if (a.dropped > 0 && a.accepted == 0) {
            out += Finding(Severity.FAIL, "ANNOUNCEMENT_DROPPED", "${t.label ?: t.pkg} announced its session but Svan dropped every announcement",
                listOf("announcements seen: ${a.opens + a.closes}, dropped: ${a.dropped}") + a.dropReasons.map { "dropped: ${it.first} x${it.second}" },
                listOf("The reasons above say exactly why. 'service not running' means the announcement arrived while Svan's service was off."))
        }
        if (!f.env.reportAccess && !routed && a.accepted == 0) {
            val uptime = f.env.serviceUptimeMs?.let { "${it / 1000} s" } ?: "unknown"
            val selfTestBad = d.receiverSelfTestRan && d.receiverSelfTestMs == null
            when {
                selfTestBad -> out += Finding(Severity.FAIL, "RECEIVER_DEAF", "Svan's own announcement receiver did not receive a test broadcast",
                    listOf("Self-test broadcast: not delivered", "Service uptime: $uptime"),
                    listOf("Broadcast delivery to Svan appears blocked on this phone. ${f.env.oem.family} background limits are the usual cause.") + f.env.oem.tips)
                d.otherAnnouncers.isNotEmpty() -> out += Finding(Severity.FAIL, "TARGET_NEVER_ANNOUNCED",
                    "${t.label ?: t.pkg} has not announced a session, though other apps have",
                    listOf("Service uptime: $uptime", "${t.pkg} announcements: 0", "Apps that did announce: " + d.otherAnnouncers.entries.joinToString { "${it.key} x${it.value}" }),
                    listOf("Receiving works here, so this player is not announcing to Svan. If it was already playing before Svan started, close it completely and open it again.",
                        "If it still never announces, only Enhanced detection or shared-output EQ can find it."))
                else -> out += Finding(Severity.FAIL, "NO_ANNOUNCEMENTS_AT_ALL", "No app has announced a session since Svan's service started",
                    listOf("Service uptime: $uptime", "Self-test broadcast: ${if (d.receiverSelfTestRan) "delivered in ${d.receiverSelfTestMs} ms" else "not run"}", "Announcements from any app: 0"),
                    listOf("Receiving works, but no player has opened a new session since Svan started. Players announce when they create a session, so restart the player.",
                        "Svan cannot discover a session that already existed before its service started, except through Enhanced detection."))
            }
        }
        if (f.env.reportAccess && d.reportsReadable == false) {
            out += Finding(Severity.FAIL, "REPORTS_UNREADABLE", "Enhanced access is granted but Android's audio reports could not be read",
                listOf("Report route: ${f.env.reportRoute ?: "unknown"}"),
                listOf("Some builds (HiOS has been seen) stall the helper process. Re-run Svan's detection setup, or use the direct Shizuku route."))
        }
        if (a.accepted > 0 && !routed) {
            out += Finding(Severity.WARN, "ANNOUNCED_NOT_ROUTED", "${t.label ?: t.pkg} was accepted but has no route",
                listOf("accepted announcements: ${a.accepted}", "last: ${a.lastAcceptedMs?.let { "at $it" }}"),
                listOf("Routing may be waiting for playback. Press play and refresh detection."))
        }
        val active = d.publicActiveMedia
        if (active != null && active > 0 && !routed && a.accepted == 0 && !f.env.reportAccess) {
            out += Finding(Severity.WARN, "HIDDEN_PLAYER", "Android reports $active active media player(s) that Svan cannot connect to",
                listOf("public active playback count: $active", "Svan routes for ${t.pkg}: 0"),
                listOf("A playing app is hiding its audio session. Try 'Whole-phone EQ for hidden players' or shared-output EQ, or Enhanced detection."))
        }
        if (d.path != null && d.path in BYPASS_PATHS) {
            out += Finding(Severity.WARN, "BYPASS_PATH", "${t.label ?: t.pkg} plays on a '${d.path}' output that bypasses session effects",
                listOf("output path: ${d.path}"),
                listOf("Turn off the player's hi-res, exclusive, bit-perfect or offload option; effects and capture cannot reach this path."))
        }
    }

    // ---------------------------------------------------------------- effects

    private fun effects(f: DiagFacts, out: MutableList<Finding>) {
        val route = f.detection.routes.firstOrNull() ?: return
        val e = f.effects
        if (route.owner == "UNPROCESSED") {
            out += Finding(Severity.FAIL, "EFFECT_NOT_ATTACHED", "Svan could not attach its effect to ${f.target.label ?: f.target.pkg}",
                listOf("route owner: UNPROCESSED"), listOf("Another effect app may hold the session; stop it and refresh detection."))
        } else if (route.owner == "ENGINE_A") {
            when (e.verification) {
                "MISSING" -> out += Finding(Severity.WARN, "EFFECT_MISSING_IN_SERVER", "The effect is attached but the audio server does not list it",
                    listOf("verification: MISSING"), listOf("Svan retries automatically. If it persists the phone may drop effects on this stream."))
                "BYPASSED", "SUSPENDED" -> out += Finding(Severity.WARN, "EFFECT_BYPASSED", "The effect is attached but the audio server bypasses or suspends it",
                    listOf("verification: ${e.verification}"), listOf("The stream is on a power-saving or direct path. See the output-path finding."))
                "PROCESSING" -> out += Finding(Severity.OK, "EFFECT_VERIFIED", "System effects are verified on this player", listOf("verification: PROCESSING"))
            }
        }
        val others = e.sessionEffectNames.filterNot { it.contains("Dynamics", ignoreCase = true) }
        if (others.isNotEmpty()) {
            out += Finding(Severity.INFO, "OTHER_EFFECTS_ON_SESSION", "Other audio effects are attached to the same session",
                others.map { "effect: $it" },
                listOf("Vendor or app effects (for example Dolby, DTS or an equaliser app) can interact with Svan's. Turn them off to test."))
        }
    }

    // ---------------------------------------------------------------- capture, from static evidence

    private fun staticCapture(f: DiagFacts, out: MutableList<Finding>) {
        val t = f.target
        if (t.manifestAllows == false) {
            out += Finding(Severity.FAIL, "MANIFEST_DISABLES_CAPTURE", "${t.label ?: t.pkg}'s installed manifest disables playback capture",
                listOfNotNull(t.manifestSummary), listOf("The audiophile engine cannot receive this app's audio. System effects remain available."))
        }
        val p = f.policy
        p.uidPolicyMask?.takeIf { it and (PolicyDump.FLAG_NO_MEDIA_PROJECTION or PolicyDump.FLAG_NO_SYSTEM_CAPTURE) != 0 }?.let {
            out += Finding(Severity.FAIL, "UID_POLICY_BLOCKS_CAPTURE", "Android's audio server holds a UID-wide capture opt-out for this app",
                listOf("AllowedCapturePolicies flag mask: 0x${it.toString(16)}"), listOf("The app asked Android to forbid capture. Only System effects can process it."))
        }
        val blockingClient = p.clients.firstOrNull { it.blocksMediaProjection || it.blocksSystemCapture }
        if (blockingClient != null) {
            val reportedClean = f.detection.audioServiceFlags?.let { it and (PolicyDump.FLAG_NO_MEDIA_PROJECTION or PolicyDump.FLAG_NO_SYSTEM_CAPTURE) == 0 } == true
            out += Finding(Severity.FAIL, "STREAM_OPTS_OUT", "The audio server marks this player's stream as not capturable" +
                (if (reportedClean) " (Android's player list does not show it)" else ""),
                listOf("policy attributes: ${blockingClient.attributesLine}",
                    "player-list flags: ${f.detection.audioServiceFlags?.let { "0x" + it.toString(16) } ?: "unknown"}"),
                listOf("The stream itself opts out of capture. This is the app's choice; use System effects for it."))
        }
    }

    // ---------------------------------------------------------------- capture, from the lab

    private fun lab(f: DiagFacts, out: MutableList<Finding>) {
        val l = f.lab
        if (!l.ran) {
            out += Finding(Severity.INFO, "LAB_NOT_RUN", "The capture lab did not run", listOfNotNull(l.skipReason),
                listOf("Start the audiophile engine (it provides the capture permission), keep the player playing, and run the diagnostic again."))
            return
        }
        if (l.targetPlayingBefore == false) {
            out += Finding(Severity.WARN, "LAB_PLAYER_NOT_PLAYING", "The player was not playing when the capture lab started",
                listOf("player state before: not started"), listOf("Silence proves nothing here. Start playback and run it again."))
            return
        }
        val started = l.trials.filter { it.started }
        if (started.isEmpty()) {
            out += Finding(Severity.FAIL, "LAB_NO_RECORDER", "No capture recorder could be opened",
                l.trials.mapNotNull { it.error?.let { e -> "${it.id}: $e" } }.take(4),
                listOf("Android refused every capture attempt. Check the capture permission prompt, and that no other app is capturing."))
            return
        }
        val byId = l.trials.associateBy { it.id }
        fun heard(id: String) = byId[id]?.heardAudio == true
        fun line(id: String) = byId[id]?.let { "${it.title}: ${if (it.heardAudio) "HEARD AUDIO" else "silent"} (frames=${it.frames}, peak=${"%.4f".format(it.peak)})" }

        started.filter { it.silencedSeen.any { s -> s.contains("true") } }.firstOrNull()?.let {
            out += Finding(Severity.FAIL, "RECORDER_SILENCED", "Android reported that it silenced the capture recorder",
                listOf("${it.id}: isClientSilenced seen ${it.silencedSeen}"),
                listOf("Silencing follows the record-audio app-op or the app's process state. Check the permission and battery settings."))
        }

        val ladderEvidence = listOfNotNull(
            line(TrialIds.UID_MEDIA_F32), line(TrialIds.UID_ANY_F32), line(TrialIds.UID_ALL3_F32), line(TrialIds.UID_MEDIA_S16),
            line(TrialIds.UID_MEDIA_F32_44K), line(TrialIds.ALL_MEDIA_F32), line(TrialIds.ALL_ANY_F32),
            line(TrialIds.UID_ANY_EFFECT_OFF), line(TrialIds.UID_ANY_MUTED))
        val clientUsage = l.livePolicy?.clients?.firstOrNull()?.usage ?: f.policy.clients.firstOrNull()?.usage

        when {
            heard(TrialIds.UID_MEDIA_F32) ->
                out += Finding(Severity.OK, "CAPTURE_WORKS", "Capture hears ${f.target.label ?: f.target.pkg} with Svan's current settings", ladderEvidence,
                    listOf("If the live routing still reports silence, the difference is timing or ordering in Svan's admission step; report this diagnostic."))
            heard(TrialIds.UID_ANY_F32) || heard(TrialIds.UID_ALL3_F32) ->
                out += Finding(Severity.FAIL, "USAGE_FILTER_MISSES_STREAM", "Capture hears the player only when the usage rule is widened",
                    ladderEvidence + listOfNotNull(clientUsage?.let { "audio server's usage for the stream: $it" }),
                    listOf("Svan's media-only rule excludes this stream. Matching the usage the audio server reports would admit it."))
            heard(TrialIds.UID_MEDIA_S16) || heard(TrialIds.UID_MEDIA_F32_44K) ->
                out += Finding(Severity.WARN, "FORMAT_SENSITIVE", "Capture works only with a different sample format or rate", ladderEvidence,
                    listOf("This phone's capture path rejects Svan's default 48 kHz float format for this player."))
            heard(TrialIds.UID_ANY_MUTED) && !heard(TrialIds.UID_ANY_EFFECT_OFF) ->
                out += Finding(Severity.FAIL, "CAPTURE_ONLY_AFTER_MUTE", "Capture hears the player only after the player has been muted",
                    ladderEvidence, listOf("This is the sequence older Svan builds used. The current 'listen first, mute later' order cannot work on this phone for this player."))
            heard(TrialIds.UID_ANY_EFFECT_OFF) ->
                out += Finding(Severity.FAIL, "CAPTURE_ONLY_WITHOUT_EFFECT", "Capture hears the player only while Svan's own effect is detached", ladderEvidence,
                    listOf("Svan's effect on the session interferes with the capture tap on this phone."))
            (heard(TrialIds.ALL_MEDIA_F32) || heard(TrialIds.ALL_ANY_F32)) ->
                out += Finding(Severity.FAIL, "UID_FILTER_MISSES_STREAM", "Capture hears audio overall but not under this app's UID filter",
                    ladderEvidence + listOfNotNull(l.otherActivePlayers?.takeIf { it > 1 }?.let { "other media players were active ($it): the broad capture may be hearing them, not this player" }),
                    listOf("Either another app is what was heard, or the stream is played under a different UID than Svan filters on. Run again with only this player playing."))
            else -> {
                val client = l.livePolicy?.clients?.firstOrNull { it.active } ?: l.livePolicy?.clients?.firstOrNull()
                when {
                    l.livePolicy?.readable != true ->
                        out += Finding(Severity.FAIL, "ALL_CAPTURE_SILENT", "Every capture attempt was silent, and the audio server's view could not be read",
                            ladderEvidence, listOf("Enable Enhanced detection so the diagnostic can read the audio policy view and say why."))
                    client == null ->
                        out += Finding(Severity.FAIL, "NO_POLICY_CLIENT", "Every capture attempt was silent and the audio policy lists no active track for this app's UID",
                            ladderEvidence, listOf("The audio policy does not see this app's track under that UID. The player may use a path or process the policy tracks differently."))
                    client.blocksMediaProjection || client.blocksSystemCapture ->
                        out += Finding(Severity.FAIL, "STREAM_OPTS_OUT", "The stream's effective attributes forbid capture", ladderEvidence + listOfNotNull(client.attributesLine),
                            listOf("The player opts out of capture at the stream level."))
                    !client.attachedToCaptureMix ->
                        out += Finding(Severity.FAIL, "NOT_ATTACHED_TO_CAPTURE_MIX", "While capturing, the audio server did not copy this player's stream into the capture mix",
                            ladderEvidence + listOfNotNull(client.attributesLine, client.streamLine, "secondary outputs: none"),
                            listOf("Nothing in Android's published policy flags explains this, so a vendor audio policy or output path on this phone is the likely cause. The audio server entry above is the evidence to send to the Svan developer."))
                    else ->
                        out += Finding(Severity.FAIL, "ATTACHED_BUT_SILENT", "The stream is attached to the capture mix but only silence arrives",
                            ladderEvidence + listOfNotNull(client.attributesLine, "secondary outputs: ${client.secondaryOutputs}"),
                            listOf("The tap exists, so look at the recorder side: the record-audio permission/op, or the player writing silence to that path."))
                }
            }
        }
    }
}
