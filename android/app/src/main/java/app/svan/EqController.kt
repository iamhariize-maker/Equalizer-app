package app.svan

/**
 * Process-wide state for the spike. A real app would host this in a foreground
 * service so Android doesn't kill the process (and our effects) in the background.
 */
object EqController {
    const val SAMPLE_RATE = 48000

    /** Parametric curve source of truth; also renders the curve for Engine A. */
    val curveEngine: NativeEngine by lazy { NativeEngine(SAMPLE_RATE, 2, NativeEngine.Quality.EFFICIENT) }

    val globalEq = GlobalEqEngine(bandCount = 128)

    val log = StringBuilder()

    fun log(line: String) {
        android.util.Log.i("EqSpike", line) // automated tests read logcat
        synchronized(log) { log.appendLine(line) }
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
