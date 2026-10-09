package app.svan

/** Where one player stands with the audiophile (capture) engine. */
internal enum class CaptureStanding {
    /** Capture is carrying it now, or was heard on this installed version. */
    FULL,
    /** Direct evidence says the app forbids capture. System effects only, however good the engine is. */
    SYSTEM_ONLY_BY_APP,
    /** The listener chose System effects for this app. */
    SYSTEM_ONLY_BY_CHOICE,
    /** A check is running, or heard nothing yet. Not proof of a block. */
    UNCONFIRMED,
    /** Nothing has been observed for this installed version. */
    NOT_CHECKED,
}

internal data class AppCaptureInputs(
    val pkg: String,
    val label: String,
    val versionName: String?,
    val systemOnlyChoice: Boolean,
    /** From the installed manifest: null when it could not be read. */
    val manifestAllows: Boolean?,
    /** Capture was heard (before and after muting) on this installed version. */
    val heardOnThisVersion: Boolean,
    /** Direct opt-out evidence seen for this version: reason code and when. */
    val optOutReason: String?,
    val optOutAtMs: Long?,
    /** Live route, when Svan is routing this player now. */
    val routeOwner: String?,
    val routeReason: String?,
    val engineRunning: Boolean,
)

internal data class AppCaptureStatus(
    val pkg: String,
    val label: String,
    val versionName: String?,
    val standing: CaptureStanding,
    val headline: String,
    val detail: String,
)

/** Pure decision for the per-app status card. Evidence of a block outranks an older success: a version can change its mind. */
internal object CaptureStatusRules {
    private val OPT_OUT_ROUTE_REASONS = setOf("STREAM_NOT_CAPTURABLE", "UID_CAPTURE_DISABLED", "APP_CAPTURE_DISABLED")
    private val CHECKING_ROUTE_REASONS = setOf("CHECKING", "WAITING_FOR_PLAYBACK", "RECORDER_BUSY", "UID_GROUP_UNSAFE")

    fun reasonText(reason: String?): String = when (reason) {
        "STREAM_NOT_CAPTURABLE" -> "Android's audio server marks this app's audio stream as not capturable."
        "UID_CAPTURE_DISABLED" -> "Android's audio server reports that this app disabled playback capture."
        "APP_CAPTURE_DISABLED" -> "This installed app's manifest disables playback capture."
        else -> "The app forbids capture."
    }

    fun evaluate(i: AppCaptureInputs, nowMs: Long = System.currentTimeMillis()): AppCaptureStatus {
        fun status(s: CaptureStanding, headline: String, detail: String) =
            AppCaptureStatus(i.pkg, i.label, i.versionName, s, headline, detail)
        val live = i.routeOwner == "ENGINE_B_MUTED"
        val optOut = i.optOutReason ?: i.routeReason?.takeIf { it in OPT_OUT_ROUTE_REASONS }
        val version = i.versionName?.let { " ${it}" }.orEmpty()
        return when {
            i.systemOnlyChoice -> status(CaptureStanding.SYSTEM_ONLY_BY_CHOICE, "System effects (your choice)",
                "You set this app to System effects. Change it in the app's engine choice.")
            i.manifestAllows == false -> status(CaptureStanding.SYSTEM_ONLY_BY_APP, "System effects only: this app forbids capture",
                reasonText("APP_CAPTURE_DISABLED") + " Equalizer curve and dynamics still apply through system effects.")
            live -> status(CaptureStanding.FULL, "Full audiophile engine, active now",
                "Svan is carrying this app's audio through the capture chain.")
            optOut != null -> status(CaptureStanding.SYSTEM_ONLY_BY_APP, "System effects only: capture is blocked",
                reasonText(optOut) + " This is the app's choice for the installed version$version" +
                    (i.optOutAtMs?.let { " (seen ${ago(nowMs - it)})" } ?: "") + ". Your equalizer curve and dynamics still apply through system effects.")
            i.heardOnThisVersion -> status(CaptureStanding.FULL, "Full audiophile engine available",
                "Capture was heard from this version${if (i.engineRunning) "" else ". Start the audiophile engine to use it"}.")
            i.routeReason in CHECKING_ROUTE_REASONS -> status(CaptureStanding.UNCONFIRMED, "Checking capture",
                "Svan is checking this app while it plays. System effects play meanwhile.")
            i.routeReason != null && i.routeOwner == "ENGINE_A" -> status(CaptureStanding.UNCONFIRMED, "Not confirmed yet",
                "Capture checks did not hear this app. That is not proof it forbids capture; Svan retries while it plays.")
            else -> status(CaptureStanding.NOT_CHECKED, "Not checked yet",
                if (i.manifestAllows == true) "The app's manifest allows capture. Whether its audio stream does is only known while it plays with the audiophile engine running."
                else "Play something with the audiophile engine running and Svan will check.")
        }
    }

    internal fun ago(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 90 -> "just now"
            s < 90 * 60 -> "${(s + 30) / 60} min ago"
            s < 36 * 3600 -> "${(s + 1800) / 3600} h ago"
            else -> "${(s + 43200) / 86400} days ago"
        }
    }

    /** The card lists blocked apps first, because those are the ones that surprise people. */
    val order: Comparator<AppCaptureStatus> = compareBy<AppCaptureStatus> {
        when (it.standing) {
            CaptureStanding.SYSTEM_ONLY_BY_APP -> 0
            CaptureStanding.UNCONFIRMED -> 1
            CaptureStanding.FULL -> 2
            CaptureStanding.SYSTEM_ONLY_BY_CHOICE -> 3
            CaptureStanding.NOT_CHECKED -> 4
        }
    }.thenBy { it.label.lowercase() }
}

/** Reads the live evidence for each player Svan can see and applies [CaptureStatusRules]. */
internal object CaptureStatusBoard {
    fun collect(context: android.content.Context, nowMs: Long = System.currentTimeMillis()): List<AppCaptureStatus> {
        val pm = context.packageManager
        val compat = runCatching { SessionRouter.compat() }.getOrNull() ?: return emptyList()
        val routes = SessionRouter.snapshot.filter { !it.pkg.startsWith("uid:") }.associateBy { it.pkg }
        val heard = compat.all().filterValues { it == CaptureCompat.Verdict.CAPTURABLE.name }.keys
        val systemOnly = runCatching { SessionRouter.appPreferences().systemOnlyPackages() }.getOrDefault(emptySet())
        val candidates = (app.svan.diag.KnownPlayers.packages + routes.keys + heard + systemOnly).distinct()
        return candidates.mapNotNull { pkg ->
            val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
            val route = routes[pkg]
            val optOut = compat.optOut(pkg)
            CaptureStatusRules.evaluate(AppCaptureInputs(
                pkg = pkg,
                label = pm.getApplicationLabel(info).toString(),
                versionName = runCatching { pm.getPackageInfo(pkg, 0).versionName }.getOrNull(),
                systemOnlyChoice = pkg in systemOnly,
                manifestAllows = compat.declaration(pkg, info.uid)?.allowed,
                heardOnThisVersion = compat.cached(pkg) == CaptureCompat.Verdict.CAPTURABLE,
                optOutReason = optOut?.reason,
                optOutAtMs = optOut?.atMs,
                routeOwner = route?.owner?.name,
                routeReason = SessionRouter.reasonFor(pkg)?.name,
                engineRunning = CaptureService.isRunning,
            ), nowMs)
        }.sortedWith(CaptureStatusRules.order)
    }
}
