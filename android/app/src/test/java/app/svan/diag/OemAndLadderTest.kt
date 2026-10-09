package app.svan.diag

import android.media.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OemAndLadderTest {
    @Test fun transsionPhonesAreRecognisedAsHiOS() {
        val oem = Oem.detect("TECNO", "TECNO", emptyMap())
        assertTrue(oem.family.startsWith("HiOS")); assertTrue(oem.aggressiveBackground); assertTrue(oem.tips.isNotEmpty())
    }

    @Test fun xiaomiBecomesHyperOsWhenTheBuildSaysSo() {
        assertTrue(Oem.detect("Xiaomi", "Redmi", mapOf("ro.mi.os.version.name" to "OS1")).family.startsWith("HyperOS"))
        assertTrue(Oem.detect("Xiaomi", "Redmi", mapOf("ro.miui.ui.version.name" to "V14")).family.startsWith("MIUI"))
    }

    @Test fun stockLikeBuildsHaveNoAggressiveLimits() {
        assertFalse(Oem.detect("Google", "google", emptyMap()).aggressiveBackground)
        assertFalse(Oem.detect("LGE", "lge", emptyMap()).aggressiveBackground)
    }

    @Test fun unknownMakersAreLabelledHonestly() {
        assertTrue(Oem.detect("Acme", "Acme", emptyMap()).family.startsWith("Unrecognised"))
    }

    @Test fun theLadderAlwaysStartsWithWhatSvanDoesToday() {
        val l = CaptureLab.ladder(false)
        assertEquals(TrialIds.UID_MEDIA_F32, l.first().id)
        assertEquals(7, l.size)
        assertTrue(l.none { it.disruption != Disruption.NONE })
    }

    @Test fun disruptiveTrialsAreOptInAndComeLast() {
        val l = CaptureLab.ladder(true)
        assertEquals(9, l.size)
        assertEquals(listOf(Disruption.EFFECT_OFF, Disruption.MUTED), l.takeLast(2).map { it.disruption })
    }

    @Test fun trialIdsAreUnique() {
        val ids = CaptureLab.ladder(true).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun theLadderCoversBroadAndNarrowScopesAndBothFormats() {
        val l = CaptureLab.ladder(false)
        assertTrue(l.any { it.allApps }); assertTrue(l.any { !it.allApps })
        assertTrue(l.any { it.encoding == AudioFormat.ENCODING_PCM_16BIT })
        assertTrue(l.any { it.usages == null })
    }
}
