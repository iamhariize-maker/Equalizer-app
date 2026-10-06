package app.svan

/** Discovery is not permission to process every sound a phone makes. Recognition never overrides this policy. */
object MusicSourcePolicy {
    private val utilityPackages = setOf(
        "com.rapido.passenger", "com.rapido.rider", "com.rapido.captain",
        "com.android.systemui", "com.android.settings", "com.android.phone",
        "com.android.facelock", "com.google.android.faceunlock",
    )
    private val musicPackages = setOf(
        "com.spotify.music", "com.google.android.apps.youtube.music", "com.google.android.youtube",
        "com.amazon.mp3", "com.apple.android.music", "com.aspiro.tidal", "deezer.android.app",
        "com.soundcloud.android", "com.neutroncode.mp", "com.neutroncode.mpeval",
        "com.maxmpz.audioplayer", "com.hiby.music", "org.videolan.vlc",
    )
    fun excludedPackage(pkg: String): Boolean = pkg.lowercase() in utilityPackages
    fun exclusion(s: PlaybackSession): String? = when {
        s.uid >= 0 && s.uid % 100_000 < 10_000 -> "System sound"
        excludedPackage(s.packageName) -> "Utility app"
        !s.usageCapturable -> "Notification, call, alarm or other non-media sound"
        s.contentType == "CONTENT_TYPE_SONIFICATION" -> "Interface sound"
        s.playerType.contains("soundpool", ignoreCase = true) || s.playerType == "3" -> "Short sound effect"
        else -> null
    }
    fun immediate(s: PlaybackSession): Boolean = s.contentType == "CONTENT_TYPE_MUSIC" ||
        s.packageName in musicPackages || s.usage == "USAGE_GAME"
}

/** Brief unknown players stay untouched; real sustained playback earns admission. Worker-confined. */
class MusicSourceGate {
    private data class Observation(val uid: Int, val pkg: String, val since: Long)
    private val waiting = mutableMapOf<Int, Observation>()
    fun admit(s: PlaybackSession, nowMs: Long, alreadyRouted: Boolean): Boolean {
        if (MusicSourcePolicy.exclusion(s) != null) { waiting.remove(s.sessionId); return false }
        if (alreadyRouted || MusicSourcePolicy.immediate(s)) { waiting.remove(s.sessionId); return true }
        if (s.state != "started") { waiting.remove(s.sessionId); return false }
        val old=waiting[s.sessionId]
        if(old==null || old.uid!=s.uid || old.pkg!=s.packageName) {
            waiting[s.sessionId]=Observation(s.uid,s.packageName,nowMs);return false
        }
        return nowMs-old.since>=1500L
    }
    fun retain(seen: Set<Int>) { waiting.keys.retainAll(seen) }
    fun forget(sid: Int) { waiting.remove(sid) }
    fun clear() { waiting.clear() }
}
