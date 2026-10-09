package app.svan

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import java.util.concurrent.ConcurrentHashMap
import org.xmlpull.v1.XmlPullParser

/**
 * Records measured capture support and inspects the installed application's manifest.
 * A silent read is inconclusive: it never establishes a permanent app-level opt-out.
 * Before muting a source, the router measures it unmuted and then confirms the mute tap.
 */
class CaptureCompat(context: Context) {

    private val app = context.applicationContext

    // BLOCKED is retained only to recognise records made by older versions.
    enum class Verdict { CAPTURABLE, INCONCLUSIVE, BLOCKED }

    private val prefs = context.getSharedPreferences("capture_compat", Context.MODE_PRIVATE)
    /** Direct opt-out evidence seen for an installed version. Display only: routing re-checks live and never reads this. */
    private val observed = context.getSharedPreferences("capture_observed", Context.MODE_PRIVATE)
    private val packages = context.packageManager
    private val checks = ConcurrentHashMap<String, String>()
    private val declarations = ConcurrentHashMap<String, AppDeclaration>()

    data class AppDeclaration(val targetSdk: Int, val allowed: Boolean, val explicit: Boolean?) {
        fun summary() = "manifest capture=${if (allowed) "allowed" else "disabled"}, targetSdk=$targetSdk, declaration=${explicit ?: "Android default"}"
    }

