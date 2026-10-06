package app.svan

/** UI decisions only. These types never attach effects, select an engine or grant access. */
enum class SettingState(val label: String) { ON("On"), OFF("Off"), UNKNOWN("Can't tell") }

data class DebuggingState(
    val developer: SettingState = SettingState.UNKNOWN,
    val usb: SettingState = SettingState.UNKNOWN,
    val wireless: SettingState = SettingState.UNKNOWN,
) {
    val verifiablyOff: Boolean get() = usb == SettingState.OFF && wireless == SettingState.OFF

    companion object {
        /** A nullable reader makes missing keys and denied reads testable without Android stubs. */
        fun read(api: Int, readInt: (String) -> Int?): DebuggingState {
            fun read(key: String): SettingState = try {
                when (readInt(key)) { 0 -> SettingState.OFF; 1 -> SettingState.ON; else -> SettingState.UNKNOWN }
            } catch (_: Exception) { SettingState.UNKNOWN }
            return DebuggingState(read("development_settings_enabled"), read("adb_enabled"),
                if (api >= 30) read("adb_wifi_enabled") else SettingState.UNKNOWN)
        }
    }
}

enum class WizardStep(val title: String) {
    INSTALL("Shizuku installed"), DEBUGGING("Wireless debugging setup"),
    START("Shizuku running"), AUTHORIZE("Svan approved in Shizuku"),
    GRANT("Music detection granted"), FINISH("Turn debugging off"),
}

data class WizardSnapshot(
    val installed: Boolean? = null,
    val running: Boolean? = null,
    val authorized: Boolean? = null,
    val dumpGranted: Boolean = false,
    val debugging: DebuggingState = DebuggingState(),
) {
    val step: WizardStep get() = when {
        dumpGranted -> WizardStep.FINISH
        installed != true -> WizardStep.INSTALL
        running != true && (debugging.developer != SettingState.ON || debugging.wireless != SettingState.ON) -> WizardStep.DEBUGGING
        running != true -> WizardStep.START
        authorized != true -> WizardStep.AUTHORIZE
        else -> WizardStep.GRANT
    }
}

enum class UiEngine(val title: String) { SYSTEM_EFFECTS("System effects"), AUDIOPHILE("Audiophile engine") }
enum class WorkingKind { IDLE, UNREACHABLE, ROUTED, UNKNOWN }
data class WorkingPlayer(
    val key: String, val name: String, val active: Boolean?, val attachable: Boolean,
    val engine: UiEngine? = null, val reason: String? = null,
)

data class WorkingState(val kind: WorkingKind, val players: List<WorkingPlayer>, val dumpGranted: Boolean) {
    fun promptKey(dismissed: Set<String>): String? = if (dumpGranted || kind != WorkingKind.UNREACHABLE) null
        else players.firstOrNull { !it.attachable && it.engine == null && it.key !in dismissed }?.key

    companion object {
        const val ANONYMOUS_PLAYER = "anonymous-playback"
        fun derive(publicOtherActive: Int?, observed: List<WorkingPlayer>, dumpGranted: Boolean): WorkingState {
            val playing = observed.filter { it.active == true || (it.active == null && (publicOtherActive ?: 0) > 0) }
            if (playing.isNotEmpty()) return WorkingState(
                if (playing.any { it.engine == null }) WorkingKind.UNREACHABLE else WorkingKind.ROUTED,
                playing, dumpGranted)
            return when {
                publicOtherActive == null -> WorkingState(WorkingKind.UNKNOWN, emptyList(), dumpGranted)
                publicOtherActive <= 0 -> WorkingState(WorkingKind.IDLE, emptyList(), dumpGranted)
                else -> WorkingState(WorkingKind.UNREACHABLE, listOf(WorkingPlayer(ANONYMOUS_PLAYER, "A player", true, false,
                    reason = if (dumpGranted) "Android has not exposed a usable audio session." else "Android has not exposed the app name or an audio session.")), dumpGranted)
            }
        }
    }
}

/** No player labels/packages, media metadata, output addresses or raw report excerpts. */
fun diagnosticSummary(version: String, api: Int, state: WorkingKind, scans: Int,
                      publicPlayers: Int?, routedSessions: Int, routeType: String): String =
    "Svan: $version\nAndroid: $api\nDetection: $state\nScans: $scans\n" +
        "Public playback count: ${publicPlayers ?: "unknown"}\nRouted sessions: $routedSessions\nOutput type: $routeType"

object FirstRunPolicy {
    fun needsFlatDefault(onboardingRecorded: Boolean, savedSound: Boolean, previousAutomaticDefault: Boolean): Boolean =
        !onboardingRecorded && !savedSound && !previousAutomaticDefault
}

fun detectionGrantFailureMessage(pending: Boolean): String = if (pending)
    "Android is still answering the request. Keep Shizuku running, wait a moment, then retry."
else "Music detection was not granted. Open Shizuku, allow Svan, then retry. Details stay in the local audio log."

fun batteryAdvice(manufacturer: String): String {
    val maker = manufacturer.lowercase()
    return when {
        listOf("tecno", "infinix", "itel").any { it in maker } -> "TECNO / Infinix / itel: check Svan's Battery and auto-start options; HiOS/XOS may stop background audio. Labels vary."
        "xiaomi" in maker || "redmi" in maker || "poco" in maker -> "Xiaomi: check app Battery saver and Background autostart. MIUI/HyperOS labels vary."
        "realme" in maker || "oppo" in maker -> "Realme / OPPO: check Svan's Battery usage and background activity options. ColorOS/Realme UI labels vary."
        "samsung" in maker -> "Samsung: check Battery's sleeping/deep-sleeping apps and Svan's app battery settings. One UI labels vary."
        else -> "Open Svan's app battery settings and check restrictions on background activity. Your phone may use different labels."
    }
}

/** Bundled factual rows, with an explicit provenance rather than a boolean compatibility badge. */
data class CompatibilityEntry(val player: String, val withoutSetup: String, val withDetection: String,
                              val verification: String, val note: String) {
    companion object {
        fun parse(text: String): List<CompatibilityEntry> = text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val f = line.split('\t')
                if (f.size != 5 || f[3] !in setOf("CI fake player", "Owner phone", "Unverified")) null
                else if (f[3] == "Unverified") CompatibilityEntry(f[0], "Unverified", "Unverified", f[3], f[4])
                else CompatibilityEntry(f[0], f[1], f[2], f[3], f[4])
            }.toList()
    }
}
