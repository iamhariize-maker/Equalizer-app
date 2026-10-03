package dev.equalizer.app

import android.media.audiofx.Visualizer

/**
 * Level of the device's final output mix (Visualizer on session 0, i.e. after
 * every session effect), for automated tests: "is the user hearing one
 * processed copy, two copies, or nothing?". Session-0 Visualizer is deprecated
 * but still available on AOSP builds. Needs RECORD_AUDIO.
 */
object MixMeter {
    /** Median RMS in dBFS over [seconds]. */
    fun measure(seconds: Double): String {
        val vis = Visualizer(0)
        vis.measurementMode = Visualizer.MEASUREMENT_MODE_PEAK_RMS
        vis.enabled = true
        val m = Visualizer.MeasurementPeakRms()
        val samples = mutableListOf<Int>()
        val end = System.nanoTime() + (seconds * 1e9).toLong()
        try {
            while (System.nanoTime() < end) {
                Thread.sleep(250)
                vis.getMeasurementPeakRms(m)
                samples += m.mRms
            }
        } finally {
            vis.release()
        }
        val sorted = samples.sorted()
        val median = sorted[sorted.size / 2] / 100.0
        return "MIX median=%.1f dBFS min=%.1f max=%.1f n=%d".format(median, sorted.first() / 100.0, sorted.last() / 100.0, sorted.size)
    }
}
