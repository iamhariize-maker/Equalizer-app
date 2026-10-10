package app.svan

import app.svan.model.AudioSettings
import app.svan.model.Band
import app.svan.model.EqState

/** Exact inputs to the system-effects curve. No rounding or approximation for caching. */
internal data class CurveConfiguration(val bands: List<Band>, val preampDb: Double,
    val autoHeadroom: Boolean, val gainProtection: Boolean) {
    companion object {
        fun from(state: EqState, settings: AudioSettings): CurveConfiguration {
            val effective = settings.effectiveFor(state)
            return CurveConfiguration(state.systemEffectsBands(), state.effectivePreampDb(),
                effective.autoHeadroom, effective.gainProtection)
        }
    }
}

/** Live smart analysis and compare are intentionally absent from the persisted EqState JSON. */
internal fun EqState.persistenceState(): EqState = copy(smart = null, smartBypass = false)