    init {
        // Migrate silence-based BLOCKED records, including the owner's 0.5.9 lock, without discarding positives.
        val stale = CaptureRecords.discardable(prefs.all, ::installedVersion, CaptureEvidencePolicy::reusableStoredValue)
        if (stale.isNotEmpty()) prefs.edit().apply { stale.forEach(::remove) }.apply()
        context.getSharedPreferences("capture_silence", Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** The installed version, or null when the package is not installed or not visible to Svan (see the manifest's queries). */
    private fun installedVersion(pkg: String): InstalledVersion? =
        runCatching { packages.getPackageInfo(pkg, 0) }.getOrNull()?.let { InstalledVersion(it.longVersionCode, it.lastUpdateTime) }

    /**
     * The verdict belongs to one installed version of the app. An update can change how its audio
     * reaches the mixer, so an older verdict must not outlive the version it was measured on.
     */
    fun key(pkg: String): String {
        val version = installedVersion(pkg)
        return CaptureVerdictKey.of(pkg, version?.code, version?.updatedMs)
    }

    fun cached(pkg: String): Verdict? = CaptureRecords.verdict(prefs.getString(key(pkg), null))?.takeIf { it == Verdict.CAPTURABLE }

    fun remember(pkg: String, v: Verdict) {
        if (v == Verdict.CAPTURABLE) {
            prefs.edit().putString(key(pkg), v.name).apply()
            observed.edit().remove(key(pkg)).apply() // a heard stream outranks an older opt-out sighting
        }
    }

    data class OptOut(val reason: String, val atMs: Long)

    /**
     * Records that the manifest, the UID policy or the audio server's stream flags forbade capture for this
     * installed version. Never written for silence. Rewritten at most hourly while the same reason holds.
     */
    fun noteOptOut(pkg: String, reason: String, nowMs: Long = System.currentTimeMillis()) {
        val previous = optOut(pkg)
        if (previous != null && previous.reason == reason && nowMs - previous.atMs < 3_600_000) return
        observed.edit().putString(key(pkg), "$reason|$nowMs").apply()
    }

    fun optOut(pkg: String): OptOut? = observed.getString(key(pkg), null)?.split('|', limit = 2)?.let {
        val at = it.getOrNull(1)?.toLongOrNull() ?: return null
        OptOut(it[0], at)
    }

    /**
     * What the UI may show: one verdict per package, for its current version only. Storage keys such as
     * `com.spotify.music@146810608@1791220371629` never leave this class.
     */
    fun all(): Map<String, String> = CaptureRecords.current(prefs.all.filterValues(CaptureEvidencePolicy::reusableStoredValue), ::installedVersion)

    /** Uses public APIs and the verified base manifest, never a split manifest or another package's resources. */
    fun declaration(pkg: String, uid: Int): AppDeclaration? {
        val k = "${key(pkg)}:$uid"
        declarations[k]?.let { return it }
        return runCatching {
            val info = packages.getApplicationInfo(pkg, 0)
            if (info.uid != uid) return@runCatching null
            val resources = packages.getResourcesForApplication(info)
            for (cookie in 1..64) {
                val found = runCatching {
                    resources.assets.openXmlResourceParser(cookie, "AndroidManifest.xml").use { xml ->
                        var correctBase = false
                        while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                            if (xml.eventType == XmlPullParser.START_TAG) {
                                if (xml.name == "manifest") {
                                    correctBase = xml.getAttributeValue(null, "package") == pkg && xml.getAttributeValue(null, "split") == null
                                    if (!correctBase) break
                                } else if (correctBase && xml.name == "application") {
                                    val ns = "http://schemas.android.com/apk/res/android"
                                    val raw = xml.getAttributeValue(ns, "allowAudioPlaybackCapture")
                                    val explicit = when (raw) {
                                        null -> null
                                        "true" -> true
                                        "false" -> false
                                        else -> resources.getBoolean(xml.getAttributeResourceValue(ns, "allowAudioPlaybackCapture", 0))
                                    }
                                    return@use AppDeclaration(info.targetSdkVersion, CaptureEvidencePolicy.manifestAllows(info.targetSdkVersion, explicit), explicit)
                                }
                            }
                            xml.next()
                        }
                        null
                    }
                }.getOrNull()
                if (found != null) { declarations[k] = found; return@runCatching found }
            }
            null
        }.getOrNull()
    }

    fun diagnosticFor(pkg: String): String = checks.entries.filter { it.key.startsWith("$pkg|") }
        .sortedBy { it.key }.joinToString("\n") { it.value }.ifEmpty { "No capture samples measured in this process yet." }

    /** Forgets this app's positive history and current observations. */
    fun forget(pkg: String) {
        val own = { key: String -> CaptureVerdictKey.parse(key)?.pkg == pkg }
        prefs.edit().apply { prefs.all.keys.filter(own).forEach(::remove) }.apply()
        observed.edit().apply { observed.all.keys.filter(own).forEach(::remove) }.apply()
        checks.keys.filter { it.startsWith("$pkg|") }.forEach(checks::remove)
    }

    fun clear() {
        prefs.edit().clear().apply()
        observed.edit().clear().apply()
        checks.clear()
    }

    /**
     * Blocking: listens to [uid]'s capture for up to [timeoutMs]. Returns
     * CAPTURABLE as soon as finite audio arrives; silence is INCONCLUSIVE.
     * Nonblocking reads make cancellation and the deadline real even if the recorder supplies no frames.
     */
    @SuppressLint("MissingPermission") // RECORD_AUDIO checked before Engine B starts
    fun probe(projection: MediaProjection, uid: Int, timeoutMs: Long = 2500, stillActive: () -> Boolean = { true },
              pkg: String = "uid:$uid", sid: Int = 0, phase: String = "check"): Verdict {
        if (phase == "unmuted") checks.remove("$pkg|muted") // a previous attempt's P2 is not this attempt's proof
        var lease: CaptureRecorderGate.Lease? = null
        val rate = 48000
        var record: AudioRecord? = null
        var frames = 0L
        var peak = 0f
        var silenced: Boolean? = null
        var outcome = "opening recorder"
        val began = System.nanoTime()
        try {
            if (!stillActive()) throw ProbeCancelled()
            val policy = CaptureUidPolicies.read(app, uid)
            if (policy != null && policy and (PlaybackSession.FLAG_NO_MEDIA_PROJECTION or PlaybackSession.FLAG_NO_SYSTEM_CAPTURE) != 0) {
                outcome = "UID capture policy disabled (flag_mask=0x${policy.toUInt().toString(16)})"
                throw ProbePolicyDenied(policy)
            }
            // The audio server's effective flags for this very track can forbid capture while the player list shows 0x0.
            CaptureUidPolicies.streamMask(uid, sid)?.let { stream ->
                outcome = "audio server stream flags disable capture (flag_mask=0x${stream.toString(16)})"
                throw ProbePolicyDenied(stream)
            }
            lease = recorders.await("probe", active = stillActive)
            val config = playbackConfig(projection, listOf(uid))
            val input = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build(),
                )
                .setAudioPlaybackCaptureConfig(config)
                .build()
            record = input
            val buf = FloatArray(1024)
            check(input.state == AudioRecord.STATE_INITIALIZED) { "capture check recorder is not initialized" }
            input.startRecording()
            check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "capture check recorder did not start" }
            val recordingBegan = System.nanoTime()
            val deadline = recordingBegan + timeoutMs * 1_000_000
            while (System.nanoTime() < deadline) {
                if (!stillActive()) throw ProbeCancelled()
                val n = input.read(buf, 0, buf.size, AudioRecord.READ_NON_BLOCKING)
                if (n < 0) error("capture check read failed ($n)")
                frames += n / 2
                silenced = input.activeRecordingConfiguration?.isClientSilenced
                for (i in 0 until n) if (buf[i].isFinite()) peak = maxOf(peak, kotlin.math.abs(buf[i]))
                if (silenced != true && CaptureEvidencePolicy.tapSettled(phase, System.nanoTime() - recordingBegan) &&
                    CaptureEvidencePolicy.hasSignal(buf, n)) {
                    outcome = "audio"
                    return Verdict.CAPTURABLE
                }
                Thread.sleep(5)
            }
            outcome = if (silenced == true) "recorder silenced by Android" else if (frames == 0L) "no frames delivered" else "silent samples; policy unproven"
            if (silenced == true || frames == 0L) throw ProbeUnavailable(silenced == true)
            return Verdict.INCONCLUSIVE
        } catch (e: Exception) {
            if (outcome == "opening recorder") outcome = "${e.javaClass.simpleName}: recorder setup did not complete"
            throw e
        } finally {
            val detail = "capture check: $pkg sid=$sid uid=$uid phase=$phase result=$outcome frames=$frames peak=$peak elapsedMs=${(System.nanoTime()-began)/1_000_000} clientSilenced=${silenced ?: "unknown"} rate=$rate recordState=${record?.recordingState ?: -1} source=${record?.audioSource ?: -1}"
            checks["$pkg|$phase"] = detail
            EqController.log(detail)
            record?.let { runCatching { it.stop() }; runCatching { it.release() } }
            lease?.close()
        }
    }

