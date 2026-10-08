package app.svan.svaramanas

import android.content.Context
import app.svan.EqController
import app.svan.SvanRepository
import app.svan.tuning.AutoEqSource
import app.svan.tuning.Signature
import app.svan.tuning.TuningController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Recognises the connected headphones by the name they report and applies AutoEq's measured correction
 * toward the Harman target: the single largest, best-evidenced tonal improvement available for most
 * headphones. It never overrides a correction the listener chose, and it removes its own when those
 * headphones are unplugged. If the model is not in the database nothing is applied (and it says so).
 */
object AutoHeadphone {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val SOURCE_PRIORITY = listOf("oratory1990", "crinacle", "Rtings", "Super Review", "Innerfidelity", "Headphone.com Legacy")
    private val NOISE = setOf("bluetooth", "wireless", "headphones", "headphone", "earphones", "earbuds", "headset", "audio", "bt", "the")

    @Volatile private var lastKey: Pair<String?, Boolean>? = null
    @Volatile private var busy = false
    @Volatile private var autoManaged: String? = null // headphone name of the correction we applied
    private val _status = kotlinx.coroutines.flow.MutableStateFlow("")
    /** What headphone recognition did, in plain words ("" = nothing to say). */
    val statusFlow: kotlinx.coroutines.flow.StateFlow<String> = _status
    private var status: String
        get() = _status.value
        set(v) { _status.value = v }

    fun tokens(name: String): Set<String> =
        name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in NOISE }.toSet()

    /**
     * Best AutoEq entry for a reported device name, or null when no confident, unambiguous match exists.
     * Exact name = 1.0; every AutoEq token present in the device name (and at least two) = 0.8; the
     * device's tokens all inside the AutoEq name (and at least two) = 0.7.
     */
    fun match(deviceName: String, index: List<AutoEqSource.Entry>): AutoEqSource.Entry? {
        val d = tokens(deviceName)
        if (d.isEmpty()) return null
        data class Scored(val e: AutoEqSource.Entry, val score: Double)
        val scored = index.mapNotNull { e ->
            val n = tokens(e.name)
            if (n.isEmpty()) return@mapNotNull null
            val s = when {
                n == d -> 1.0
                n.size >= 2 && d.containsAll(n) -> 0.8 - 0.03 * (d.size - n.size)
                d.size >= 2 && n.containsAll(d) -> 0.7 - 0.03 * (n.size - d.size)
                else -> return@mapNotNull null
            }
            Scored(e, s)
        }
        val best = scored.maxOfOrNull { it.score } ?: return null
        if (best < 0.7) return null
        val top = scored.filter { it.score >= best - 1e-9 }
        // Several different models tying means the name is ambiguous: do nothing rather than guess.
        if (top.map { tokens(it.e.name) }.distinct().size > 1) return null
        return top.minByOrNull { SOURCE_PRIORITY.indexOf(it.e.source).let { i -> if (i < 0) 99 else i } }?.e
    }

    /** Cheap; call whenever the output may have changed. Network work runs in the background. */
    fun check(context: Context, request: SmartRequest) {
        val name = SvaresaSensors.headphoneName(context)
        // Keyed on the switch too: turning recognition off with the same headphones on must remove the correction.
        val key = name to request.autoHeadphone
        if (key == lastKey || busy) return
        lastKey = key
        val managed = autoManaged
        // Headphones gone or swapped for others (or feature off): drop the correction we added for the previous
        // pair, never one the listener chose. A swap must not leave pair A's correction on pair B.
        if (managed != null) {
            if (SvanRepository.eq.value.tuning?.headphone == managed) scope.launch(Dispatchers.Main) {
                SvanRepository.update { if (it.tuning?.headphone == managed) it.copy(tuning = null) else it }
            }
            autoManaged = null
            status = ""
            EqController.log("svaresa: ${if (!request.autoHeadphone) "headphone recognition off" else "output changed"}; removed the automatic correction")
        }
        if (name == null || !request.autoHeadphone) return
        val current = SvanRepository.eq.value.tuning
        if (current != null && current.headphone != managed) { status = "Your own headphone correction is active; left as is."; return }
        busy = true
        scope.launch {
            try {
                val index = AutoEqSource.index(context)
                val entry = match(name, index)
                if (entry == null) {
                    status = "“$name” is not in the AutoEq database; no automatic correction."
                    EqController.log("svaresa: $name not recognised in AutoEq")
                    return@launch
                }
                val built = TuningController.build(context, TuningController.Request(entry, Signature.HARMAN, 0.0, 0.0, 64))
                val t = built.getOrNull()
                if (t == null) {
                    status = "Could not load the correction for ${entry.name}: ${built.exceptionOrNull()?.message}"
                    EqController.log("svaresa: auto headphone failed: ${built.exceptionOrNull()}")
                } else {
                    withContext(Dispatchers.Main) {
                        // The listener may disable recognition, unplug, or choose a manual
                        // correction while the network/fit work is in flight. Check the live
                        // state at the commit, not before dispatching back to the UI thread.
                        if (AutoHeadphoneCommit.allowed(Svaramanas.request.value,
                                SvaresaSensors.headphoneName(context), name, current, SvanRepository.eq.value.tuning)) {
                            autoManaged = t.headphone
                            SvanRepository.update { it.copy(tuning = t) }
                            status = "Recognised $name as ${entry.name} (${entry.label}); applied the Harman correction."
                            EqController.log("svaresa: recognised $name → ${entry.name} (${entry.label}); applied Harman, fit rms=%.2f dB".format(t.fitRmsDb))
                        } else {
                            lastKey = null // a future enabled check can start a fresh request
                            EqController.log("svaresa: discarded an outdated automatic headphone correction")
                        }
                    }
                }
            } catch (e: Exception) {
                status = "Headphone recognition needs the internet once (${e.javaClass.simpleName})."
                lastKey = null // retry on the next check
                EqController.log("svaresa: headphone index unavailable: $e")
            } finally {
                busy = false
            }
        }
    }
}
