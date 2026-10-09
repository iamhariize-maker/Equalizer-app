package app.svan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class CapturePolicyTest {
    private fun route(sid: Int, uid: Int, owner: SessionRouter.Owner) = SessionRouter.Route(sid, "player", uid, owner)

    @Test fun startupRequiresDiscoveryAccessOrARealConnectedSource() {
        assertNotNull(CapturePolicy.startupBlock(false, emptyList(), 99))
        assertNotNull(CapturePolicy.startupBlock(false, listOf(route(0, 12, SessionRouter.Owner.ENGINE_A)), 99))
        assertNotNull(CapturePolicy.startupBlock(false, listOf(route(1, -1, SessionRouter.Owner.ENGINE_A)), 99))
        assertNotNull(CapturePolicy.startupBlock(false, listOf(route(1, 99, SessionRouter.Owner.ENGINE_A)), 99))
        assertNotNull(CapturePolicy.startupBlock(false, listOf(route(1, 12, SessionRouter.Owner.ENGINE_A).copy(playing = false)), 99))
        // Broadcast-known source works without DUMP; optional discovery is not a mandatory dependency.
        assertNull(CapturePolicy.startupBlock(false, listOf(route(1, 12, SessionRouter.Owner.ENGINE_A)), 99))
        assertNull(CapturePolicy.startupBlock(true, emptyList(), 99))
    }

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

    @Test fun pausedCapturedAppsDoNotReserveTheRecorderAndBlockTheNextMusicApp() {
        val paused = route(1, 10012, SessionRouter.Owner.ENGINE_B_MUTED).copy(playing = false)
        assertEquals(emptySet<Int>(), CapturePolicy.eligibleUids(listOf(paused), 99))
        val active = route(2, 10013, SessionRouter.Owner.ENGINE_B_MUTED).copy(playing = true)
        assertEquals(setOf(10013), CapturePolicy.eligibleUids(listOf(paused, active), 99))
    }

    @Test fun aPausedSiblingCannotMakeAnUnmutedActiveUidSafeToCapture() {
        val paused = route(1, 10012, SessionRouter.Owner.ENGINE_B_MUTED).copy(playing = false)
        val active = route(2, 10012, SessionRouter.Owner.ENGINE_A).copy(playing = true)
        assertEquals(emptySet<Int>(), CapturePolicy.eligibleUids(listOf(paused, active), 99))
    }

    private fun observed(sid: Int, uid: Int, state: String = "started", usage: String = "USAGE_MEDIA") =
        PlaybackSession(sid, uid, usage, state, 0, "player")

    @Test fun ignoredInterfaceAndPendingMediaSiblingsExcludeTheUid() {
        val routes = listOf(route(1, 10012, SessionRouter.Owner.ENGINE_B_MUTED))
        val music = observed(1, 10012)
        val interfaceSound = observed(2, 10012).copy(contentType = "CONTENT_TYPE_SONIFICATION")
        assertEquals(emptySet<Int>(), CapturePolicy.eligibleUids(routes, 99, listOf(music, interfaceSound)))
        assertEquals(emptySet<Int>(), CapturePolicy.eligibleUids(routes, 99, listOf(music, observed(3, 10012))))
    }

    @Test fun unrelatedUidAndNonMediaNotificationDoNotExcludeTheMusic() {
        val routes = listOf(route(1, 10012, SessionRouter.Owner.ENGINE_B_MUTED))
        assertEquals(setOf(10012), CapturePolicy.eligibleUids(routes, 99,
            listOf(observed(1, 10012), observed(2, 10013), observed(3, 10012, usage = "USAGE_NOTIFICATION"))))
    }

    @Test fun closedOrPausedSiblingDoesNotBlockReplacementPlayback() {
        val routes = listOf(route(4, 10012, SessionRouter.Owner.ENGINE_B_MUTED))
        assertEquals(setOf(10012), CapturePolicy.eligibleUids(routes, 99,
            listOf(observed(4, 10012), observed(1, 10012, "released"), observed(2, 10012, "paused"))))
    }

    @Test fun reusedSessionIdCannotBorrowAnotherUidsMute() {
        val routes = listOf(route(1, 10012, SessionRouter.Owner.ENGINE_B_MUTED), route(2, 10013, SessionRouter.Owner.ENGINE_B_MUTED))
        assertEquals(setOf(10012), CapturePolicy.eligibleUids(routes, 99,
            listOf(observed(1, 10013), observed(2, 10013))))
    }
}
