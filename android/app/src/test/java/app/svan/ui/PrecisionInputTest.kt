package app.svan.ui

import org.junit.Assert.*
import org.junit.Test

class PrecisionInputTest {
    @Test fun tinyMovesAccumulateInsteadOfBeingLost() {
        val fine=DetentAccumulator(0.0,-12.0,12.0,0.1)
        repeat(100) { fine.move(0.003) }
        assertEquals(0.3,fine.value,1e-9)
        assertEquals(fine.value,DetentAccumulator(0.0,-12.0,12.0,0.1).move(0.3),1e-9)
    }
    @Test fun aGrabDoesNotSnapAnExistingAutomaticValueToTheGrid() {
        val dial=DetentAccumulator(1.234,-12.0,12.0,0.1)
        assertEquals(1.234,dial.move(0.0),0.0)
        assertEquals(1.234,dial.move(0.01),0.0)
        assertEquals(1.3,dial.move(0.06),1e-9)
    }
    @Test fun jitterAtTheTickBoundaryDoesNotToggleTheValue() {
        val dial=DetentAccumulator(0.0,-1.0,1.0,0.1)
        assertEquals(0.1,dial.move(0.06),1e-9)
        repeat(10) { dial.move(-0.01); assertEquals(0.1,dial.value,1e-9); dial.move(0.01) }
        assertEquals(0.0,dial.move(-0.03),1e-9)
    }
    @Test fun reversingAtEitherStopRespondsImmediately() {
        val dial=DetentAccumulator(0.0,-1.0,1.0,0.01)
        assertEquals(1.0,dial.move(500.0),0.0)
        assertEquals(0.99,dial.move(-0.01),1e-9)
        assertEquals(-1.0,dial.move(-500.0),0.0)
        assertEquals(-0.99,dial.move(0.01),1e-9)
        assertEquals(-0.99,dial.move(Double.NaN),1e-9)
        assertEquals(-0.99,dial.move(Double.POSITIVE_INFINITY),1e-9)
    }
    @Test fun rotationCrossesTheAngleSeamWithoutAnAlmostFullTurnJump() {
        val forward=angularDeltaDegrees(-1f,-0.01f,-1f,0.01f)
        assertTrue(forward<0 && forward>-2)
        assertEquals(-forward,angularDeltaDegrees(-1f,0.01f,-1f,-0.01f),1e-9)
        assertEquals(90.0,angularDeltaDegrees(1f,0f,0f,1f),1e-9)
    }
    @Test fun centreScrollingAndRimTurningHaveDistinctGestures() {
        assertEquals(DialDrag.WAIT,dialDrag(0f,0f,2f,1f,40f,8f))
        assertEquals(DialDrag.SCROLL,dialDrag(0f,0f,0f,20f,40f,8f))
        assertEquals(DialDrag.SCROLL,dialDrag(0f,-38f,0f,20f,40f,8f))
        assertEquals(DialDrag.SIDEWAYS,dialDrag(0f,0f,20f,0f,40f,8f))
        assertEquals(DialDrag.ROTARY,dialDrag(0f,-38f,20f,0f,40f,8f))
        assertEquals(DialDrag.ROTARY,dialDrag(38f,0f,0f,20f,40f,8f))
    }
    @Test fun frameEditsUseTheLatestValueAndReleaseFlushesTheFinalTick() {
        val edits=mutableListOf<Double>()
        val interaction=PrecisionInteraction({0.0},{edits.add(it)})
        interaction.begin()
        interaction.move(0.1);interaction.move(0.2);interaction.move(0.3)
        assertTrue(edits.isEmpty())
        interaction.dispatch();assertEquals(listOf(0.3),edits)
        interaction.move(0.4);interaction.finish();interaction.dispatch()
        assertEquals(listOf(0.3,0.4),edits)
        assertFalse(interaction.dragging)
    }
    @Test fun stationaryInputDoesNotRepeatedlyRecomputeAudio() {
        val edits=mutableListOf<Double>()
        val interaction=PrecisionInteraction({0.3},{edits.add(it)})
        interaction.begin();repeat(30) { interaction.move(0.3);interaction.dispatch() };interaction.finish()
        assertTrue(edits.isEmpty())
    }
}
