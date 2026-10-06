package app.svan

/** Only known, exclusively muted UIDs may enter the capture mix. */
object CapturePolicy {
    /** Missing discovery permission plus no known source must never launch a silent capture session. */
    fun startupBlock(dumpPermission: Boolean, routes: Collection<SessionRouter.Route>, ownUid: Int): String? =
        if (!dumpPermission && routes.none { it.sessionId > 0 && it.uid >= 0 && it.uid != ownUid && it.playing != false })
            "No music session is connected and enhanced detection is not enabled. Complete Music detection in Hi-Fi, keep your song playing, then try again."
        else null

    fun eligibleUids(routes: Collection<SessionRouter.Route>, ownUid: Int): Set<Int> =
        routes.filter { it.uid >= 0 && it.uid != ownUid }.groupBy { it.uid }
            .filterValues { sessions -> sessions.all { it.owner == SessionRouter.Owner.ENGINE_B_MUTED } }
            .keys.toSet()
}
