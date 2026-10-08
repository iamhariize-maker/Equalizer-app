package app.svan

/** Router-worker owned. Only the capture-stop acknowledgement can complete a pending handoff. */
internal class SharedOutputHandoff {
    var waiting = false
        private set

    /** Returns true when no capture needs to stop first. */
    fun request(captureActive: Boolean): Boolean {
        waiting = captureActive
        return !captureActive
    }

    fun captureStopped(serviceEnabled: Boolean): Boolean {
        val attach = waiting && serviceEnabled
        waiting = false
        return attach
    }

    fun cancel() { waiting = false }

    companion object {
        /** Android TYPE_REMOTE_SUBMIX=25 is a virtual capture endpoint, not a headphone change. */
        fun physicalOutputChanged(types: List<Int>): Boolean = types.any { it != 25 }
    }
}
