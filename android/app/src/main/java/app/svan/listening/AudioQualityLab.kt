package app.svan.listening

import app.svan.EqController
import app.svan.NativeEngine
import app.svan.svaramanas.PolicyRule
import org.json.JSONObject
import kotlin.math.abs

/** Real release JNI calls, silent/offline, with explicit sample-rate and delay contracts. */
object AudioQualityLab {
    fun verify() {
        var maxError = 0.0
        for (fs in listOf(44100, 48000, 96000)) {
            var n = 1
            while (n < fs * 1024L / 48000) n *= 2
            fun render(detailed: Boolean): Pair<Int, FloatArray> =
                NativeEngine(fs, 2, 1, 120.0, 0, 0, false, false, detailed).use { engine ->
                    val input = FloatArray((fs / 4 + engine.latencyFrames) * 2)
                    input[64] = .1f; input[65] = .07f
                    engine.process(input, input, input.size / 2)
                    val diagnostic = engine.bassUnmaskDiagnostics()
                    check(diagnostic.size == 5 && diagnostic.all { it.isFinite() } && diagnostic.take(4).all { it <= 0 })
                    engine.latencyFrames to input
                }
            val fast = render(false); val detailed = render(true)
            check(detailed.first - fast.first == n) { "Detailed delay wrong at $fs Hz" }
            val offset = n * 2
            for (i in fast.second.indices) maxError = maxOf(maxError, abs(fast.second[i] - detailed.second[i + offset]).toDouble())
            check(maxError < 1e-6) { "Detailed zero-control aligned identity error $maxError" }
        }
        val rules = PolicyRule.parse(NativeEngine.nativePolicyRulesJson())
        check(rules.size == 19 && rules.any { it.id == "SV-RESOLVE-1" })
        EqController.log("AUDIO_QUALITY_LAB_READY " + JSONObject().put("rates", "44100/48000/96000")
            .put("alignedMaxError", maxError).put("ruleCount", rules.size).put("diagnosticSize", 5))
    }
}
