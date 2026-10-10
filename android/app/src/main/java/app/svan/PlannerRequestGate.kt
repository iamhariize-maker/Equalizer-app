package app.svan

/** A bounded planner queue. Immediate edits outrank periodic slew requests. */
internal class PlannerRequestGate {
    private var running = false
    private var pending = false
    private var immediate = false

    @Synchronized fun request(urgent: Boolean): Boolean {
        pending = true
        immediate = immediate || urgent
        if (running) return false
        running = true
        return true
    }

    @Synchronized fun takeImmediate(): Boolean {
        val urgent = immediate
        immediate = false
        pending = false
        return urgent
    }

    @Synchronized fun complete(): Boolean {
        if (pending) return true
        running = false
        return false
    }
}