    /** Diagnostics: what one UID's capture actually delivers in [ms]. */
    @SuppressLint("MissingPermission")
    fun measure(projection: MediaProjection, uid: Int, ms: Long): String {
        val lease = recorders.await("diagnostic", active = { true })
        var opened: AudioRecord? = null
        try {
            val record = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build(),
                )
                .setAudioPlaybackCaptureConfig(playbackConfig(projection, listOf(uid)))
                .build()
            opened = record
            val buf = FloatArray(1024)
            var frames = 0L
            var peak = 0f
            var firstNonZeroMs = -1L
            record.startRecording()
            val t0 = System.nanoTime()
            while ((System.nanoTime() - t0) / 1_000_000 < ms) {
                val n = record.read(buf, 0, buf.size, AudioRecord.READ_NON_BLOCKING)
                if (n < 0) return "read error $n"
                for (i in 0 until n) {
                    if (!buf[i].isFinite()) continue
                    val a = kotlin.math.abs(buf[i])
                    if (a > peak) peak = a
                    if (a > 0f && firstNonZeroMs < 0) firstNonZeroMs = (System.nanoTime() - t0) / 1_000_000
                }
                frames += n / 2
                Thread.sleep(5)
            }
            return "frames=$frames peak=%.4f firstAudio=%s".format(peak, if (firstNonZeroMs < 0) "never" else "${firstNonZeroMs}ms")
        } finally {
            opened?.let { runCatching { it.stop() }; runCatching { it.release() } }
            lease.close()
        }
    }

    companion object {
        internal val recorders = CaptureRecorderGate()
        /** Usage list for the main (mixed) capture. */
        val MIX_USAGES = intArrayOf(AudioAttributes.USAGE_MEDIA)

        /**
         * The one capture filter: the permitted usages plus exactly these UIDs. The compatibility check and the
         * main recorder both build it here, so a check can never hear audio the engine would then exclude.
         */
        fun playbackConfig(projection: MediaProjection, uids: Collection<Int>): AudioPlaybackCaptureConfiguration =
            AudioPlaybackCaptureConfiguration.Builder(projection).also { b ->
                MIX_USAGES.forEach { b.addMatchingUsage(it) }
                uids.forEach { b.addMatchingUid(it) }
            }.build()
    }
}

