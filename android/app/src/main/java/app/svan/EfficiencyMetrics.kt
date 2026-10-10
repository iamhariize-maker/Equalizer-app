package app.svan

import java.util.concurrent.atomic.AtomicLong

/** Debug-only counters, never called from the audio thread. R8 removes increments in phone builds. */
internal object EfficiencyMetrics {
    private val uiPolls = AtomicLong()
    private val plannerRequests = AtomicLong()
    private val plannerRuns = AtomicLong()
    private val savedEqWrites = AtomicLong()
    fun uiPoll() { if (BuildConfig.DEBUG) uiPolls.incrementAndGet() }
    fun plannerRequest() { if (BuildConfig.DEBUG) plannerRequests.incrementAndGet() }
    fun plannerRun() { if (BuildConfig.DEBUG) plannerRuns.incrementAndGet() }
    fun savedEqWrite() { if (BuildConfig.DEBUG) savedEqWrites.incrementAndGet() }

    fun snapshot(): org.json.JSONObject = org.json.JSONObject()
        .put("elapsedMs", android.os.SystemClock.elapsedRealtime())
        .put("uiPolls", uiPolls.get()).put("plannerRequests", plannerRequests.get())
        .put("plannerRuns", plannerRuns.get()).put("savedEqWrites", savedEqWrites.get())
        .put("detectionScans", DetectionMonitor.scans)
        .put("curveRevision", SvanRepository.curveRevision.value)
        .put("quality", SvanRepository.settings.value.quality.name)
        .put("settings", SvanRepository.settings.value.toJson())
        .put("eq", SvanRepository.eq.value.toJson())
}
