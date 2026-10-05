package app.svan

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class DetectionFixturesTest {
    private fun fixture(name: String) = javaClass.getResource("/detection/$name")!!.readText()
    private val flinger = fixture("aosp-shaped-streaming-flinger.txt")
    private val audio = fixture("aosp-shaped-streaming-audio.txt")

    @Test fun tecnoMissingPermissionReportRemainsASetupFailureAndBlocksSilentCapture() {
        val report = fixture("tecno-lh7n-2026-10-05-no-dump.txt")
        fun value(label: String) = report.lines().single { it.startsWith(label) }.substringAfter(":").trim()
        val permission = value("DUMP (music detection)").toBooleanStrict()
        val publicActive = value("Android public API says active players").toInt()
        // This phone report contains no raw Android player records, audio-server tables, or routes.
        assertTrue(report.contains("Sessions Svan found (0)"))
        assertTrue(PlaybackSessions.parseAll(report).isEmpty())
        assertFalse(AudioFlingerDump.parse(report).usable)
        val verdict = DetectionStatus.assess(permission, false, false, publicActive, 1, emptyList(), emptyList(), emptyMap())
        assertEquals(Health.NO_PERMISSION, verdict.first)
        assertNotNull(CapturePolicy.startupBlock(permission, emptyList(), 10276))
        assertFalse(verdict.third.contains("offload"))
    }

    @Test fun sessionlessSiblingsKeepEveryCaptureOptOut() {
        val players = PlaybackSessions.parseAll(audio)
        assertEquals(setOf(1281, 2049, 0), players.map { it.sessionId }.toSet())
        val native = players.single { it.sessionId == 0 }
        assertEquals("started", native.state)
        assertTrue(native.flagsBlockCapture)
    }

    @Test fun overflowingCaptureFlagsAreConservativeRatherThanWrappingToPermission() {
        listOf("0x100000000", "0xffffffffffffffffffffffff", "999999999999999999999").forEach { flags ->
            val player = PlaybackSessions.parse("piid:1 uid=10187 state=2 usage=1 flags=$flags sessionId=1281").single()
            assertTrue(player.flagsBlockCapture)
        }
        val validUnsigned = PlaybackSessions.parse("piid:1 uid=10187 state=2 usage=1 flags=0xffffffff sessionId=1281").single()
        assertTrue(validUnsigned.flagsBlockCapture)
    }

    @Test fun overflowingIdentifiersDoNotDiscardOtherTracksRefsOrEffects() {
        // Every formerly unchecked integer conversion is exercised independently.
        val corruptions = listOf(
            "1281    1    4100" to "999999999999999999999    1    4100",
            "1281    1    4100" to "1281    1    999999999999999999999",
            "yes    4100    1281" to "yes    999999999999999999999    1281",
            "yes    4100    1281" to "yes    4100    999999999999999999999",
            "1 effects for session 1281" to "1 effects for session 999999999999999999999",
            "777    2147483647  yes" to "999999999999999999999    2147483647  yes",
        )
        corruptions.forEach { (from, to) ->
            assertTrue("fixture must contain $from", flinger.contains(from))
            val af = AudioFlingerDump.parse(flinger.replace(from, to))
            assertTrue(af.tracks.any { it.sessionId == 2049 })
            assertTrue(af.refs.any { it.sessionId == 2049 })
            // Even an invalid chain header can be repaired by the independently valid effect-state row.
            assertTrue(af.effects.any { it.sessionId == 1281 })
        }
        val noSession = AudioFlingerDump.parse(flinger.replace("1 effects for session 1281", "1 effects for session 999999999999999999999")
            .replace("01281   003", "999999999999999999999   003"))
        assertTrue(noSession.effects.none { it.sessionId == 1281 })
        val mmap = """
            Output thread 0x11, name AudioOut_1, tid 1, type 5 (MMAP_PLAYBACK):
              Client Session Port Id Format ChnMask SRate Flags Usg CT
              999999999999999999999 22 1 5 3 48000 0 1 2
              70 44 2 5 3 48000 0 1 2
        """.trimIndent()
        assertEquals(listOf(44), AudioFlingerDump.parse(mmap).tracks.map { it.sessionId })
    }

    @Test fun deterministicMutationsAndEveryLineTruncationNeverThrowOrInventSessions() {
        val valid = AudioFlingerDump.parse(flinger).tracks.map { it.sessionId }.toSet()
        val lines = flinger.lines()
        for (n in lines.indices) {
            val af = AudioFlingerDump.parse(lines.take(n).joinToString("\n"), partial = true)
            assertTrue(af.partial)
            assertTrue(af.tracks.all { it.sessionId in valid })
            assertNotEquals(Verification.MISSING, EffectVerifier.verify(1281, af, 777))
        }
        val rng = Random(369)
        repeat(200) {
            val modified = lines.map { line ->
                when (rng.nextInt(6)) {
                    0 -> line.replace(Regex("(?<![A-Za-z_])\\b\\d{3,}\\b"), "999999999999999999999")
                    1 -> line.replace(" ", "\t")
                    2 -> line.take(rng.nextInt(line.length + 1))
                    else -> line
                }
            }.joinToString("\n")
            val af = AudioFlingerDump.parse(modified, partial = true)
            assertTrue("iteration $it invented a track: ${af.tracks.filter { t -> t.sessionId !in valid || t.pid < 0 }}\n$modified",
                af.tracks.all { t -> t.sessionId in valid && t.pid >= 0 })
            PlaybackSessions.parseAll(audio.replace("10187", "999999999999999999999"))
        }
    }
}
