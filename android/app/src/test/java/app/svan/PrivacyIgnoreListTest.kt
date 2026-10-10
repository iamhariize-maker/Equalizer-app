package app.svan

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner decision (10 and 11 October 2026): private apps never reach Svan, on any path; music platforms always can. */
class PrivacyIgnoreListTest {
    private fun media(pkg: String, uid: Int = 10234, content: String = "CONTENT_TYPE_UNKNOWN", usage: String = "USAGE_MEDIA") =
        PlaybackSession(7, uid, usage, "started", 0, pkg, contentType = content)

    @After fun resetResolver() { MusicSourcePolicy.packagesForUid = { emptyList() } }

    @Test fun eachCategoryIsRefused() {
        val samples = mapOf(
            "com.whatsapp" to MusicSourcePolicy.PrivateCategory.MESSAGING,
            "com.whatsapp.w4b" to MusicSourcePolicy.PrivateCategory.MESSAGING,
            "com.facebook.orca" to MusicSourcePolicy.PrivateCategory.MESSAGING,
            "com.Slack" to MusicSourcePolicy.PrivateCategory.MESSAGING,
            "com.instagram.android" to MusicSourcePolicy.PrivateCategory.SOCIAL,
            "com.rapido.passenger" to MusicSourcePolicy.PrivateCategory.RIDE,
            "com.phonepe.app" to MusicSourcePolicy.PrivateCategory.PAYMENTS,
            "com.google.android.googlequicksearchbox" to MusicSourcePolicy.PrivateCategory.ASSISTANT,
            "com.sec.android.app.voicenote" to MusicSourcePolicy.PrivateCategory.RECORDER,
            "com.google.android.GoogleCamera" to MusicSourcePolicy.PrivateCategory.CAMERA,
            "com.google.android.dialer" to MusicSourcePolicy.PrivateCategory.TELEPHONY,
        )
        val gate = MusicSourceGate()
        for ((pkg, category) in samples) {
            assertEquals(pkg, category, MusicSourcePolicy.privateCategory(pkg))
            // Music content or a game usage must not let a private app skip the list.
            for (s in listOf(media(pkg), media(pkg, content = "CONTENT_TYPE_MUSIC"), media(pkg, usage = "USAGE_GAME"))) {
                assertNotNull(pkg, MusicSourcePolicy.exclusion(s))
                assertFalse(pkg, gate.admit(s, 10_000L, alreadyRouted = true))
            }
            assertTrue(pkg, MusicSourcePolicy.excludedPackage(pkg, 10234))
        }
    }

    @Test fun musicPlatformsAreNeverIgnored() {
        for (pkg in listOf("com.google.android.youtube", "com.google.android.apps.youtube.music", "com.apple.android.music",
            "com.spotify.music", "com.gaana", "com.amazon.mp3", "com.aspiro.tidal", "deezer.android.app",
            "com.soundcloud.android", "com.neutroncode.mp", "org.videolan.vlc")) {
            assertNull(pkg, MusicSourcePolicy.privateCategory(pkg))
            assertNull(pkg, MusicSourcePolicy.exclusion(media(pkg)))
            assertTrue(pkg, MusicSourcePolicy.immediate(media(pkg)))
        }
    }

    @Test fun aUidSharedWithAPrivateAppIsIgnored() {
        MusicSourcePolicy.packagesForUid = { uid -> if (uid == 10500) listOf("com.example.player", "com.whatsapp") else emptyList() }
        assertNotNull(MusicSourcePolicy.exclusion(media("com.example.player", uid = 10500)))
        assertNotNull(MusicSourcePolicy.exclusion(media("uid:10500", uid = 10500)))
        assertTrue(MusicSourcePolicy.excludedPackage("com.example.player", 10500))
        assertNull(MusicSourcePolicy.exclusion(media("com.example.player", uid = 10501)))
    }

    @Test fun anUnresolvedUidIsNotDecidedByItsPlaceholderName() {
        assertNull(MusicSourcePolicy.privateCategory("uid:10600", 10600))
    }

    @Test fun lookalikesAreNotMatched() {
        for (pkg in listOf("com.whatsappy.app", "com.facebookish.player", "com.instagramfan.music", "org.telegramplayer")) {
            assertNull(pkg, MusicSourcePolicy.privateCategory(pkg))
        }
    }
}
