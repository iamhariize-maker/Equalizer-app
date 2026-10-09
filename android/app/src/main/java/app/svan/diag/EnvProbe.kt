package app.svan.diag

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import app.svan.CaptureService
import app.svan.DetectionSetup
import app.svan.PlaybackSessions
import app.svan.SessionRouter
import app.svan.ShizukuAudioReports
import app.svan.SystemEqService
import java.io.File
import java.security.MessageDigest

/**
 * Read-only measurements of the phone and of one player app. Every call is guarded: a ROM that refuses one
 * query must not stop the others, and a refusal is itself reported (as "unavailable"), never hidden.
 */
object EnvProbe {
    private val interestingProp = Regex(
        "(?i)(hios|tran|transsion|hyper|miui|mi\\.os|oppo|oplus|realme|vivo|samsung|oneui|emui|magic|audio|dolby|dts|mtk|mediatek|" +
            "board\\.platform|ro\\.hardware|treble|vndk|first_api|build\\.version\\.(release|sdk|security_patch|incremental)|" +
            "build\\.display|build\\.fingerprint|build\\.flavor|product\\.(manufacturer|brand|model|device|name))")
    private val propFiles = listOf("/system/build.prop", "/vendor/build.prop", "/product/build.prop",
        "/system_ext/build.prop", "/odm/etc/build.prop", "/vendor/odm/etc/build.prop")

