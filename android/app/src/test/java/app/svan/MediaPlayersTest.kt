package app.svan

import org.junit.Assert.*
import org.junit.Test

class MediaPlayersTest {
    private val spotify = MediaPlayer("com.spotify.music", 10101, 3, true)
    private fun session(uid: Int = 10101, pkg: String = "uid:$uid", state: String = "started") =
        LedgerSession(PlaybackSession(41, uid, "USAGE_MEDIA", state, 0x400, pkg, 7), SessionSource.AUDIO_SERVICE, 7)
    private fun snapshot(vararg players: MediaPlayer) = MediaPlayerSnapshot(true, true, players.toList())

    @Test fun namesStreamingPlayerWithoutChangingRoutingOrCapturePolicy() {
        val original = session()
        val named = MediaPlayers.name(Ledger(listOf(original), emptyList()), snapshot(spotify)).sessions.single()
        assertEquals(original.copy(session = original.session.copy(packageName = spotify.packageName)), named)
        assertTrue(named.session.flagsBlockCapture)
    }

    @Test fun mediaSessionAloneNeverCreatesAnAudioSession() {
        val ledger = MediaPlayers.name(Ledger(emptyList(), emptyList()), snapshot(spotify))
        assertTrue(ledger.sessions.isEmpty())
        assertEquals(listOf(spotify), MediaPlayers.withoutAudioSession(snapshot(spotify).playing, ledger.sessions))
    }

    @Test fun pausedBufferingRemoteAndFailedSnapshotsDoNotProveLocalPlayback() {
        assertTrue(snapshot(spotify.copy(state = 2), spotify.copy(state = 6), spotify.copy(state = null), spotify.copy(local = false)).playing.isEmpty())
        assertTrue(snapshot(spotify).copy(available = false).playing.isEmpty())
        val original = Ledger(listOf(session()), emptyList())
        assertEquals(original, MediaPlayers.name(original, snapshot(spotify).copy(available = false)))
    }

    @Test fun sharedUidAndWorkProfilesNeverBorrowAnotherPlayersName() {
        val original = Ledger(listOf(session()), emptyList())
        assertEquals(original, MediaPlayers.name(original, snapshot(spotify, spotify.copy(packageName = "com.amazon.mp3"))))
        assertEquals(original, MediaPlayers.name(original, snapshot(spotify.copy(uid = 110101))))
        assertEquals(original, MediaPlayers.name(original, snapshot(spotify.copy(local = false))))
        // One known package's live track cannot hide a different player sharing its UID.
        val amazon = spotify.copy(packageName = "com.amazon.mp3")
        assertEquals(listOf(amazon), MediaPlayers.withoutAudioSession(listOf(spotify, amazon), listOf(session(pkg = spotify.packageName))))
        assertEquals(listOf(spotify, amazon), MediaPlayers.withoutAudioSession(listOf(spotify, amazon), listOf(session())))
    }

    @Test fun knownPackageWinsAndSeveralSessionsMatchOnePlayer() {
        val known = Ledger(listOf(session(pkg = "com.amazon.mp3")), emptyList())
        assertEquals(known, MediaPlayers.name(known, snapshot(spotify)))
        assertTrue(MediaPlayers.withoutAudioSession(listOf(spotify), listOf(session(), session().copy(session = session().session.copy(sessionId = 42)))).isEmpty())
        assertEquals(listOf(spotify), MediaPlayers.withoutAudioSession(listOf(spotify), listOf(session(state = "paused"))))
    }

    @Test fun healthNamesAnUnmatchedStreamingAppEvenWithNoPublicPlaybackSignal() {
        val verdict = DetectionStatus.assess(true, true, true, 0, 0, emptyList(), emptyList(), emptyMap(), mediaPlayers = listOf(spotify)) { "Spotify" }
        assertEquals(Health.BLIND, verdict.first)
        assertTrue(verdict.second.contains("Spotify"))
        assertFalse(verdict.third.contains("direct")) // no output-path evidence
        val noPermission = DetectionStatus.assess(false, false, false, null, 0, emptyList(), emptyList(), emptyMap(), mediaPlayers = listOf(spotify)) { "Spotify" }
        assertEquals(Health.NO_PERMISSION, noPermission.first)
        assertTrue(noPermission.second.contains("Spotify"))
    }

    @Test fun oneProcessedAppDoesNotHideAnotherMissingStreamingApp() {
        val verdict = DetectionStatus.assess(true, true, true, 1, 0, listOf(session()), emptyList(), emptyMap(),
            mediaPlayers = listOf(spotify, MediaPlayer("com.amazon.mp3", 10202, 3, true)))
        assertEquals(Health.DEGRADED, verdict.first)
    }
}
