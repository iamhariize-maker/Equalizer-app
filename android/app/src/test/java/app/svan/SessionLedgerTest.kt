package app.svan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLedgerTest {
    private val own = AudioFlingerDumpTest.OWN_PID
    private val af = AudioFlingerDump.parse(AudioFlingerDumpTest.DUMP)

    private fun apc(sid: Int, uid: Int, pid: Int, state: String = "started", pkg: String = "uid:$uid", type: String = "android.media.AudioTrack") =
        PlaybackSession(sid, uid, "USAGE_MEDIA", state, 0, pkg, pid, type)

    private fun merge(players: List<PlaybackSession>?, snapshot: AfSnapshot? = af) =
        SessionLedger.merge(players, snapshot, ownPid = own, ownUid = 10399)

    @Test fun serverTablesAloneStillFindEveryRealPlayer() {
        val l = merge(players = null)
        val bySid = l.sessions.associateBy { it.session.sessionId }
        // YouTube Music (active + a second, idle session of the same app) and Neutron (direct).
        // The own patch track and the system-uid sound are not players.
        assertEquals(setOf(1281, 1282, 2049), bySid.keys)
        assertEquals("paused", bySid[1282]!!.session.state)
        assertEquals("com.google.android.apps.youtube.music", bySid[1281]!!.session.packageName)
        assertEquals(10187, bySid[1281]!!.session.uid)
        assertEquals(SessionSource.AUDIO_SERVER, bySid[1281]!!.source)
        assertEquals("started", bySid[1281]!!.session.state)
    }

    @Test fun playerListAloneStillWorksWithoutTheServerTables() {
        val l = merge(listOf(apc(1281, 10187, 4100, pkg = "com.google.android.apps.youtube.music")), snapshot = null)
        assertEquals(1, l.sessions.size)
        assertEquals(SessionSource.AUDIO_SERVICE, l.sessions.single().source)
        assertNull(l.sessions.single().effectsPossible)
    }

    @Test fun bothSourcesAreCrossCheckedAndPathIsKnown() {
        val l = merge(listOf(apc(1281, 10187, 4100, "paused")))
        val s = l.sessions.first { it.session.sessionId == 1281 }
        assertEquals(SessionSource.BOTH, s.source)
        assertEquals("paused", s.session.state) // the player list's own state wins
        assertEquals(true, s.serverActive)
        assertEquals(true, s.effectsPossible)
        assertEquals("mixer", s.pathLabel)
        assertTrue(s.devices.contains("BLUETOOTH_A2DP"))
        // the package name is repaired from the audio server when the app could not be resolved by uid
        assertEquals("com.google.android.apps.youtube.music", s.session.packageName)
    }

    @Test fun nativePlayerWithoutSessionIdIsResolvedThroughItsPid() {
        val native = apc(0, 10250, 5200, type = "AAudio")
        val l = merge(listOf(native))
        val s = l.sessions.first { it.session.sessionId == 2049 }
        assertEquals(SessionSource.BOTH, s.source)
        assertEquals("com.neutroncode.mp", s.session.packageName)
        assertEquals(false, s.effectsPossible) // DIRECT output bypasses session effects
        assertEquals("direct", s.pathLabel)
        assertTrue(l.unresolved.isEmpty())
    }

    @Test fun sessionlessPlayerWithNoServerTrackIsReportedUnresolved() {
        val l = merge(listOf(apc(0, 10250, 9999, type = "AAudio")))
        assertEquals(1, l.unresolved.size)
        assertFalse(l.sessions.any { it.pid == 9999 })
    }

    @Test fun ownTracksAndSystemUidsAreNeverPlayers() {
        val l = merge(players = null)
        assertTrue(l.sessions.none { it.session.sessionId == 3001 || it.session.sessionId == 901 })
    }

    @Test fun capturePolicyFlagsFromAnyRecordAreKept() {
        val l = merge(listOf(apc(1281, 10187, 4100).copy(flags = 0x400), apc(1281, 10187, 4100, "paused").copy(flags = 0)))
        assertEquals(0x400, l.sessions.first { it.session.sessionId == 1281 }.session.flags and 0x400)
    }

    @Test fun verifierDistinguishesProcessingSuspendedBypassedWaitingAndMissing() {
        assertEquals(Verification.PROCESSING, EffectVerifier.verify(1281, af, own))
        assertEquals(Verification.WAITING, EffectVerifier.verify(2049, af.copy(tracks = af.tracks.filter { it.sessionId != 2049 }), own))
        assertEquals(Verification.BYPASSED, EffectVerifier.verify(2049, af, own))
        val suspended = af.copy(effects = af.effects.map { if (it.sessionId == 1281) it.copy(suspended = true) else it })
        assertEquals(Verification.SUSPENDED, EffectVerifier.verify(1281, suspended, own))
        // 1282 has a mixer track but no effect while our other effects are visible: the effect was lost.
        assertEquals(Verification.MISSING, EffectVerifier.verify(1282, af, own))
        // If the parser can't see ANY of our effects it must not claim they are missing.
        val blind = af.copy(effects = emptyList())
        assertEquals(Verification.UNKNOWN, EffectVerifier.verify(1281, blind, own))
        assertEquals(Verification.UNKNOWN, EffectVerifier.verify(1281, null, own))
    }

    @Test fun healthTellsTheTruthAboutEachSituation() {
        val ok = merge(null).sessions
        fun assess(perm: Boolean, p: Boolean, s: Boolean, pub: Int?, own: Int = 0, sessions: List<LedgerSession> = ok, un: List<PlaybackSession> = emptyList(), v: Map<Int, Verification> = emptyMap()) =
            DetectionStatus.assess(perm, p, s, pub, own, sessions, un, v).first
        assertEquals(Health.NO_PERMISSION, assess(false, false, false, 1, sessions = emptyList()))
        assertEquals(Health.BLIND, assess(true, false, false, 1, sessions = emptyList()))
        assertEquals(Health.DEGRADED, assess(true, true, true, 2)) // Neutron on a direct path
        assertEquals(Health.OK, assess(true, true, true, 1, sessions = ok.filter { it.session.sessionId == 1281 }, v = mapOf(1281 to Verification.PROCESSING)))
        assertEquals(Health.BLIND, assess(true, true, true, 1, sessions = emptyList()))
        assertEquals(Health.IDLE, assess(true, true, true, 0, sessions = emptyList()))
        // Svan's own Engine B output must not make a quiet phone look blind.
        assertEquals(Health.IDLE, assess(true, true, true, 1, own = 1, sessions = emptyList()))
    }

    @Test fun releasedOrNonMediaSessionlessRecordsNeverOverrideTheLiveSession() {
        // YouTube Music's live record plus a released session-0 record (an old SoundPool) and a
        // session-0 notification click, all from the same pid: the live session must stay "started"
        // with its media usage, otherwise the router would close it on every scan.
        val l = merge(listOf(
            apc(1281, 10187, 4100, pkg = "com.google.android.apps.youtube.music"),
            apc(0, 10187, 4100, "released"),
            apc(0, 10187, 4100).copy(usage = "USAGE_ASSISTANCE_SONIFICATION"),
        ))
        val s = l.sessions.first { it.session.sessionId == 1281 }
        assertEquals("started", s.session.state)
        assertEquals("USAGE_MEDIA", s.session.usage)
        // The sessionless record listed first must not win over the real record either.
        val reordered = merge(listOf(apc(0, 10187, 4100, "paused"), apc(1281, 10187, 4100, pkg = "com.google.android.apps.youtube.music")))
        assertEquals("started", reordered.sessions.first { it.session.sessionId == 1281 }.session.state)
        // A released or non-media session-0 record is not an unresolved player either.
        assertTrue(merge(listOf(apc(0, 10250, 9999, "released"))).unresolved.isEmpty())
    }

    @Test fun systemUidsOfSecondaryUsersAreNotPlayers() {
        val mixer = AfThread("AudioOut_D", "MIXER", true, "AUDIO_DEVICE_OUT_SPEAKER")
        val snap = AfSnapshot(
            threads = listOf(mixer),
            tracks = listOf(AfTrack(mixer, 700, 4001, true, 1), AfTrack(mixer, 701, 4002, true, 1)),
            effects = emptyList(),
            refs = listOf(AfSessionRef(4001, 700, 1_001_041, ""), AfSessionRef(4002, 701, 1_010_187, "com.example.player")),
        )
        val l = merge(players = null, snapshot = snap)
        assertEquals(listOf(4002), l.sessions.map { it.session.sessionId })
    }

    @Test fun verifierNeverClaimsMissingForASessionWithoutAServerTrack() {
        // No track and no effect for 7777 while our other effects are visible: the effect may sit in a
        // chain layout the parser does not know. "Missing" here would re-attach on every scan.
        assertEquals(Verification.UNKNOWN, EffectVerifier.verify(7777, af, own))
    }

    @Test fun missingEffectRepairIsBoundedAndReArmedOnlyByProof() {
        val r = MissingEffectRepair(maxAttempts = 3, firstDelayMs = 10_000L)
        assertTrue(r.shouldRepair(5, Verification.MISSING, 0))
        assertFalse(r.shouldRepair(5, Verification.MISSING, 5_000)) // spaced
        assertTrue(r.shouldRepair(5, Verification.MISSING, 10_000))
        assertFalse(r.shouldRepair(5, Verification.MISSING, 25_000))
        assertFalse(r.shouldRepair(5, Verification.UNKNOWN, 40_000)) // an unread report proves nothing
        assertTrue(r.shouldRepair(5, Verification.MISSING, 40_000))
        assertTrue(r.exhausted(5))
        assertFalse(r.shouldRepair(5, Verification.MISSING, 10_000_000)) // gave up: the report disagrees with a working attach
        assertFalse(r.shouldRepair(5, Verification.PROCESSING, 10_000_001)) // proof re-arms it
        assertTrue(r.shouldRepair(5, Verification.MISSING, 10_000_002))
    }

    @Test fun neutronGetsTheDspEffectTipOnlyWhenItIsTheProblem() {
        val ok = merge(null).sessions
        // Neutron (2049) is playing on a direct output.
        val direct = DetectionStatus.assess(true, true, true, 2, 0, ok, emptyList(), emptyMap())
        assertEquals(Health.DEGRADED, direct.first)
        assertTrue(direct.third.contains("DSP Effect (Device)"))
        // Neutron is active but Android gave no session.
        val sessionless = DetectionStatus.assess(true, true, true, 1, 0, emptyList(),
            listOf(apc(0, 10250, 9999, pkg = "com.neutroncode.mp", type = "AAudio")), emptyMap())
        assertEquals(Health.BLIND, sessionless.first)
        assertTrue(sessionless.third.contains("DSP Effect (Device)"))
        // Another player's problem gets no Neutron advice.
        val yt = ok.filter { it.session.sessionId == 1281 }
        val missing = DetectionStatus.assess(true, true, true, 1, 0, yt, emptyList(), mapOf(1281 to Verification.MISSING))
        assertEquals(Health.DEGRADED, missing.first)
        assertFalse(missing.third.contains("Neutron"))
    }

    @Test fun healthLineNamesTheAppByItsInstalledLabel() {
        val labels = mapOf("com.neutroncode.mp" to "Neutron", "com.google.android.apps.youtube.music" to "YouTube Music")
        assertEquals("Neutron", DetectionStatus.appLabel("com.neutroncode.mp") { labels[it] })
        assertEquals("mp", DetectionStatus.appLabel("com.neutroncode.mp") { null }) // not installed: last segment
        assertEquals("mp", DetectionStatus.appLabel("com.neutroncode.mp") { throw SecurityException() })
        assertEquals("uid:10250", DetectionStatus.appLabel("uid:10250") { "never asked" })
        val yt = merge(null).sessions.filter { it.session.sessionId == 1281 }
        val ok = DetectionStatus.assess(true, true, true, 1, 0, yt, emptyList(), mapOf(1281 to Verification.PROCESSING)) { labels[it] }
        assertEquals("Detected: YouTube Music", ok.second)
        val direct = DetectionStatus.assess(true, true, true, 2, 0, merge(null).sessions, emptyList(), emptyMap()) { labels[it] }
        assertTrue(direct.second.startsWith("Neutron is playing on a direct output"))
    }
}
