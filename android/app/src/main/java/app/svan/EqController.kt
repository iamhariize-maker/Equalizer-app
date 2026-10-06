package app.svan

/**
 * Shared engine state. SystemEqService owns the foreground lifetime and releases
 * session effects when processing is stopped.
 */
object EqController {
    const val SAMPLE_RATE = 48000

    /** Parametric curve source of truth; also renders the curve for Engine A. */
    val curveEngine: NativeEngine by lazy { NativeEngine(SAMPLE_RATE, 2, NativeEngine.Quality.EFFICIENT) }

    val globalEq = GlobalEqEngine(bandCount = 128)

    val log = StringBuilder()

    fun log(line: String) {
        android.util.Log.i("EqSpike", line) // automated tests read logcat
        synchronized(log) {
            log.appendLine(line)
            // Diagnostics must not grow for the entire lifetime of the audio service.
            if (log.length > 65_536) log.delete(0, log.length - 49_152)
        }
    }

    /** Loads AutoEq text into the shared state; both engines follow it. */
    fun loadPreset(text: String): Int = SvanRepository.importParametric("Sample preset", text)

    /** Sample AutoEq-format preset used by the spike UI. */
    const val SAMPLE_PRESET = """Preamp: -6.0 dB
Filter 1: ON LSC Fc 105 Hz Gain 6.0 dB Q 0.70
Filter 2: ON PK Fc 2500 Hz Gain -3.0 dB Q 1.40
Filter 3: ON PK Fc 6000 Hz Gain 2.5 dB Q 3.00
Filter 4: ON HSC Fc 10000 Hz Gain -2.0 dB Q 0.70
"""
}
