package app.svan

/** Only known, exclusively muted UIDs may enter the capture mix. */
object CapturePolicy {
    fun eligibleUids(routes: Collection<SessionRouter.Route>, ownUid: Int): Set<Int> =
        routes.filter { it.uid >= 0 && it.uid != ownUid }.groupBy { it.uid }
            .filterValues { sessions -> sessions.all { it.owner == SessionRouter.Owner.ENGINE_B_MUTED } }
            .keys.toSet()
}
