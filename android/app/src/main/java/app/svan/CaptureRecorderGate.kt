package app.svan

import java.util.concurrent.atomic.AtomicReference

/** Main capture and a compatibility check must never own two playback recorders at once. */
internal class CaptureRecorderGate {
    private val owner = AtomicReference<Lease?>(null)
    val currentPurpose: String? get() = owner.get()?.purpose

    inner class Lease internal constructor(val purpose: String) : AutoCloseable {
        override fun close() { owner.compareAndSet(this, null) }
    }

    fun tryAcquire(purpose: String): Lease? {
        val lease = Lease(purpose)
        return lease.takeIf { owner.compareAndSet(null, it) }
    }

    /** Used off the live audio path, during startup or by the probe worker. */
    fun await(purpose: String, timeoutMs: Long = 6_000, active: () -> Boolean): Lease {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (active()) {
            tryAcquire(purpose)?.let { return it }
            if (System.nanoTime() >= deadline) throw IllegalStateException("playback recorder is still occupied")
            Thread.sleep(5)
        }
        throw ProbeCancelled()
    }
}

internal object CaptureEvidencePolicy {
    /** A previous positive is a hint. Silence never proves a permanent application policy. */
    fun reusableStoredValue(value: Any?): Boolean = value == "CAPTURABLE"

    fun manifestAllows(targetSdk: Int, explicit: Boolean?): Boolean = explicit ?: (targetSdk >= 29)

    /** A corrupt/non-finite sample must not admit and mute a source. */
    fun hasSignal(samples: FloatArray, count: Int): Boolean =
        (0 until count).any { samples[it].isFinite() && samples[it] != 0f }

    /** Buffered pre-mute audio is not proof that the capture tap survives the mute. */
    fun tapSettled(phase: String, elapsedNs: Long): Boolean = phase != "muted" || elapsedNs >= 200_000_000L
}

/** A frame counter cannot detect a live recorder which stops returning frames altogether. */
internal class CaptureNoDataWatchdog(private val timeoutNs: Long = 2_500_000_000L) {
    private var emptySinceNs: Long? = null

    fun reset() { emptySinceNs = null }

    fun observe(sampleCount: Int, nowNs: Long): Boolean {
        if (sampleCount > 0) { reset(); return false }
        val since = emptySinceNs ?: nowNs.also { emptySinceNs = it }
        return nowNs - since >= timeoutNs
    }
}
