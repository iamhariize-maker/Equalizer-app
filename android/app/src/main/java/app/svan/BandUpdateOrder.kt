package app.svan

import kotlin.math.abs

/** Same stable cut-before-boost ordering and 0.01f threshold, without sorting/boxing indices. */
internal inline fun forEachBandUpdate(gains: DoubleArray, sent: FloatArray, apply: (Int, Float) -> Unit) {
    require(gains.size == sent.size)
    for (pass in 0..1) for (i in gains.indices) {
        val first = sent[i].isNaN() || gains[i] < sent[i]
        if (first != (pass == 0)) continue
        val gain = gains[i].toFloat()
        if (abs(gain - sent[i]) < 0.01f) continue
        apply(i, gain)
    }
}
