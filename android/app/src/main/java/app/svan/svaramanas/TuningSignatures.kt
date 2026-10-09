package app.svan.svaramanas

import org.json.JSONArray
import org.json.JSONObject

/**
 * One saved result of "Learn this sound": the packed `TasteTarget` (see core `svaramanas.h`) that Svaresa
 * steers toward. It holds measured balance only (tilt, bass depth, brightness, width, dynamics), never audio.
 * The array is copied on the way in and out so a stored signature cannot change after it is saved.
 */
class TuningSignature(val name: String, packed: DoubleArray, val savedAtMs: Long) {
    private val values = packed.copyOf(TuningSignatures.PACKED)
    val packed: DoubleArray get() = values.copyOf()
    /** How many reference tracks this signature was learned from. */
    val tracks: Int get() = values[1].toInt()

    fun sameSound(other: DoubleArray?): Boolean =
        other != null && other.size >= TuningSignatures.PACKED &&
            (0 until TuningSignatures.PACKED).all { values[it] == other[it] }

    fun renamed(newName: String) = TuningSignature(newName, values, savedAtMs)

    override fun equals(other: Any?) = other is TuningSignature && name == other.name &&
        savedAtMs == other.savedAtMs && values.contentEquals(other.values)
    override fun hashCode() = 31 * (31 * name.hashCode() + savedAtMs.hashCode()) + values.contentHashCode()
}

/**
 * The nine saved tuning signatures: fixed slots, so a slot keeps its place when another one is deleted.
 * Every function is pure; [app.svan.svaramanas.Svaramanas] owns the state and persistence.
 */
object TuningSignatures {
    const val SLOTS = 9
    const val PACKED = 7
    const val MAX_NAME = 24

    fun empty(): List<TuningSignature?> = List(SLOTS) { null }

    /**
     * A usable learned taste: flag set, at least one track, and every value inside the range the native
     * planner accepts (it clamps anything else, so out-of-range numbers mean damaged data, not a taste).
     */
    fun isValid(packed: DoubleArray?): Boolean {
        if (packed == null || packed.size < PACKED) return false
        if ((0 until PACKED).any { !packed[it].isFinite() }) return false
        return packed[0] != 0.0 && packed[1] in 1.0..1e6 &&
            packed[2] in -6.0..0.0 && packed[3] in 0.0..25.0 && packed[4] in 0.5..1.8 &&
            packed[5] in -40.0..0.0 && packed[6] in 0.0..30.0
    }

    fun defaultName(slot: Int) = "Signature ${slot + 1}"

    /** Printable, single-spaced, at most [MAX_NAME] characters; blank becomes the slot's default name. */
    fun cleanName(raw: String?, slot: Int): String {
        val text = (raw ?: "").filter { !it.isISOControl() }.trim().replace(Regex("\\s+"), " ")
        val cut = if (text.length > MAX_NAME) text.substring(0, MAX_NAME).trimEnd() else text
        return cut.ifEmpty { defaultName(slot) }
    }

    fun count(slots: List<TuningSignature?>) = slots.count { it != null }

    fun firstFree(slots: List<TuningSignature?>): Int? = slots.indexOfFirst { it == null }.takeIf { it >= 0 }

    /** The slot holding exactly this sound, or null when the current taste is unsaved or changed since. */
    fun indexMatching(slots: List<TuningSignature?>, packed: DoubleArray?): Int? =
        slots.indexOfFirst { it?.sameSound(packed) == true }.takeIf { it >= 0 }

    fun put(slots: List<TuningSignature?>, slot: Int, signature: TuningSignature): List<TuningSignature?> {
        require(slot in 0 until SLOTS) { "No such signature slot" }
        require(isValid(signature.packed)) { "Not a learned sound" }
        return slots.toMutableList().also { it[slot] = signature }
    }

    fun remove(slots: List<TuningSignature?>, slot: Int): List<TuningSignature?> {
        require(slot in 0 until SLOTS) { "No such signature slot" }
        return slots.toMutableList().also { it[slot] = null }
    }

    fun rename(slots: List<TuningSignature?>, slot: Int, name: String): List<TuningSignature?> {
        require(slot in 0 until SLOTS) { "No such signature slot" }
        val current = slots[slot] ?: return slots
        return slots.toMutableList().also { it[slot] = current.renamed(cleanName(name, slot)) }
    }

    fun toJson(slots: List<TuningSignature?>): JSONArray = JSONArray().also { out ->
        for (slot in 0 until SLOTS) {
            val s = slots.getOrNull(slot)
            out.put(if (s == null) JSONObject.NULL else JSONObject().put("name", s.name).put("saved", s.savedAtMs)
                .put("sound", JSONArray().also { a -> s.packed.forEach { a.put(it) } }))
        }
    }

    /** Lenient read for the app's own storage: a damaged slot is dropped, the rest survive. */
    fun fromJson(text: String?): List<TuningSignature?> {
        if (text.isNullOrBlank()) return empty()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return empty()
        return List(SLOTS) { slot -> runCatching { entry(array.optJSONObject(slot), slot) }.getOrNull() }
    }

    /** Strict read for an imported backup: any damaged or surplus entry rejects the whole list. */
    fun fromJsonStrict(array: JSONArray): List<TuningSignature?> {
        require(array.length() <= SLOTS) { "Too many tuning signatures" }
        return List(SLOTS) { slot ->
            if (slot >= array.length() || array.isNull(slot)) null
            else entry(array.getJSONObject(slot), slot) ?: throw IllegalArgumentException("Invalid tuning signature")
        }
    }

    private fun entry(o: JSONObject?, slot: Int): TuningSignature? {
        if (o == null) return null
        val sound = o.getJSONArray("sound")
        if (sound.length() != PACKED) return null
        val packed = DoubleArray(PACKED) { sound.getDouble(it) }
        if (!isValid(packed)) return null
        return TuningSignature(cleanName(o.optString("name"), slot), packed, o.optLong("saved", 0L).coerceAtLeast(0L))
    }
}