/** No data or Android explicitly silenced the recorder: this cannot become an app-policy verdict. */
internal class ProbeUnavailable(val androidSilenced: Boolean) : IllegalStateException(
    if (androidSilenced) "Android reports capture recorder silenced" else "capture recorder delivered no frames")

/** A capture check that was stopped on purpose (capture ended, or the player went away); not a verdict. */
internal class ProbeCancelled : IllegalStateException("capture check cancelled")

internal data class InstalledVersion(val code: Long, val updatedMs: Long)

/** Verdict key: the package plus the installed version it was measured on (plain package if unknown). */
internal object CaptureVerdictKey {
    fun of(pkg: String, versionCode: Long?, updatedMs: Long?): String =
        if (versionCode == null || updatedMs == null) pkg else "$pkg@$versionCode@$updatedMs"

    class Parsed(val pkg: String, val version: InstalledVersion?)

    /** Package names never contain `@`, so a stored key is a plain package or `package@versionCode@updateTime`. Null = malformed. */
    fun parse(key: String): Parsed? {
        val parts = key.split('@')
        val pkg = parts[0]
        if (pkg.isEmpty() || pkg.length > 255 || pkg.any { it.isWhitespace() || it.isISOControl() }) return null
        return when (parts.size) {
            1 -> Parsed(pkg, null)
            3 -> {
                val code = parts[1].toLongOrNull() ?: return null
                val updated = parts[2].toLongOrNull() ?: return null
                Parsed(pkg, InstalledVersion(code, updated))
            }
            else -> null
        }
    }
}

/**
 * Reads stored capture records for the UI and for cleanup, without ever treating a storage key as an app.
 * Svan can resolve versions only for the packages its manifest declares visible, so "no version" means
 * "cannot verify", not "uninstalled": such records are left alone and read as a plain package.
 */
internal object CaptureRecords {
    fun verdict(value: Any?): CaptureCompat.Verdict? =
        (value as? String)?.let { name -> CaptureCompat.Verdict.entries.firstOrNull { it.name == name } }

    /** Package → verdict name for records that apply to the installed version. */
    fun current(stored: Map<String, *>, installed: (String) -> InstalledVersion?): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for ((key, value) in stored) {
            val verdict = verdict(value) ?: continue
            val parsed = CaptureVerdictKey.parse(key) ?: continue
            if (parsed.pkg.startsWith("uid:")) continue // an unresolved player is not an app
            val now = installed(parsed.pkg)
            when {
                parsed.version != null -> if (now == parsed.version) out[parsed.pkg] = verdict.name
                now == null -> out.putIfAbsent(parsed.pkg, verdict.name) // version unknowable: the plain key is what lookups use
            }
        }
        return out
    }

    /** Keys that can never be read again: damaged, an older version of a visible app, or a plain key a visible app shadows. */
    fun discardable(stored: Map<String, *>, installed: (String) -> InstalledVersion?, validValue: (Any?) -> Boolean): Set<String> =
        stored.keys.filterTo(linkedSetOf()) { key ->
            if (!validValue(stored[key])) return@filterTo true
            val parsed = CaptureVerdictKey.parse(key) ?: return@filterTo true
            if (parsed.pkg.startsWith("uid:")) return@filterTo false
            val now = installed(parsed.pkg) ?: return@filterTo false
            parsed.version != now // plain (null) differs from a resolvable version; an older version differs too
        }
}
