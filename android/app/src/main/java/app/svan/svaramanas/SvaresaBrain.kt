package app.svan.svaramanas

import app.svan.NativeEngine.FilterType
import app.svan.model.Band
import kotlin.math.log10
import kotlin.math.pow

/** Equal-loudness contours, ISO 226:2003 (the standard's published coefficients). */
object Iso226 {
    private val F = doubleArrayOf(20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0, 400.0,
        500.0, 630.0, 800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0)
    private val ALPHA = doubleArrayOf(0.532, 0.506, 0.480, 0.455, 0.432, 0.409, 0.387, 0.367, 0.349, 0.330, 0.315, 0.301, 0.288,
        0.276, 0.267, 0.259, 0.253, 0.250, 0.246, 0.244, 0.243, 0.243, 0.243, 0.242, 0.242, 0.245, 0.254, 0.271, 0.301)
    private val LU = doubleArrayOf(-31.6, -27.2, -23.0, -19.1, -15.9, -13.0, -10.3, -8.1, -6.2, -4.5, -3.1, -2.0, -1.1, -0.4,
        0.0, 0.3, 0.5, 0.0, -2.7, -4.1, -1.0, 1.7, 2.5, 1.2, -2.1, -7.1, -11.2, -10.7, -3.1)
    private val TF = doubleArrayOf(78.5, 68.7, 59.5, 51.1, 44.0, 37.5, 31.5, 26.5, 22.1, 17.9, 14.4, 11.4, 8.6, 6.2,
        4.4, 3.0, 2.2, 2.4, 3.5, 1.7, -1.3, -4.2, -6.0, -5.4, -1.5, 6.0, 12.6, 13.9, 12.3)

    private fun at(table: DoubleArray, f: Double): Double {
        val x = f.coerceIn(F.first(), F.last())
        var i = 0
        while (i < F.size - 2 && x > F[i + 1]) i++
        val t = (Math.log(x) - Math.log(F[i])) / (Math.log(F[i + 1]) - Math.log(F[i]))
        return table[i] + t * (table[i + 1] - table[i])
    }

    /** Sound pressure level (dB) at [freqHz] that sounds as loud as [phon] at 1 kHz. Valid 20–90 phon. */
    fun spl(freqHz: Double, phon: Double): Double {
        val ln = phon.coerceIn(20.0, 90.0)
        val af = at(ALPHA, freqHz)
        val lu = at(LU, freqHz)
        val tf = at(TF, freqHz)
        val a = 4.47e-3 * (10.0.pow(0.025 * ln) - 1.15) + (0.4 * 10.0.pow((tf + lu) / 10.0 - 9.0)).pow(af)
        return (10.0 / af) * log10(a) - lu + 94.0
    }

    /** Contour shape relative to 1 kHz at that loudness level (dB). */
    fun relative(freqHz: Double, phon: Double): Double = spl(freqHz, phon) - phon
}

/** What kind of output the music is going to. */
enum class RouteKind(val label: String) {
    SPEAKER("phone speaker"), WIRED("wired headphones"), USB("USB audio"), BLUETOOTH("Bluetooth"), OTHER("this output");

    companion object {
        /** From the audio server's `Output devices:` text (AUDIO_DEVICE_OUT_BLUETOOTH_A2DP…). */
        fun fromServerText(text: String): RouteKind? {
            val t = text.uppercase()
            return when {
                t.isBlank() -> null
                "BLUETOOTH" in t || "BLE_" in t || "HEARING_AID" in t -> BLUETOOTH
                "USB" in t -> USB
                "WIRED" in t || "HEADPHONE" in t || "HEADSET" in t -> WIRED
                "SPEAKER" in t || "EARPIECE" in t -> SPEAKER
                else -> OTHER
            }
        }

        /** From android.media.AudioDeviceInfo.TYPE_*. */
        fun fromDeviceType(type: Int): RouteKind = when (type) {
            2 -> SPEAKER                       // BUILTIN_SPEAKER
            3, 4 -> WIRED                      // WIRED_HEADSET, WIRED_HEADPHONES
            7, 8, 23, 26, 27, 30 -> BLUETOOTH  // SCO, A2DP, HEARING_AID, BLE_HEADSET, BLE_SPEAKER, BLE_BROADCAST
            11, 12, 13, 22 -> USB              // USB_DEVICE, USB_ACCESSORY, DOCK, USB_HEADSET
            else -> OTHER
        }
    }
}

/** How Svaresa adapts. [night] = AUTO uses the clock, ON/OFF force it. */
enum class NightMode(val label: String) { AUTO("Auto"), ON("On"), OFF("Off") }

/** What the phone knows right now, with no audio analysis. */
data class SvaresaContext(
    /** Music volume, 0..1 (system volume index / max). */
    val volume: Double,
    val route: RouteKind,
    val hourOfDay: Int,
    val minuteOfHour: Int = 0,
    val night: NightMode = NightMode.AUTO,
    /** Master switches for each adaptation, so the listener stays in control. */
    val volumeAware: Boolean = true,
    val routeAware: Boolean = true,
)

