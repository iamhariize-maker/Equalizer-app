package app.svan

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection

/**
 * Decides, once per app, whether Engine B can process it.
 *
 * Apps can opt out of playback capture (reported: Spotify, Chrome, SoundCloud).
 * Muting such an app would silence it completely, because the capture would
 * never deliver its audio. A blocked capture delivers exact digital zeros, so we
 * mute the app, open a capture for only its UID, and listen briefly:
 *  - any non-zero sample → CAPTURABLE (stay muted; Engine B renders it)
 *  - only zeros while it is playing → BLOCKED (unmute; Engine A EQs it instead)
 * Decisions are cached per app version, so the brief silence happens once per version. A later
 * silent capture while the app plays is recorded as a strike; see [SilentStrikes].
 */
class CaptureCompat(context: Context) {

    enum class Verdict { CAPTURABLE, BLOCKED }

    private val prefs = context.getSharedPreferences("capture_compat", Context.MODE_PRIVATE)
    private val strikes = context.getSharedPreferences("capture_silence", Context.MODE_PRIVATE)
    private val packages = context.packageManager

    init {
        // Drop records that can never be read again (see [CaptureRecords]); never touches a current verdict.
        val stale = CaptureRecords.discardable(prefs.all, ::installedVersion) { CaptureRecords.verdict(it) != null }
        if (stale.isNotEmpty()) prefs.edit().apply { stale.forEach(::remove) }.apply()
        val staleStrikes = CaptureRecords.discardable(strikes.all, ::installedVersion) { it is Int && it > 0 }
        if (staleStrikes.isNotEmpty()) strikes.edit().apply { staleStrikes.forEach(::remove) }.apply()
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

    fun cached(pkg: String): Verdict? = CaptureRecords.verdict(prefs.getString(key(pkg), null))

    fun remember(pkg: String, v: Verdict) = prefs.edit().putString(key(pkg), v.name).apply()

    /** Records that [pkg] was playing (as the audio service reported) while its capture stayed silent. Returns the count for this version. */
    fun recordSilentPlayback(pkg: String): Int {
        val k = key(pkg)
        val count = strikes.getInt(k, 0) + 1
        strikes.edit().putInt(k, count).apply()
        return count
    }

    /**
     * What the UI may show: one verdict per package, for its current version only. Storage keys such as
     * `com.spotify.music@146810608@1791220371629` never leave this class.
     */
    fun all(): Map<String, String> = CaptureRecords.current(prefs.all, ::installedVersion)

    /** Forgets this app's verdict and silence strikes (every version). */
    fun forget(pkg: String) {
        val own = { key: String -> CaptureVerdictKey.parse(key)?.pkg == pkg }
        prefs.edit().apply { prefs.all.keys.filter(own).forEach(::remove) }.apply()
        strikes.edit().apply { strikes.all.keys.filter(own).forEach(::remove) }.apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
        strikes.edit().clear().apply()
    }

    /**
     * Blocking: listens to [uid]'s capture for up to [timeoutMs]. Returns
     * CAPTURABLE as soon as real audio arrives, BLOCKED if only zeros came.
     * Call off the main thread, after the app's session has been muted.
     */
    @SuppressLint("MissingPermission") // RECORD_AUDIO checked before Engine B starts
    fun probe(projection: MediaProjection, uid: Int, timeoutMs: Long = 2500, stillActive: () -> Boolean = { true }): Verdict {
        val config = playbackConfig(projection, listOf(uid))
        val rate = 48000
        val record = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build(),
            )
            .setAudioPlaybackCaptureConfig(config)
            .build()
        val buf = FloatArray(1024)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "capture check recorder is not initialized" }
            record.startRecording()
            while (System.nanoTime() < deadline) {
                if (!stillActive()) throw ProbeCancelled()
                val n = record.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n < 0) error("capture check read failed ($n)")
                for (i in 0 until n) if (buf[i] != 0f) return Verdict.CAPTURABLE
            }
            return Verdict.BLOCKED
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    /** Diagnostics: what one UID's capture actually delivers in [ms]. */
    @SuppressLint("MissingPermission")
    fun measure(projection: MediaProjection, uid: Int, ms: Long): String {
        val record = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build(),
            )
            .setAudioPlaybackCaptureConfig(playbackConfig(projection, listOf(uid)))
            .build()
        val buf = FloatArray(1024)
        var frames = 0L
        var peak = 0f
        var firstNonZeroMs = -1L
        val t0 = System.nanoTime()
        try {
            record.startRecording()
            while ((System.nanoTime() - t0) / 1_000_000 < ms) {
                val n = record.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n < 0) return "read error $n"
                for (i in 0 until n) {
                    val a = kotlin.math.abs(buf[i])
                    if (a > peak) peak = a
                    if (a > 0f && firstNonZeroMs < 0) firstNonZeroMs = (System.nanoTime() - t0) / 1_000_000
                }
                frames += n / 2
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
        return "frames=$frames peak=%.4f firstAudio=%s".format(peak, if (firstNonZeroMs < 0) "never" else "${firstNonZeroMs}ms")
    }

    companion object {
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

/**
 * Silent captures confirmed while the app plays. One can be a stream, an ad or a track; two on the same
 * app version mean Engine B cannot hear it on this phone, so Engine B stops muting it for that version.
 */
internal object SilentStrikes {
    const val LIMIT = 2
    fun blocks(count: Int): Boolean = count >= LIMIT
}
