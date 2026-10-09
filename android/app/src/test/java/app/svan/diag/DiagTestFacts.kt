package app.svan.diag

/** Builders for diagnostic facts, with a healthy baseline that each test then breaks in one way. */
internal object DiagTestFacts {
    fun env() = EnvFacts(
        sdk = 34, oem = Oem("HiOS / Transsion (TECNO, Infinix, itel)", true, listOf("tip")),
        developerOptionsOn = true, adbEnabled = true, wirelessAdbEnabled = false,
        recordAudioGranted = true, dumpGranted = false, shizukuInstalled = true, shizukuRunning = true, shizukuPermitted = true,
        reportAccess = true, reportRoute = "Shizuku DIRECT", batteryOptimizationIgnored = true, backgroundRestricted = false,
        standbyBucket = "ACTIVE", powerSave = false, serviceRunning = true, serviceUptimeMs = 600_000, killedByAndroid = false,
        captureRunning = true, recordAudioOp = "allow", runInBackgroundOp = "allow",
    )

    fun target() = TargetFacts("com.spotify.music", "Spotify", true, "9.0.1", 123, 10520, "installer=com.android.vending",
        false, true, false, 37, "abcd1234abcd1234", true, "manifest capture=allowed, declaration=true")

    fun announcements(opens: Int = 1, accepted: Int = 1, dropped: Int = 0, reasons: List<Pair<String, Int>> = emptyList()) =
        AnnouncementSummary(opens, 0, accepted, dropped, 1_000L, 1_000L, reasons, setOf(42225))

    fun detection(a: AnnouncementSummary = announcements(), routes: List<RouteFacts> = listOf(RouteFacts(42225, "ENGINE_A", true, null))) =
        DetectionFacts(a, emptyMap(), routes, "started", "USAGE_MEDIA", 0, "android.media.AudioTrack", "mixer", 1,
            emptyList(), true, true, 40L)

    fun effects() = EffectFacts(true, true, "PROCESSING", emptyList(), emptyList())

    fun client(flags: Int? = 0, secondary: List<Int> = emptyList(), hasLine: Boolean = true) = PolicyDump.Client(
        77, 42225, 10520, true, "PCM 48000", "Attributes: { Usage: AUDIO_USAGE_MEDIA Flags: ${flags?.let { "0x" + it.toString(16) }} }",
        "AUDIO_USAGE_MEDIA", flags, "Stream: 3; Flags: 00000000; Refcount: 1", secondary, hasLine, emptyList())

    fun policy(vararg c: PolicyDump.Client, mask: Int? = null, readable: Boolean = true) = PolicyFacts(readable, null, c.toList(), mask)

    fun trial(id: String, heard: Boolean, started: Boolean = true, silenced: Set<String> = setOf("false")) = TrialResult(
        id, id, "uid 10520", "MEDIA", "float32 48000 Hz", started, if (started) null else "refused", 100_000,
        if (heard) 5000 else 0, if (heard) 0.5f else 0f, if (heard) 120L else null, 3500, silenced, 3)

    fun lab(trials: List<TrialResult>, live: PolicyFacts? = null, playing: Boolean? = true, others: Int? = 0) =
        LabFacts(true, null, playing, playing, others, trials, live, emptyList(), emptyList())

    val allSilent = TrialIds.let { listOf(it.UID_MEDIA_F32, it.UID_ANY_F32, it.UID_ALL3_F32, it.UID_MEDIA_S16, it.UID_MEDIA_F32_44K, it.ALL_MEDIA_F32, it.ALL_ANY_F32) }
        .map { trial(it, false) }

    fun noLab() = LabFacts(false, "not requested", null, null, null, emptyList(), null, emptyList(), emptyList())

    fun facts(
        env: EnvFacts = env(), target: TargetFacts = target(), detection: DetectionFacts = detection(),
        effects: EffectFacts = effects(), policy: PolicyFacts = policy(), lab: LabFacts = noLab(),
    ) = DiagFacts(env, target, detection, effects, policy, lab)

    fun codes(findings: List<Finding>) = findings.map { it.code }
}
