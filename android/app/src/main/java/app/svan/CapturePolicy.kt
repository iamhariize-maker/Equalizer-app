package app.svan

/** Only known, exclusively muted UIDs may enter the capture mix. */
object CapturePolicy {
    /** Missing discovery permission plus no known source must never launch a silent capture session. */
    fun startupBlock(dumpPermission: Boolean, routes: Collection<SessionRouter.Route>, ownUid: Int,
                     sharedOutput: Boolean = false): String? =
        if (sharedOutput) "Stop shared-output EQ in Hi-Fi before starting the audiophile engine."
        else if (!dumpPermission && routes.none { it.sessionId > 0 && it.uid >= 0 && it.uid != ownUid && it.playing != false })
            "No music session is connected and enhanced detection is not enabled. Complete Music detection in Hi-Fi, keep your song playing, then try again."
        else null

    fun eligibleUids(routes: Collection<SessionRouter.Route>, ownUid: Int,
                     observed: Collection<PlaybackSession> = emptyList()): Set<Int> {
        val bySession = routes.associateBy { it.sessionId }
        // A UID filter captures every MEDIA track for that UID, including tracks
        // discovery deliberately left unrouted. Every active sibling must be muted.
        val excluded = observed.filter { s ->
            val route = bySession[s.sessionId]
            s.usage == "USAGE_MEDIA" && s.state == "started" &&
                (route == null || route.owner != SessionRouter.Owner.ENGINE_B_MUTED || route.uid != s.uid)
        }.map { it.uid }.toSet()
        return routes.filter { it.uid >= 0 && it.uid != ownUid }.groupBy { it.uid }
            .filterValues { sessions -> sessions.all { it.owner == SessionRouter.Owner.ENGINE_B_MUTED } &&
                sessions.any { it.playing != false } }
            .keys.toSet() - excluded
    }
}