    /** `ro.*` keys that identify the build and its skin, from the readable build.prop files. Capped. */
    fun buildProps(): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (path in propFiles) {
            runCatching {
                File(path).useLines { lines ->
                    for (line in lines) {
                        if (line.startsWith("#") || '=' !in line) continue
                        val key = line.substringBefore('=').trim()
                        if (interestingProp.containsMatchIn(key) && key !in out) out[key.lowercase()] = line.substringAfter('=').trim().take(120)
                        if (out.size >= 140) return out
                    }
                }
            }
        }
        return out
    }

    fun collect(context: Context, props: Map<String, String>): EnvFacts {
        val pm = context.packageManager
        val pkg = context.packageName
        val power = context.getSystemService(PowerManager::class.java)
        val bucket = runCatching {
            when (context.getSystemService(UsageStatsManager::class.java).appStandbyBucket) {
                10 -> "ACTIVE"; 20 -> "WORKING_SET"; 30 -> "FREQUENT"; 40 -> "RARE"; 45 -> "RESTRICTED"; else -> "OTHER"
            }
        }.getOrNull()
        fun globalInt(name: String): Boolean? = runCatching { Settings.Global.getInt(context.contentResolver, name, -1) }.getOrNull()
            ?.let { if (it < 0) null else it == 1 }
        val shell = ShizukuAudioReports.state.value
        return EnvFacts(
            sdk = Build.VERSION.SDK_INT,
            oem = Oem.detect(Build.MANUFACTURER, Build.BRAND, props),
            developerOptionsOn = globalInt(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED),
            adbEnabled = globalInt(Settings.Global.ADB_ENABLED),
            wirelessAdbEnabled = globalInt("adb_wifi_enabled"),
            recordAudioGranted = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            dumpGranted = PlaybackSessions.hasDumpPermission(context),
            shizukuInstalled = runCatching { pm.getPackageInfo("moe.shizuku.privileged.api", 0); true }.getOrDefault(false),
            shizukuRunning = runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false),
            shizukuPermitted = runCatching { rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false),
            reportAccess = PlaybackSessions.hasReportAccess(context),
            reportRoute = when {
                PlaybackSessions.hasDumpPermission(context) -> "app DUMP grant"
                ShizukuAudioReports.ready -> "Shizuku ${shell.route ?: ""}".trim()
                else -> shell.stage.name.lowercase() + (shell.detail.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
            },
            batteryOptimizationIgnored = runCatching { power.isIgnoringBatteryOptimizations(pkg) }.getOrNull(),
            backgroundRestricted = runCatching { context.getSystemService(ActivityManager::class.java).isBackgroundRestricted }.getOrNull(),
            standbyBucket = bucket,
            powerSave = runCatching { power.isPowerSaveMode }.getOrNull(),
            serviceRunning = SystemEqService.isRunning,
            serviceUptimeMs = SignalLedger.serviceStartedMs.takeIf { it > 0 && SystemEqService.isRunning }?.let { System.currentTimeMillis() - it },
            killedByAndroid = SystemEqService.wasKilledByAndroid(context),
            captureRunning = CaptureService.isRunning,
            recordAudioOp = opMode(context, "android:record_audio"),
            runInBackgroundOp = opMode(context, "android:run_in_background"),
        )
    }

    private fun opMode(context: Context, op: String): String? = runCatching {
        val mode = context.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(op, Process.myUid(), context.packageName)
        when (mode) { 0 -> "allow"; 1 -> "ignore"; 2 -> "error"; 3 -> "default"; 4 -> "foreground"; else -> "mode $mode" }
    }.getOrNull()

    fun target(context: Context, pkg: String): TargetFacts {
        val pm = context.packageManager
        val info = runCatching {
            @Suppress("DEPRECATION") pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrNull() ?: return TargetFacts(pkg, null, installed = false)
        val app = info.applicationInfo
        val signer = runCatching {
            val bytes = info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            bytes?.let { MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) }.take(16) }
        }.getOrNull()
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).let { "installer=${it.installingPackageName} initiator=${it.initiatingPackageName}" }
            else @Suppress("DEPRECATION") "installer=${pm.getInstallerPackageName(pkg)}"
        }.getOrNull()
        val declaration = runCatching { app?.let { SessionRouter.compat().declaration(pkg, it.uid) } }.getOrNull()
        return TargetFacts(
            pkg = pkg,
            label = runCatching { app?.let { pm.getApplicationLabel(it).toString() } }.getOrNull(),
            installed = true,
            versionName = info.versionName,
            versionCode = info.longVersionCode,
            uid = app?.uid,
            installer = installer,
            stopped = app?.let { it.flags and ApplicationInfo.FLAG_STOPPED != 0 },
            enabled = app?.enabled,
            system = app?.let { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 },
            targetSdk = app?.targetSdkVersion,
            signerPrefix = signer,
            manifestAllows = declaration?.allowed,
            manifestSummary = declaration?.summary(),
        )
    }

    /** Effects registered with the audio framework. Vendor effects (not by the AOSP) can sit on a player's session. */
    fun installedEffects(): List<String> = runCatching {
        AudioEffect.queryEffects().orEmpty().map { d ->
            "${d.name} · type=${d.type} · by ${d.implementor}"
        }
    }.getOrDefault(emptyList())

    fun oemEffects(effects: List<String>): List<String> =
        effects.filterNot { it.contains("The Android Open Source Project", ignoreCase = true) }

    fun outputDevices(context: Context): List<String> = runCatching {
        context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { d ->
            "${typeName(d.type)} · ${d.productName} · rates=${d.sampleRates.joinToString("/").ifEmpty { "any" }}"
        }
    }.getOrDefault(emptyList())

    private fun typeName(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth a2dp"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth sco"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "usb headset"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "usb device"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "usb accessory"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble headset"
        else -> "type $type"
    }

    fun audioFacts(context: Context): Map<String, String> {
        val am = context.getSystemService(AudioManager::class.java)
        fun prop(key: String) = runCatching { am.getProperty(key) }.getOrNull() ?: "unavailable"
        return linkedMapOf(
            "output sample rate (mixer)" to prop(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE),
            "output frames per buffer" to prop(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER),
            "low-latency feature" to runCatching { context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY).toString() }.getOrDefault("unavailable"),
            "pro-audio feature" to runCatching { context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO).toString() }.getOrDefault("unavailable"),
            "music active (public API)" to runCatching { am.isMusicActive.toString() }.getOrDefault("unavailable"),
            "active playback configs (public API)" to runCatching { am.activePlaybackConfigurations.size.toString() }.getOrDefault("unavailable"),
            "active recordings visible to Svan" to runCatching { am.activeRecordingConfigurations.size.toString() }.getOrDefault("unavailable"),
        )
    }

    /** How the system currently ranks Svan's own process: capture is silenced or allowed by process state. */
    fun ownImportance(context: Context): String? = runCatching {
        val pid = Process.myPid()
        context.getSystemService(ActivityManager::class.java).runningAppProcesses?.firstOrNull { it.pid == pid }?.let {
            when (it.importance) {
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND"
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FOREGROUND_SERVICE"
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE"
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE"
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED"
                else -> "importance ${it.importance}"
            }
        }
    }.getOrNull()

    fun notificationsEnabled(context: Context): Boolean? =
        runCatching { context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() }.getOrNull()

    fun processUptimeMs(): Long = runCatching { SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime() }.getOrDefault(-1L)

    fun setupSummary(): String = runCatching { DetectionSetup.diagnostics() }.getOrDefault("unavailable")
}
