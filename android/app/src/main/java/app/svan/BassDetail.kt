package app.svan

import android.media.AudioDeviceInfo
import app.svan.model.AudioSettings
import app.svan.model.EqState

/**
 * What the audiophile engine's bass stage gets (see core BassTexture): the harmonic texture follows Svaresa's house body
 * weight, scaled by the output route, and four explicit experiments (attack, dimension, sustain, tube) are each off
 * until the listener turns them on. Pure, so the mapping is unit-tested.
 */
internal object BassDetail {
    data class Levels(val texture: Double, val evenMix: Double, val attack: Double, val spread: Double, val sustain: Double) {
        companion object { val OFF = Levels(0.0, 0.0, 0.0, 0.0, 0.0) }
    }

    /** Texture used by the tube and dimension experiments when Svaresa supplies no body weight (the house value). */
    const val HOUSE_TEXTURE = 0.7

    fun levels(settings: AudioSettings, eq: EqState, routeFactor: Double): Levels {
        if (!eq.enabled) return Levels.OFF
        val body = eq.activeSmart?.groundingBody ?: 0.0
        // Tube colour and dimension act on the generated harmonics, so they bring the texture with them.
        val texture = (if (settings.bassTube || settings.bassDimension) maxOf(body, HOUSE_TEXTURE) else body) * routeFactor
        return Levels(
            texture = texture.coerceIn(0.0, 1.0),
            evenMix = if (settings.bassTube) 1.0 else 0.0,
            attack = if (settings.bassAttack) 1.0 else 0.0,
            spread = if (settings.bassDimension) 1.0 else 0.0,
            sustain = if (settings.bassSustain) 1.0 else 0.0,
        )
    }

    /**
     * Texture strength by output: the phone speaker cannot reproduce the fundamentals, so it gets the full texture;
     * headphones, earbuds and other outputs reproduce more of the bass and start at 0.7. Starting guesses, to be
     * blind-tested (docs/BUILD_BRIEF_0.5.14.md WP5).
     */
    fun routeFactor(deviceType: Int?): Double = when (deviceType) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 1.0
        null -> 0.85
        else -> 0.7
    }

    fun apply(engine: NativeEngine, levels: Levels) {
        engine.setBassTexture(levels.texture)
        engine.setBassDetail(levels.evenMix, levels.attack, levels.spread, levels.sustain)
    }

    /** The highs experiment (analogue top): full depth when the listener turns it on and the EQ is active. */
    fun analogTop(settings: AudioSettings, eq: EqState): Double = if (eq.enabled && settings.analogTop) 1.0 else 0.0

    /** The expression experiment (winds and strings): full depth when turned on and the EQ is active. */
    fun expression(settings: AudioSettings, eq: EqState): Double = if (eq.enabled && settings.expression) 1.0 else 0.0
}
