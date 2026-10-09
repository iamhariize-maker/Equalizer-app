package app.svan

import app.svan.svaramanas.TuningSignature
import app.svan.svaramanas.TuningSignatures
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TuningSignaturesTest {
    /** Packed TasteTarget: valid, tracks, tilt, bass-to-mids, sharpness, side-to-mid, PLR. */
    private fun taste(tracks: Double = 3.0, tilt: Double = -2.8) = doubleArrayOf(1.0, tracks, tilt, 9.5, 1.0, -11.0, 12.0)
    private fun sig(name: String = "Warm", t: DoubleArray = taste()) = TuningSignature(name, t, 1_000L)

    @Test fun thereAreExactlyNineSlotsAndTheyStartEmpty() {
        val slots = TuningSignatures.empty()
        assertEquals(9, slots.size)
        assertEquals(0, TuningSignatures.count(slots))
        assertEquals(0, TuningSignatures.firstFree(slots))
    }

    @Test fun savingFillsASlotAndKeepsOthersInPlace() {
        var slots = TuningSignatures.empty()
        slots = TuningSignatures.put(slots, 4, sig("Middle"))
        slots = TuningSignatures.put(slots, 0, sig("First"))
        assertEquals("First", slots[0]?.name)
        assertEquals("Middle", slots[4]?.name)
        assertEquals(2, TuningSignatures.count(slots))
        assertEquals(1, TuningSignatures.firstFree(slots))
    }

    @Test fun deletingLeavesAHoleInsteadOfShiftingTheOtherSlots() {
        var slots = TuningSignatures.empty()
        for (i in 0 until 9) slots = TuningSignatures.put(slots, i, sig("S$i"))
        assertNull("nine full slots have no free one", TuningSignatures.firstFree(slots))
        slots = TuningSignatures.remove(slots, 3)
        assertNull(slots[3])
        assertEquals("S4", slots[4]?.name)
        assertEquals(3, TuningSignatures.firstFree(slots))
        assertEquals(8, TuningSignatures.count(slots))
    }

    @Test fun aTenthSlotDoesNotExist() {
        val slots = TuningSignatures.empty()
        assertTrue(runCatching { TuningSignatures.put(slots, 9, sig()) }.isFailure)
        assertTrue(runCatching { TuningSignatures.put(slots, -1, sig()) }.isFailure)
        assertTrue(runCatching { TuningSignatures.remove(slots, 9) }.isFailure)
    }

    @Test fun onlyARealLearnedSoundCanBeSaved() {
        val slots = TuningSignatures.empty()
        assertFalse(TuningSignatures.isValid(null))
        assertFalse(TuningSignatures.isValid(doubleArrayOf(1.0, 3.0)))
        assertFalse("flag unset", TuningSignatures.isValid(taste().also { it[0] = 0.0 }))
        assertFalse("no tracks", TuningSignatures.isValid(taste(tracks = 0.0)))
        assertFalse("not finite", TuningSignatures.isValid(taste(tilt = Double.NaN)))
        assertFalse("tilt out of range", TuningSignatures.isValid(taste(tilt = 4.0)))
        assertTrue(TuningSignatures.isValid(taste()))
        assertTrue(runCatching { TuningSignatures.put(slots, 0, sig(t = taste(tilt = 4.0))) }.isFailure)
    }

    @Test fun aSavedSignatureCannotBeChangedFromOutside() {
        val source = taste()
        val s = sig(t = source)
        source[2] = -5.0
        s.packed[2] = -5.0
        assertEquals(-2.8, s.packed[2], 0.0)
    }

    @Test fun theSlotInUseIsTheOneWithExactlyTheCurrentSound() {
        var slots = TuningSignatures.put(TuningSignatures.empty(), 2, sig("A", taste(tracks = 3.0)))
        slots = TuningSignatures.put(slots, 5, sig("B", taste(tracks = 4.0)))
        assertEquals(2, TuningSignatures.indexMatching(slots, taste(tracks = 3.0)))
        assertEquals(5, TuningSignatures.indexMatching(slots, taste(tracks = 4.0)))
        // Learning another track after using a signature changes the sound: it is no longer a saved one.
        assertNull(TuningSignatures.indexMatching(slots, taste(tracks = 5.0)))
        assertNull(TuningSignatures.indexMatching(slots, null))
    }

    @Test fun namesAreTidyBoundedAndNeverBlank() {
        assertEquals("Signature 3", TuningSignatures.cleanName("   ", 2))
        assertEquals("Signature 1", TuningSignatures.cleanName(null, 0))
        assertEquals("Late night", TuningSignatures.cleanName("  Late \t  night\n", 0))
        assertEquals(TuningSignatures.MAX_NAME, TuningSignatures.cleanName("x".repeat(80), 0).length)
        assertEquals("ab", TuningSignatures.cleanName("a\u0000b", 0).replace(" ", ""))
    }

    @Test fun renamingKeepsTheSoundAndIgnoresEmptySlots() {
        val slots = TuningSignatures.put(TuningSignatures.empty(), 1, sig("Old"))
        val renamed = TuningSignatures.rename(slots, 1, "  New name ")
        assertEquals("New name", renamed[1]?.name)
        assertTrue(renamed[1]!!.sameSound(taste()))
        assertEquals(renamed.map { it?.name }, TuningSignatures.rename(renamed, 7, "x").map { it?.name })
        assertEquals("Signature 2", TuningSignatures.rename(slots, 1, "")[1]?.name)
    }

    @Test fun storageRoundTripKeepsSlotPositionsNamesAndSounds() {
        var slots = TuningSignatures.put(TuningSignatures.empty(), 0, sig("One"))
        slots = TuningSignatures.put(slots, 8, sig("Nine", taste(tracks = 7.0, tilt = -1.2)))
        val back = TuningSignatures.fromJson(TuningSignatures.toJson(slots).toString())
        assertEquals(slots, back)
        assertNull(back[4])
    }

    @Test fun damagedStorageKeepsWhatIsStillGood() {
        val good = TuningSignatures.toJson(TuningSignatures.put(TuningSignatures.empty(), 1, sig("Keep")))
        good.put(2, JSONObject().put("name", "Broken").put("sound", JSONArray(listOf(1.0, 2.0))))
        good.put(3, "not an object")
        val back = TuningSignatures.fromJson(good.toString())
        assertEquals("Keep", back[1]?.name)
        assertNull(back[2])
        assertNull(back[3])
        assertEquals(TuningSignatures.empty(), TuningSignatures.fromJson("not json"))
        assertEquals(TuningSignatures.empty(), TuningSignatures.fromJson(null))
    }

    @Test fun importedBackupsAreCheckedStrictly() {
        val ok = TuningSignatures.toJson(TuningSignatures.put(TuningSignatures.empty(), 6, sig("Imported")))
        assertEquals("Imported", TuningSignatures.fromJsonStrict(ok)[6]?.name)
        // A tenth entry, or any damaged entry, rejects the whole list.
        val tooMany = JSONArray().also { a -> repeat(10) { a.put(JSONObject.NULL) } }
        assertTrue(runCatching { TuningSignatures.fromJsonStrict(tooMany) }.isFailure)
        val damaged = TuningSignatures.toJson(TuningSignatures.empty())
        damaged.put(0, JSONObject().put("name", "Bad").put("sound", JSONArray(listOf(1.0, 3.0, 99.0, 9.5, 1.0, -11.0, 12.0))))
        assertTrue(runCatching { TuningSignatures.fromJsonStrict(damaged) }.isFailure)
        assertNotNull(TuningSignatures.fromJsonStrict(JSONArray()))
        assertEquals(9, TuningSignatures.fromJsonStrict(JSONArray()).size)
    }
}
