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

    /**
     * The verdict belongs to one installed version of the app. An update can change how its audio
     * reaches the mixer, so an older verdict must not outlive the version it was measured on.
     */
    fun key(pkg: String): String {
        val info = runCatching { packages.getPackageInfo(pkg, 0) }.getOrNull()
        return CaptureVerdictKey.of(pkg, info?.longVersionCode, info?.lastUpdateTime)
    }

    fun cached(pkg: String): Verdict? = prefs.getString(key(pkg), null)?.let(Verdict::valueOf)

    fun remember(pkg: String, v: Verdict) = prefs.edit().putString(key(pkg), v.name).apply()

    /** Records that [pkg] was playing (as the audio service reported) while its capture stayed silent. Returns the count for this version. */
    fun recordSilentPlayback(pkg: String): Int {
        val k = key(pkg)
        val count = strikes.getInt(k, 0) + 1
        strikes.edit().putInt(k, count).apply()
        return count
    }

    fun all(): Map<String, String> = prefs.all.mapValues { it.value.toString() }

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
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUid(uid)
            .build()
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
                if (!stillActive()) error("capture check cancelled")
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
            .setAudioPlaybackCaptureConfig(AudioPlaybackCaptureConfiguration.Builder(projection).addMatchingUid(uid).build())
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
    }
}

/** Verdict key: the package plus the installed version it was measured on (plain package if unknown). */
internal object CaptureVerdictKey {
    fun of(pkg: String, versionCode: Long?, updatedMs: Long?): String =
        if (versionCode == null || updatedMs == null) pkg else "$pkg@$versionCode@$updatedMs"
}

/**
 * Silent captures confirmed while the app plays. One can be a stream, an ad or a track; two on the same
 * app version mean Engine B cannot hear it on this phone, so Engine B stops muting it for that version.
 */
internal object SilentStrikes {
    const val LIMIT = 2
    fun blocks(count: Int): Boolean = count >= LIMIT
}
