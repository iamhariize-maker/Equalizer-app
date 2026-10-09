package app.svan.diag

enum class Severity { OK, INFO, WARN, FAIL }

/** One conclusion, always with the measurements it rests on and a next step. Never a bare verdict. */
data class Finding(
    val severity: Severity,
    val code: String,
    val title: String,
    val evidence: List<String> = emptyList(),
    val advice: List<String> = emptyList(),
)

/** What kind of Android build this is. The family changes which background limits are likely. */
data class Oem(val family: String, val aggressiveBackground: Boolean, val tips: List<String>) {
    companion object {
        /**
         * [props] are lower-cased `ro.*` keys read from the build.prop files (see EnvProbe). OEM skins are
         * recognised from the maker first and from skin-specific properties second.
         */
        fun detect(manufacturer: String, brand: String, props: Map<String, String>): Oem {
            val m = "$manufacturer $brand".lowercase()
            val keys = props.keys
            fun hasKey(vararg parts: String) = keys.any { k -> parts.any { k.contains(it) } }
            return when {
                "tecno" in m || "infinix" in m || "itel" in m || "transsion" in m || hasKey("transsion", "tranos", "hios") ->
                    Oem("HiOS / Transsion (TECNO, Infinix, itel)", true, listOf(
                        "Settings > Apps > Svan > Battery: choose no restrictions (names vary by HiOS version).",
                        "Allow auto-start for Svan in the phone manager / Phone Master app, and lock Svan in the recents list.",
                        "After changing these, fully close Spotify and open it again so it announces a fresh session."))
                "xiaomi" in m || "redmi" in m || "poco" in m || hasKey("ro.mi.os", "ro.miui") ->
                    Oem(if (hasKey("ro.mi.os")) "HyperOS (Xiaomi, Redmi, POCO)" else "MIUI (Xiaomi, Redmi, POCO)", true, listOf(
                        "Settings > Apps > Svan > Autostart: on. Battery saver: No restrictions.",
                        "Lock Svan in the recents list. HyperOS can also block background activity starts.",
                        "Fully close the player and open it again after changing these."))
                "samsung" in m -> Oem("One UI (Samsung)", true, listOf(
                    "Settings > Battery > Background usage limits: remove Svan from sleeping and deep-sleeping apps.",
                    "Set Svan to Unrestricted battery use."))
                "oppo" in m || "realme" in m || "oneplus" in m || hasKey("opporom", "oplus", "coloros") ->
                    Oem("ColorOS / realme UI / OxygenOS", true, listOf(
                        "Settings > Battery > Svan: allow background activity and auto-launch.",
                        "Lock Svan in the recents list."))
                "vivo" in m || "iqoo" in m || hasKey("vivo") ->
                    Oem("Funtouch / OriginOS (vivo, iQOO)", true, listOf(
                        "Allow background high power consumption and auto-start for Svan in the i Manager app.",
                        "Lock Svan in the recents list."))
                "huawei" in m || "honor" in m || hasKey("emui", "magic") ->
                    Oem("EMUI / HarmonyOS / MagicOS (Huawei, Honor)", true, listOf(
                        "Settings > Battery > App launch > Svan: manage manually, allow auto-launch, secondary launch and run in background."))
                "google" in m -> Oem("Pixel (close to stock Android)", false, emptyList())
                "lg" in m || "lge" in m -> Oem("LG UX (LG)", false, emptyList())
                "motorola" in m -> Oem("Motorola (close to stock Android)", false, emptyList())
                else -> Oem("Unrecognised ($manufacturer $brand)", false, emptyList())
            }
        }
    }
}

data class EnvFacts(
    val sdk: Int,
    val oem: Oem,
    val developerOptionsOn: Boolean?,
    val adbEnabled: Boolean?,
    val wirelessAdbEnabled: Boolean?,
    val recordAudioGranted: Boolean,
    val dumpGranted: Boolean,
    val shizukuInstalled: Boolean,
    val shizukuRunning: Boolean,
    val shizukuPermitted: Boolean,
    val reportAccess: Boolean,
    val reportRoute: String?,
    val batteryOptimizationIgnored: Boolean?,
    val backgroundRestricted: Boolean?,
    val standbyBucket: String?,
    val powerSave: Boolean?,
    val serviceRunning: Boolean,
    val serviceUptimeMs: Long?,
    val killedByAndroid: Boolean,
    val captureRunning: Boolean,
    val recordAudioOp: String?,
    val runInBackgroundOp: String?,
)

data class TargetFacts(
    val pkg: String,
    val label: String?,
    val installed: Boolean,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val uid: Int? = null,
    val installer: String? = null,
    val stopped: Boolean? = null,
    val enabled: Boolean? = null,
    val system: Boolean? = null,
    val targetSdk: Int? = null,
    val signerPrefix: String? = null,
    val manifestAllows: Boolean? = null,
    val manifestSummary: String? = null,
)

data class RouteFacts(val sessionId: Int, val owner: String, val playing: Boolean?, val reason: String?)

data class DetectionFacts(
    val announcements: AnnouncementSummary,
    val otherAnnouncers: Map<String, Int>,
    val routes: List<RouteFacts>,
    val audioServiceState: String?,
    val audioServiceUsage: String?,
    val audioServiceFlags: Int?,
    val playerType: String?,
    val path: String?,
    val publicActiveMedia: Int?,
    val playbackRecordLines: List<String>,
    val reportsReadable: Boolean?,
    /** Whether Svan sent itself a test announcement; [receiverSelfTestMs] is null when the test was sent but never arrived. */
    val receiverSelfTestRan: Boolean,
    val receiverSelfTestMs: Long?,
)

data class EffectFacts(
    val attachedHere: Boolean,
    val healthy: Boolean?,
    val verification: String?,
    val sessionEffectNames: List<String>,
    val installedOemEffects: List<String>,
)

data class PolicyFacts(
    val readable: Boolean,
    val error: String?,
    val clients: List<PolicyDump.Client>,
    val uidPolicyMask: Int?,
) {
    val anyBlocksCapture: Boolean get() = clients.any { it.blocksMediaProjection || it.blocksSystemCapture } ||
        (uidPolicyMask?.let { it and (PolicyDump.FLAG_NO_MEDIA_PROJECTION or PolicyDump.FLAG_NO_SYSTEM_CAPTURE) != 0 } == true)
}

data class TrialResult(
    val id: String,
    val title: String,
    val scope: String,
    val usages: String,
    val format: String,
    val started: Boolean,
    val error: String?,
    val frames: Long,
    val nonZeroSamples: Long,
    val peak: Float,
    val firstAudioMs: Long?,
    val elapsedMs: Long,
    val silencedSeen: Set<String>,
    val recorderState: Int,
    val disruption: String? = null,
    val ownImportance: String? = null,
    val notes: List<String> = emptyList(),
) {
    val heardAudio: Boolean get() = started && nonZeroSamples > 0
}

data class LabFacts(
    val ran: Boolean,
    val skipReason: String?,
    val targetPlayingBefore: Boolean?,
    val targetPlayingAfter: Boolean?,
    /** Other media players Android reported as active during the lab (broad-capture trials may hear them). */
    val otherActivePlayers: Int?,
    val trials: List<TrialResult>,
    /** Policy view captured while a capture recorder was live (the decisive moment). */
    val livePolicy: PolicyFacts?,
    val liveFlingerLines: List<String>,
    val liveMixSections: List<String>,
)

data class DiagFacts(
    val env: EnvFacts,
    val target: TargetFacts,
    val detection: DetectionFacts,
    val effects: EffectFacts,
    val policy: PolicyFacts,
    val lab: LabFacts,
)
