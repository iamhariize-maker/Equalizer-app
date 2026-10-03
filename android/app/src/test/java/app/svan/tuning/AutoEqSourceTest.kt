package app.svan.tuning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoEqSourceTest {

    @Test
    fun parsesPlainSourceEntry() {
        val e = AutoEqSource.parse("- [Sennheiser HD 650](./oratory1990/over-ear/Sennheiser%20HD%20650) by oratory1990")
        assertNotNull(e)
        e!!
        assertEquals("Sennheiser HD 650", e.name)
        assertEquals("oratory1990", e.source)
        assertEquals("over-ear", e.form)
        assertNull(e.rig)
        assertEquals("measurements/oratory1990/data/over-ear/Sennheiser HD 650.csv", e.measurementPath)
        assertEquals("results/oratory1990/over-ear/Sennheiser HD 650", e.resultPath)
        assertTrue(e.supportsCustomTargets)
    }

    @Test
    fun parsesRigAndParenthesesInName() {
        val e = AutoEqSource.parse("- [1MORE Aero (ANC Off)](./HypetheSonics/GRAS%20RA0045%20in-ear/1MORE%20Aero%20(ANC%20Off)) by HypetheSonics on GRAS RA0045")!!
        assertEquals("1MORE Aero (ANC Off)", e.name)
        assertEquals("in-ear", e.form)
        assertEquals("GRAS RA0045", e.rig)
        assertEquals("measurements/HypetheSonics/data/in-ear/GRAS RA0045/1MORE Aero (ANC Off).csv", e.measurementPath)
    }

    @Test
    fun crinacleOffersOnlyThePublishedProfile() {
        val e = AutoEqSource.parse("- [1Custom SA02](./crinacle/711%20in-ear/1Custom%20SA02) by crinacle on 711")!!
        assertFalse(e.hasRawData)
        assertEquals(listOf(Signature.PUBLISHED), Signature.available(e))
    }

    @Test
    fun signaturesMapToRealTargetFiles() {
        val over = AutoEqSource.parse("- [HD 600](./oratory1990/over-ear/HD%20600) by oratory1990")!!
        val inEar = AutoEqSource.parse("- [Blessing 2](./Super%20Review/in-ear/Blessing%202) by Super Review")!!
        val inner = AutoEqSource.parse("- [HD 800](./Innerfidelity/over-ear/HD%20800) by Innerfidelity")!!
        assertEquals("Harman over-ear 2018", Signature.HARMAN.targetFile(over))
        assertEquals("Diffuse field ISO 11904-1", Signature.NEUTRAL.targetFile(over))
        assertEquals("Harman in-ear 2019 without bass", Signature.HARMAN_LIGHT_BASS.targetFile(inEar))
        assertEquals("AutoEq in-ear", Signature.AUTOEQ_IN_EAR.targetFile(inEar))
        assertNull(Signature.NEUTRAL.targetFile(inEar))
        assertEquals("Innerfidelity Harman over-ear 2018", Signature.HARMAN.targetFile(inner))
        assertNull(Signature.ORATORY.targetFile(inner)) // no Innerfidelity-compensated oratory target
    }

    @Test
    fun specialRigsOnlyGetThePublishedProfile() {
        val e = AutoEqSource.parse("- [AirPods Pro](./Rtings/Bruel%20%26%20Kjaer%205128%20in-ear/AirPods%20Pro) by Rtings on Bruel & Kjaer 5128")!!
        assertEquals("Bruel & Kjaer 5128", e.rig)
        assertEquals(listOf(Signature.PUBLISHED), Signature.available(e))
    }

    @Test
    fun ignoresNonEntryLines() {
        assertNull(AutoEqSource.parse("# Index"))
        assertNull(AutoEqSource.parse("This is a list of all equalization profiles."))
    }
}
