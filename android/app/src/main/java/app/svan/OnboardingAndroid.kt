package app.svan

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import rikka.shizuku.Shizuku

/** Reads existing, local discovery only. It never enumerates media titles or asks for a new grant. */
object OnboardingAndroid {
    fun debugging(context: Context): DebuggingState = DebuggingState.read(Build.VERSION.SDK_INT) { key ->
        // No default value: absent keys and denied reads must remain unknown.
        Settings.Global.getInt(context.contentResolver, key)
    }

    fun wizard(context: Context): WizardSnapshot = WizardSnapshot(
        installed = try { context.packageManager.getApplicationInfo(DetectionSetup.SHIZUKU_PACKAGE, 0); true }
            catch (_: PackageManager.NameNotFoundException) { false } catch (_: RuntimeException) { null },
        running = try { Shizuku.pingBinder() } catch (_: RuntimeException) { null },
        authorized = try { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (_: RuntimeException) { null },
        dumpGranted = PlaybackSessions.hasReportAccess(context), debugging = debugging(context),
    )

    fun working(context: Context): WorkingState {
        val st = DetectionMonitor.status.value
        val routes = SessionRouter.snapshot
        val attached = EqController.globalEq.attachedSessions
        val fresh = System.currentTimeMillis() - st.atMs < 15_000 && st.atMs > 0
        val records = (if (fresh) st.sessions.map { it.session } + st.unresolved else emptyList())
            .filter { it.uid != android.os.Process.myUid() && it.usageCapturable }
        val keys = records.map { it.packageName.ifEmpty { "uid:${it.uid}" } }.toSet() + routes.map { it.pkg }
        val players = keys.map { key ->
            val observed = records.filter { it.packageName.ifEmpty { "uid:${it.uid}" } == key }
            val appRoutes = routes.filter { it.pkg == key && it.uid != android.os.Process.myUid() }
            val a = appRoutes.firstOrNull { it.owner == SessionRouter.Owner.ENGINE_A && it.sessionId in attached &&
                SessionRouter.verification[it.sessionId] !in setOf(Verification.MISSING, Verification.SUSPENDED, Verification.BYPASSED) }
            val b = appRoutes.firstOrNull { it.owner == SessionRouter.Owner.ENGINE_B_MUTED && CaptureService.isRunning }
            val active = when {
                observed.any { it.state == "started" } || appRoutes.any { it.playing == true } -> true
                observed.isNotEmpty() && observed.all { it.state != "unknown" } -> false
                appRoutes.isNotEmpty() && appRoutes.all { it.playing == false } -> false
                else -> null
            }
            val shared = appRoutes.any { it.owner == SessionRouter.Owner.SHARED_OUTPUT } && SharedOutput.status.value.attached
            val engine = if (!SystemEqService.isRunning) null else when { shared -> UiEngine.SHARED_OUTPUT; b != null -> UiEngine.AUDIOPHILE; a != null -> UiEngine.SYSTEM_EFFECTS; else -> null }
            val attachable = appRoutes.any { it.sessionId > 0 } || observed.any { it.sessionId > 0 }
            val reason = when {
                shared -> "The shared-output effect is attached; this player's signal path is unverified."
                active == null && engine != null -> "Announced session routed; current playback association is unverified."
                !SystemEqService.isRunning -> "System equalizer is stopped."
                appRoutes.any { it.owner == SessionRouter.Owner.PROBING } -> "Checking capture; not connected yet."
                attachable && engine == null -> "Android has not applied a usable effect; Svan will retry."
                !attachable -> "Android has not exposed an attachable audio session."
                else -> null
            }
            WorkingPlayer(key, label(context, key), active, attachable, engine, reason)
        }
        // The public count is anonymous, not an app count. Never invent an identity from it.
        val publicOther = (if (fresh) st.publicActive else DetectionMonitor.publicActiveCount(context))
            ?.let { (it - if (CaptureService.isRunning) 1 else 0).coerceAtLeast(0) }
        if (players.isEmpty() && (publicOther ?: 0) > 0 && SharedOutput.status.value.attached && SystemEqService.isRunning) {
            return WorkingState.derive(publicOther, listOf(WorkingPlayer(WorkingState.ANONYMOUS_PLAYER,
                "Player unidentified", true, false, UiEngine.SHARED_OUTPUT,
                "Shared-output EQ is attached; this music's path is unverified.")), PlaybackSessions.hasReportAccess(context))
        }
        return WorkingState.derive(publicOther, players, PlaybackSessions.hasReportAccess(context))
    }

    private fun label(context: Context, key: String): String = if (key.startsWith("uid:") || key.startsWith("pid:")) "A player" else DetectionStatus.appLabel(key) { pkg ->
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }

    fun outputType(context: Context): String = runCatching {
        val types = context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        when {
            types.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= 31 && it in setOf(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER)) } -> "Bluetooth (connected; active route unverified)"
            types.any { it == AudioDeviceInfo.TYPE_USB_DEVICE || it == AudioDeviceInfo.TYPE_USB_HEADSET } -> "USB (connected; active route unverified)"
            types.any { it == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it == AudioDeviceInfo.TYPE_WIRED_HEADSET } -> "Wired (connected; active route unverified)"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER in types -> "Speaker available; active route unverified"
            else -> "Unknown"
        }
    }.getOrDefault("Unknown")

    fun summary(context: Context, working: WorkingState): String = diagnosticSummary(
        BuildConfig.VERSION_NAME, Build.VERSION.SDK_INT, working.kind, DetectionMonitor.scans,
        DetectionMonitor.status.value.publicActive, SessionRouter.snapshot.size, outputType(context),
        working.players.mapNotNull { it.engine }.toSet(), Build.VERSION.RELEASE)

    fun prefs(context: Context) = context.getSharedPreferences("svan_onboarding", Context.MODE_PRIVATE)
    fun dismissed(context: Context): Set<String> = prefs(context).getStringSet("dismissed_players", emptySet())?.toSet() ?: emptySet()
    fun dismiss(context: Context, key: String) { prefs(context).edit().putStringSet("dismissed_players", dismissed(context) + key).apply() }
    fun resetPrompts(context: Context) { prefs(context).edit().remove("dismissed_players").apply() }
}
