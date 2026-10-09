package app.svan.diag

import android.content.Context
import android.content.pm.PackageManager

/** Players the diagnostic offers by name. Only ones Svan can see (its package-visibility list) can appear. */
object KnownPlayers {
    val packages: List<String> = listOf(
        "com.spotify.music", "com.google.android.apps.youtube.music", "com.amazon.mp3", "com.google.android.youtube",
        "deezer.android.app", "com.aspiro.tidal", "com.apple.android.music", "com.soundcloud.android", "com.pandora.android",
        "org.videolan.vlc", "com.maxmpz.audioplayer", "com.neutroncode.mp", "com.hiby.music", "com.spotify.lite",
    )

    /** (package, label) for the apps the user probably wants to test: those Svan is routing now, then installed known players. */
    fun candidates(context: Context, routed: List<String>): List<Pair<String, String>> {
        val pm = context.packageManager
        fun label(pkg: String): String? = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
        val ordered = (routed.filter { !it.startsWith("uid:") } + packages).distinct()
        return ordered.mapNotNull { p -> label(p)?.let { p to it } }.ifEmpty { listOf("com.spotify.music" to "Spotify") }
    }
}
