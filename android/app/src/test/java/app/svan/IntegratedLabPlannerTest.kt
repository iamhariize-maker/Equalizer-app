package app.svan

import app.svan.lab.IntegratedLab
import app.svan.lab.CaptureLabControls
import app.svan.lab.core.Planner
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class IntegratedLabPlannerTest {
    private fun model() = Planner.Model.read(File("src/main/assets/lab/models/48000_4096.bin").inputStream()).reduce(64)
    private fun planner() = Planner(Planner.EqTable(File("src/main/assets/lab/eq_coefficients.csv").inputStream()))
    @Test fun frozenResponseFitsActualTargetRatherThanDummyFilter() {
        val m = model()
        val p = planner().planResponse(m, { _ -> -6.0 }, false, 6.0)
        assertTrue(p.guardPassed)
        assertTrue(p.target.all { it == -6.0 })
        assertTrue(p.rms < .2)
        assertTrue(p.gains.all { it in -18.0..12.0 && it.isFinite() })
        assertTrue(p.attenuationDb <= -6)
    }
    @Test fun responseAdapterMatchesExistingPeakPlannerAndKeepsUniqueBins() {
        val m = model()
        val filters = listOf(Planner.Filter(0, 60.0, 6.0, 1.0))
        val a = planner().plan(m, filters, false, 6.0)
        val b = planner().planResponse(m, { f -> Planner.target(filters, f, m.rate) }, false, 6.0)
        assertArrayEquals(a.gains, b.gains, 1e-9)
        assertEquals(a.rms, b.rms, 1e-9)
        assertEquals(64, m.stops.toSet().size)
        assertTrue(m.stops.asList().zipWithNext().all { (a, b) -> b > a })
    }
    @Test fun interpolationClampsEdgesAndRetainsFractionalFrequencies() {
        val v = doubleArrayOf(-6.0, 0.0, 6.0)
        assertEquals(-6.0, IntegratedLab.interpolate(v, -1.0, 48000), 0.0)
        assertEquals(-3.0, IntegratedLab.interpolate(v, .005, 48000), 0.0)
        assertEquals(3.0, IntegratedLab.interpolate(v, kotlin.math.sqrt(240.0), 48000), 1e-12)
        assertEquals(6.0, IntegratedLab.interpolate(v, 30000.0, 48000), 0.0)
    }
    @Test fun frozenTargetRetainsANarrowFractionalBassPeak() {
        val frequencies = IntegratedLab.frozenFrequencies(48000)
        val filters = listOf(Planner.Filter(0, 31.5, 6.0, 8.0))
        val values = DoubleArray(frequencies.size) { Planner.target(filters, frequencies[it], 48000) }
        assertEquals(6.0, IntegratedLab.interpolate(values, 31.5, 48000), .01)
        assertEquals(0.0, frequencies.first(), 0.0)
        assertEquals(24000.0, frequencies.last(), 1e-8)
    }
    @Test fun nativeBassCoefficientsMatchEveryReferenceTableResponse() {
        val table = Planner.EqTable(File("src/main/assets/lab/eq_coefficients.csv").inputStream())
        for (rate in listOf(44100,48000)) for (gain in listOf(-6,-3,-2,0,3,6,9)) {
            val c = table.coefficients(rate,60,gain)
            for (hz in listOf(20.0,31.5,60.0,100.0,230.0,1000.0,20000.0)) {
                val w = 2*Math.PI*hz/rate
                val br=c[0]+c[1]*kotlin.math.cos(w)+c[2]*kotlin.math.cos(2*w)
                val bi=-c[1]*kotlin.math.sin(w)-c[2]*kotlin.math.sin(2*w)
                val ar=1+c[3]*kotlin.math.cos(w)+c[4]*kotlin.math.cos(2*w)
                val ai=-c[3]*kotlin.math.sin(w)-c[4]*kotlin.math.sin(2*w)
                val db=10*kotlin.math.log10((br*br+bi*bi)/(ar*ar+ai*ai))
                assertEquals(table.db(hz,rate,gain,0),db,1e-10)
            }
        }
    }
    @Test fun captureControlsFreezePlanArraysAndUseItsActualRate() {
        val p = planner().plan(model(),listOf(Planner.Filter(0,60.0,6.0,1.0)),true,6.0)
        val table=Planner.EqTable(File("src/main/assets/lab/eq_coefficients.csv").inputStream())
        val c=CaptureLabControls(p,table)
        assertEquals(48000,c.rate);assertEquals(4096,c.block)
        assertEquals(10,c.coefficients.size);assertEquals(64,c.stops.toSet().size)
        assertEquals(p.attenuationDb,c.inputGainDb,0.0)
        val first=c.gains[0];p.gains[0]=12.0;p.model.stops[0]=999
        assertEquals(first,c.gains[0],0.0);assertNotEquals(999,c.stops[0])
    }
}
