package app.svan

import org.junit.Assert.assertEquals
import org.junit.Test

class CapturePolicyTest {
    private fun route(sid: Int, uid: Int, owner: SessionRouter.Owner) = SessionRouter.Route(sid, "player", uid, owner)

    @Test fun unknownBlockedAndProbingAudioCannotBeDuplicated() {
        val routes = listOf(
            route(1, -1, SessionRouter.Owner.ENGINE_B_MUTED),
            route(2, 10, SessionRouter.Owner.ENGINE_A),
            route(3, 11, SessionRouter.Owner.PROBING),
            route(4, 12, SessionRouter.Owner.ENGINE_B_MUTED),
            route(5, 99, SessionRouter.Owner.ENGINE_B_MUTED),
        )
        assertEquals(setOf(12), CapturePolicy.eligibleUids(routes, 99))
    }

    @Test fun oneUnmutedSessionExcludesTheWholeUid() {
        val routes = listOf(route(1, 12, SessionRouter.Owner.ENGINE_B_MUTED), route(2, 12, SessionRouter.Owner.ENGINE_A))
        assertEquals(emptySet<Int>(), CapturePolicy.eligibleUids(routes, 99))
        assertEquals(setOf(12), CapturePolicy.eligibleUids(routes.take(1), 99))
    }

    @Test fun noRoutesMeansNoCapture() {
        assertEquals(emptySet<Int>(), CapturePolicy.eligibleUids(emptyList(), 99))
    }
}
