package app.svan

/** Discovery is not permission to process every sound a phone makes. Recognition never overrides this policy. */
object MusicSourcePolicy {
    /**
     * Owner decision (10 and 11 October 2026): Svan never processes, captures, lists or routes apps whose audio raises
     * privacy concerns. Changing this list is an owner decision. Entries ending in "." are package families (prefixes);
     * the rest match whole names. Matching ignores case. Music platforms in [musicPackages] are never on it.
     */
    enum class PrivateCategory(val label: String) {
        MESSAGING("Messaging and calls"), SOCIAL("Social video"), RIDE("Ride and delivery"), PAYMENTS("Payments"),
        ASSISTANT("Voice assistant"), RECORDER("Recorder"), CAMERA("Camera"), TELEPHONY("Phone calls"), SYSTEM("System app"),
    }

    private val privatePackages: Map<PrivateCategory, List<String>> = mapOf(
        PrivateCategory.MESSAGING to listOf(
            "com.whatsapp", "com.whatsapp.", "org.telegram.", "org.thunderdog.challegram", "org.thoughtcrime.securesms",
            "com.facebook.", "jp.naver.line.android", "com.viber.voip", "com.tencent.mm", "com.kakao.talk",
            "com.snapchat.android", "com.discord", "com.slack", "com.microsoft.teams", "us.zoom.", "com.skype.",
            "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.google.android.apps.tachyon",
            "com.google.android.apps.meetings", "com.google.android.talk", "com.google.android.apps.dynamite",
            "com.google.android.apps.googlevoice", "com.imo.android.imoim", "ch.threema.", "im.vector.app", "com.zing.zalo",
            "com.truecaller",
        ),
        PrivateCategory.SOCIAL to listOf(
            "com.instagram.", "com.zhiliaoapp.", "com.ss.android.ugc.", "com.twitter.", "com.reddit.", "com.linkedin.",
            "com.pinterest", "in.mohalla.",
        ),
        PrivateCategory.RIDE to listOf(
            "com.rapido.", "com.ubercab", "com.ubercab.", "com.olacabs.", "in.swiggy.", "com.zomato.", "com.application.zomato",
            "com.grofers.", "com.blinkit.", "com.zeptoconsumerapp", "com.grabtaxi.", "com.gojek.", "me.lyft.android", "ee.mtakso.",
        ),
        PrivateCategory.PAYMENTS to listOf(
            "com.phonepe.", "com.google.android.apps.nbu.paisa.user", "net.one97.", "in.org.npci.upiapp", "com.dreamplug.androidapp",
        ),
        PrivateCategory.ASSISTANT to listOf("com.google.android.googlequicksearchbox", "com.amazon.dee.", "com.samsung.android.bixby."),
        PrivateCategory.RECORDER to listOf("com.google.android.apps.recorder", "com.sec.android.app.voicenote", "com.android.soundrecorder"),
        PrivateCategory.CAMERA to listOf("com.google.android.googlecamera", "com.sec.android.app.camera", "com.android.camera", "com.android.camera2"),
        PrivateCategory.TELEPHONY to listOf(
            "com.android.phone", "com.android.server.telecom", "com.android.dialer", "com.google.android.dialer",
            "com.samsung.android.dialer", "com.android.incallui", "com.samsung.android.incallui",
        ),
        PrivateCategory.SYSTEM to listOf("com.android.systemui", "com.android.settings", "com.android.facelock", "com.google.android.faceunlock"),
    )

    /** Packages that share a uid; installed by [SessionRouter] with PackageManager. Empty when unknown. */
    @Volatile var packagesForUid: (Int) -> List<String> = { emptyList() }

    /** The category that keeps [pkg] away from Svan, or null. A music platform is never matched. */
    fun privateCategory(pkg: String): PrivateCategory? {
        val name = pkg.trim().lowercase()
        if (name.isEmpty() || name in musicPackages) return null
        return privatePackages.entries.firstOrNull { (_, names) ->
            names.any { if (it.endsWith(".")) name.startsWith(it) else name == it }
        }?.key
    }

    /**
     * Uid rule: a session is private if its package is, or if any package sharing its uid is. A "uid:NNNN" name is not
     * decided by name alone; the uid decides.
     */
    fun privateCategory(pkg: String, uid: Int): PrivateCategory? =
        (if (pkg.startsWith("uid:")) null else privateCategory(pkg))
            ?: if (uid >= 0 && uid % 100_000 >= 10_000)
                runCatching { packagesForUid(uid) }.getOrDefault(emptyList()).firstNotNullOfOrNull { privateCategory(it) }
            else null

    private val musicPackages = setOf(
        "com.spotify.music", "com.google.android.apps.youtube.music", "com.google.android.youtube",
        "com.amazon.mp3", "com.apple.android.music", "com.aspiro.tidal", "deezer.android.app",
        "com.soundcloud.android", "com.neutroncode.mp", "com.neutroncode.mpeval",
        "com.maxmpz.audioplayer", "com.hiby.music", "org.videolan.vlc", "com.gaana",
    )
    /** Broadcast path: the name is the sender's claim, so the uid is checked too when known. */
    fun excludedPackage(pkg: String, uid: Int = -1): Boolean = privateCategory(pkg, uid) != null
    fun exclusion(s: PlaybackSession): String? = when {
        s.uid >= 0 && s.uid % 100_000 < 10_000 -> "System sound"
        privateCategory(s.packageName, s.uid) != null -> "Private app (${privateCategory(s.packageName, s.uid)!!.label})"
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