/** The always-fixed band skeleton Svaresa adds on top of the measured plan (so slewing works by index). */
data class ContextLayer(
    val bands: List<Band>,
    val preampDb: Double,
    /** 0..1 amount of gentle level-evening (system effects' multiband compressor). */
    val levelling: Double,
    val reasons: List<String>,
    val bassLiftDb: Double,
    val trebleLiftDb: Double,
    val nightAmount: Double,
) {
    companion object {
        const val BAND_COUNT = 4
        val NEUTRAL = ContextLayer(skeleton(0.0, 0.0, 0.0, 0.0), 0.0, 0.0, emptyList(), 0.0, 0.0, 0.0)

        fun skeleton(bassLift: Double, trebleLift: Double, nightSub: Double, nightPresence: Double) = listOf(
            Band(FilterType.LOW_SHELF, 110.0, bassLift + nightSub, 0.71),
            Band(FilterType.HIGH_SHELF, 7500.0, trebleLift, 0.71),
            Band(FilterType.LOW_SHELF, 55.0, 0.0, 0.71),                      // reserved: route protection (speaker)
            Band(FilterType.PEAK, 3800.0, nightPresence, 1.0),
        )
    }
}

/**
 * Svaresa's context brain: the part of "Auto master" that works on every engine, with no audio
 * capture. Everything here is a small, bounded, explained move:
 *  - **Quiet listening** (equal-loudness): the ear loses bass and treble as volume drops; the lift
 *    is the difference between the ISO 226 contours at the current and a reference listening level.
 *  - **Output protection**: a phone speaker gets no bass boost (its driver cannot reproduce it).
 *  - **Night comfort**: after dark, trim sub-bass, soften the presence peak and gently even out levels.
 * The volume→loudness mapping is an assumption (headphone sensitivity differs); it is deliberately
 * capped, and the listener can switch each adaptation off.
 */
object SvaresaBrain {
    const val REFERENCE_PHON = 65.0
    const val MAX_BASS_DB = 6.0
    const val MAX_TREBLE_DB = 3.0
    const val SPEAKER_MAX_BASS_DB = 1.5
    const val NIGHT_SUB_DB = -2.5
    const val NIGHT_PRESENCE_DB = -1.0
    const val NIGHT_LEVELLING = 0.6

    /** Assumed loudness for a volume setting: 30 phon (quietest) to 80 phon (maximum). */
    fun phonFor(volume: Double) = 30.0 + 50.0 * volume.coerceIn(0.0, 1.0)

    /** 0..1: how "night" it is. Full from 22:30 to 05:30, ramping over an hour each side. */
    fun nightAmount(mode: NightMode, hour: Int, minute: Int): Double {
        if (mode == NightMode.ON) return 1.0
        if (mode == NightMode.OFF) return 0.0
        val t = hour + minute / 60.0
        return when {
            t >= 22.5 || t < 5.5 -> 1.0
            t >= 21.5 -> t - 21.5          // ramp in over 21:30–22:30
            t < 6.5 -> 6.5 - t             // ramp out over 05:30–06:30
            else -> 0.0
        }
    }

    fun layer(c: SvaresaContext): ContextLayer {
        val reasons = mutableListOf<String>()
        // ---- quiet-listening compensation ----
        var bass = 0.0
        var treble = 0.0
        if (c.volumeAware) {
            val v = phonFor(c.volume)
            fun lift(f: Double) = Iso226.relative(f, v) - Iso226.relative(f, REFERENCE_PHON)
            val rawBass = listOf(50.0, 63.0, 80.0).map(::lift).average()
            val rawTreble = listOf(8000.0, 10000.0, 12500.0).map(::lift).average()
            val bassCap = if (c.routeAware && c.route == RouteKind.SPEAKER) SPEAKER_MAX_BASS_DB else MAX_BASS_DB
            bass = (0.9 * rawBass).coerceIn(0.0, bassCap)
            treble = (0.8 * rawTreble).coerceIn(0.0, MAX_TREBLE_DB)
            if (bass >= 0.5 || treble >= 0.5) {
                reasons += "Quiet listening: the ear loses bass and treble at low volume, so I raised bass %.1f dB and treble %.1f dB against the mids (equal-loudness curves; with clipping protection on, the mids step down rather than the bass going up).".format(bass, treble)
            }
            if (c.routeAware && c.route == RouteKind.SPEAKER && rawBass > SPEAKER_MAX_BASS_DB + 0.5) {
                reasons += "Phone speaker: bass lift limited to %.1f dB; a small driver would only distort.".format(SPEAKER_MAX_BASS_DB)
            }
        }
        // ---- night comfort ----
        val n = nightAmount(c.night, c.hourOfDay, c.minuteOfHour)
        val nightSub = NIGHT_SUB_DB * n
        val nightPresence = NIGHT_PRESENCE_DB * n
        val levelling = NIGHT_LEVELLING * n
        if (n >= 0.25) {
            reasons += "Night comfort: sub-bass %.1f dB, presence %.1f dB and a gentle level-evening, so quiet details stay clear without waking the house."
                .format(nightSub * 1.0, nightPresence * 1.0)
        }
        val bands = ContextLayer.skeleton(bass, treble, nightSub, nightPresence)
        // Part of the added bass/treble is returned as level, so quiet listening is not just "louder".
        val preamp = -0.35 * maxOf(bass, treble)
        return ContextLayer(bands, preamp, levelling, reasons, bass, treble, n)
    }
}
