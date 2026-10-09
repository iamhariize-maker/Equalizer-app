package app.svan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureRoutingTest {
    private val spotify = "com.spotify.music"
    private val v1 = InstalledVersion(146810608L, 1791220371629L)
    private val v2 = InstalledVersion(146810700L, 1791999999999L)
    private fun key(pkg: String, v: InstalledVersion?) = CaptureVerdictKey.of(pkg, v?.code, v?.updatedMs)

    // ---- storage keys never become apps ----

    @Test fun keysAreParsedBackToPackageAndVersion() {
        val parsed = CaptureVerdictKey.parse(key(spotify, v1))!!
        assertEquals(spotify, parsed.pkg)
        assertEquals(v1, parsed.version)
        assertNull(CaptureVerdictKey.parse(spotify)!!.version)
        assertNull(CaptureVerdictKey.parse("com.spotify.music@abc@1"))
        assertNull(CaptureVerdictKey.parse("com.spotify.music@1"))
        assertNull(CaptureVerdictKey.parse("a@1@2@3"))
        assertNull(CaptureVerdictKey.parse(""))
        assertNull(CaptureVerdictKey.parse("bad package@1@2"))
    }

    @Test fun theUiSeesOneVerdictPerPackageNeverAStorageKey() {
        val stored = mapOf(key(spotify, v1) to "CAPTURABLE")
        val shown = CaptureRecords.current(stored) { if (it == spotify) v1 else null }
        assertEquals(mapOf(spotify to "CAPTURABLE"), shown)
        assertTrue(shown.keys.none { '@' in it })
    }

    @Test fun anOlderVersionsVerdictIsNotShownOrUsedAfterAnUpdate() {
        val stored = mapOf(key(spotify, v1) to "BLOCKED")
        assertTrue(CaptureRecords.current(stored) { v2 }.isEmpty())
        assertEquals(setOf(key(spotify, v1)), CaptureRecords.discardable(stored, { v2 }) { CaptureRecords.verdict(it) != null })
    }

    @Test fun aLegacyPlainRecordIsShadowedWhenTheVersionCanBeRead() {
        val stored = mapOf(spotify to "BLOCKED", key(spotify, v1) to "CAPTURABLE")
        assertEquals(mapOf(spotify to "CAPTURABLE"), CaptureRecords.current(stored) { v1 })
        assertEquals(setOf(spotify), CaptureRecords.discardable(stored, { v1 }) { true })
    }

    @Test fun anInvisiblePackageKeepsItsPlainRecordBecauseTheVersionCannotBeRead() {
        // The manifest lists only some packages as visible: no version does not mean uninstalled.
        val stored = mapOf("com.example.player" to "CAPTURABLE")
        assertEquals(mapOf("com.example.player" to "CAPTURABLE"), CaptureRecords.current(stored) { null })
        assertTrue(CaptureRecords.discardable(stored, { null }) { true }.isEmpty())
    }

    @Test fun unresolvedPlayersAndDamagedRecordsNeverBecomeCards() {
        val stored = mapOf("uid:10123" to "BLOCKED", "garbage@x@y" to "BLOCKED", spotify to "MAYBE")
        assertTrue(CaptureRecords.current(stored) { null }.isEmpty())
        val drop = CaptureRecords.discardable(stored, { null }) { CaptureRecords.verdict(it) != null }
        assertEquals(setOf("garbage@x@y", spotify), drop)
        assertFalse("uid placeholder keeps its verdict", "uid:10123" in drop)
    }

    @Test fun aDamagedVerdictValueIsReadAsNoVerdict() {
        assertNull(CaptureRecords.verdict("MAYBE"))
        assertNull(CaptureRecords.verdict(null))
        assertNull(CaptureRecords.verdict(3))
        assertEquals(CaptureCompat.Verdict.BLOCKED, CaptureRecords.verdict("BLOCKED"))
    }

    // ---- when a late capture check may run ----

    @Test fun aNewPlayerIsCheckedImmediatelyAndOnlyOnceAtATime() {
        val s = LateProbeSchedule()
        assertTrue(s.due(spotify, 0))
        s.started(spotify)
        assertFalse("a check is already running", s.due(spotify, 1_000))
    }

    @Test fun silenceIsRetriedAfterAPauseThenStopsAfterTheLimit() {
        val s = LateProbeSchedule(maxChecks = 3, silentRetryMs = 30_000)
        var now = 0L
        repeat(3) { n ->
            assertTrue("check ${n + 1} is due", s.due(spotify, now))
            s.started(spotify); s.silent(spotify, now)
            assertFalse(s.due(spotify, now + 29_999))
            now += 30_000
        }
        assertTrue(s.gaveUp(spotify))
        assertFalse(s.due(spotify, now + 1_000_000))
    }

    @Test fun aRefusedRecorderBacksOffThenGivesUpWithoutEverCountingAsSilence() {
        val s = LateProbeSchedule()
        s.started(spotify); s.refused(spotify, 0)
        assertFalse(s.due(spotify, 59_999)); assertTrue(s.due(spotify, 60_000))
        s.started(spotify); s.refused(spotify, 60_000)
        assertFalse(s.due(spotify, 60_000 + 299_999)); assertTrue(s.due(spotify, 60_000 + 300_000))
        s.started(spotify); s.refused(spotify, 400_000)
        assertTrue(s.gaveUp(spotify))
    }

    @Test fun aCancelledCheckCostsNothingAndASettledOneForgetsTheApp() {
        val s = LateProbeSchedule()
        s.started(spotify); s.cancelled(spotify)
        assertTrue(s.due(spotify, 0))
        s.started(spotify); s.settled(spotify)
        assertTrue(s.due(spotify, 0)); assertFalse(s.gaveUp(spotify))
    }

    @Test fun theScanTickCannotReRouteTheSameSessionMoreThanEveryFifteenSeconds() {
        val s = LateProbeSchedule()
        assertTrue(s.tickReady(7, 0)); assertFalse(s.tickReady(7, 14_999)); assertTrue(s.tickReady(7, 15_000))
        assertTrue("another session is independent", s.tickReady(8, 1))
    }

    @Test fun twoSilentChecksOfAPlayingAppSaveBlockedButOneDoesNot() {
        assertFalse(SilentStrikes.blocks(1))
        assertTrue(SilentStrikes.blocks(2))
    }

    // ---- what the Hi-Fi screen says ----

    @Test fun detectedMusicOnSystemEffectsIsNeverCalledNotConnected() {
        val line = HiFiStatus.engine(running = true, systemRunning = true, fullChainApps = 0, systemEffectsApps = 1)
        assertEquals("Music detected · using system effects", line.title)
        assertTrue("1 app(s) playing" in line.detail)
        val note = HiFiStatus.idleNote(running = true, fullChainApps = 0, systemEffectsApps = 1)!!
        assertFalse("No music" in note)
        assertTrue("detected" in note)
    }

    @Test fun theHeaderFollowsTheRealRoutes() {
        assertEquals("Audiophile engine off", HiFiStatus.engine(false, false, 0, 0).title)
        assertEquals("Audiophile engine off", HiFiStatus.engine(false, true, 2, 2).title)
        assertEquals("Waiting for music", HiFiStatus.engine(true, true, 0, 0).title)
        assertEquals("Audiophile engine connected", HiFiStatus.engine(true, true, 1, 1).title)
        assertNull("no warning while the full chain carries music", HiFiStatus.idleNote(true, 1, 0))
        assertNull(HiFiStatus.idleNote(false, 0, 0))
        assertTrue("No music detected" in HiFiStatus.idleNote(true, 0, 0)!!)
    }

    @Test fun everyReasonHasAnActionableSentence() {
        RouteReason.entries.forEach { r ->
            assertNotNull(r.text)
            assertTrue("${r.name} should be a full sentence", r.text.length > 30 && r.text.trimEnd().endsWith("."))
        }
    }
}
