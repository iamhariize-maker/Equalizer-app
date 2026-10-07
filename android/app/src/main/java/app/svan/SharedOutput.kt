package app.svan

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Explicit, experimental output-mix option. Never persisted across service shutdown. */
object SharedOutput {
    data class State(val requested: Boolean = false, val attached: Boolean = false,
                     val message: String = "Off. Per-player connections are used.")
    private val mutable = MutableStateFlow(State())
    val status = mutable.asStateFlow()
    internal fun publish(requested: Boolean, attached: Boolean, message: String) {
        mutable.value = State(requested, attached, message)
    }
}
