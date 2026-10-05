package app.svan

import app.svan.svaramanas.AutoHeadphone
import app.svan.tuning.AutoEqSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoHeadphoneTest {
    private fun e(name: String, source: String = "oratory1990", form: String = "in-ear") =
        AutoEqSource.Entry(name, source, form, null, "results/$source/$form/$name")

    private val index = listOf(
        e("Sony WH-1000XM4", "oratory1990", "over-ear"),
        e("Sony WH-1000XM4", "crinacle", "over-ear"),
        e("Sony WH-1000XM3", "oratory1990", "over-ear"),
        e("Apple AirPods Pro", "oratory1990"),
        e("Apple AirPods Pro 2", "oratory1990"),
        e("Realme Buds Air 3", "crinacle"),
        e("Fosi Audio IM4", "crinacle"),
        e("Moondrop Aria", "crinacle"),
    )

    @Test fun exactNameMatchesAndTheBestSourceIsPreferred() {
        val m = AutoHeadphone.match("Sony WH-1000XM4", index)!!
        assertEquals("oratory1990", m.source)
        assertEquals("Fosi Audio IM4", AutoHeadphone.match("Fosi Audio IM4", index)!!.name)
    }

    @Test fun bluetoothNamesWithExtraWordsStillMatch() {
        assertEquals("Sony WH-1000XM4", AutoHeadphone.match("WH-1000XM4 Sony Bluetooth Headphones", index)!!.name)
        assertEquals("Moondrop Aria", AutoHeadphone.match("Moondrop Aria Wireless", index)!!.name)
    }

    @Test fun neighbouringModelsAreNeverConfusedAndUnknownModelsAreNotGuessed() {
        // AirPods Pro vs AirPods Pro 2 are different headphones: exact token sets decide.
        assertEquals("Apple AirPods Pro 2", AutoHeadphone.match("Apple AirPods Pro 2", index)!!.name)
        assertEquals("Apple AirPods Pro", AutoHeadphone.match("Apple AirPods Pro", index)!!.name)
        assertNull(AutoHeadphone.match("Realme Buds Air 8", index)) // 3 is not 8
        assertNull(AutoHeadphone.match("JBL Tune 760", index))
        assertNull(AutoHeadphone.match("", index))
    }
}
