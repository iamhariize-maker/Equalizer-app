package app.svan

import app.svan.model.AudioSettings
import app.svan.model.EngineMode
import app.svan.model.EqState
import org.junit.Assert.*
import org.junit.Test

class OnboardingStateTest {
    @Test fun peaksRequireAnActualAudiophileRouteAndAvailableReadings() {
        val b = WorkingPlayer("player", "Player", true, true, UiEngine.AUDIOPHILE)
        val routed = WorkingState.derive(1, listOf(b), true)
        assertTrue(routed.showsCapturePeaks(hasStats = true))
        assertFalse(routed.showsCapturePeaks(hasStats = false))
        assertFalse(WorkingState.derive(1, listOf(b.copy(engine = UiEngine.SYSTEM_EFFECTS)), true)
            .showsCapturePeaks(hasStats = true))
    }

    @Test fun idleUnknownUnreachableAndFixturesNeverShowStaleCapturePeaks() {
        for (state in listOf(WorkingState.derive(0, emptyList(), true),
            WorkingState.derive(null, emptyList(), true), WorkingState.derive(1, emptyList(), true))) {
            assertFalse(state.showsCapturePeaks(hasStats = true))
        }
        val b = WorkingPlayer("player", "Player", true, true, UiEngine.AUDIOPHILE)
        assertFalse(WorkingState.derive(1, listOf(b), true).showsCapturePeaks(hasStats = true, fixture = true))
    }

    private val off = DebuggingState(SettingState.OFF, SettingState.OFF, SettingState.OFF)
    private val on = DebuggingState(SettingState.ON, SettingState.OFF, SettingState.ON)
    private fun snapshot(installed: Boolean? = false, running: Boolean? = false,
                         authorized: Boolean? = false, dump: Boolean = false,
                         debugging: DebuggingState = off) = WizardSnapshot(installed, running, authorized, dump, debugging)

    @Test fun wizardDerivesEveryStepFromObservedState() {
        assertEquals(WizardStep.INSTALL, snapshot().step)
        assertEquals(WizardStep.DEBUGGING, snapshot(installed = true).step)
        assertEquals(WizardStep.START, snapshot(true, debugging = on).step)
        assertEquals(WizardStep.AUTHORIZE, snapshot(true, true, debugging = on).step)
        assertEquals(WizardStep.GRANT, snapshot(true, true, true, debugging = on).step)
        assertEquals(WizardStep.FINISH, snapshot(dump = true).step)
    }

    @Test fun runningBinderDoesNotRequireDebuggingToBeReenabled() {
        assertEquals(WizardStep.AUTHORIZE, snapshot(true, true, debugging = off).step)
        assertEquals(WizardStep.GRANT, snapshot(true, true, true, debugging = off).step)
    }

    @Test fun retainedDumpIsReadyEvenWhenHelperHasStopped() {
        val s = snapshot(false, false, false, true)
        assertEquals(WizardStep.FINISH, s.step)
        assertFalse(s.running == true)
    }

    @Test fun unknownPrerequisitesNeverReceiveFalseTicks() {
        val s = snapshot(null, null, null)
        assertEquals(WizardStep.INSTALL, s.step)
        assertFalse(s.installed == true)
        assertEquals(WizardStep.DEBUGGING, snapshot(true, null, null).step)
        assertEquals(WizardStep.AUTHORIZE, snapshot(true, true, null).step)
    }

    @Test fun debuggingReaderReadsAllThreeRealKeys() {
        val keys = mutableListOf<String>()
        val s = DebuggingState.read(34) { keys += it; 0 }
        assertEquals(listOf("development_settings_enabled", "adb_enabled", "adb_wifi_enabled"), keys)
        assertTrue(s.verifiablyOff)
    }

    @Test fun developerOptionsOnAloneIsNotDebuggingOn() {
        assertTrue(DebuggingState(SettingState.ON, SettingState.OFF, SettingState.OFF).verifiablyOff)
    }

    @Test fun eitherDebuggingTransportOnPreventsOffClaim() {
        assertFalse(off.copy(usb = SettingState.ON).verifiablyOff)
        assertFalse(off.copy(wireless = SettingState.ON).verifiablyOff)
    }

    @Test fun missingSettingsAndInvalidValuesAreUnknown() {
        assertEquals(SettingState.UNKNOWN, DebuggingState.read(34) { null }.usb)
        assertEquals(SettingState.UNKNOWN, DebuggingState.read(34) { 9 }.wireless)
        assertFalse(DebuggingState.read(34) { null }.verifiablyOff)
    }

    @Test fun securityExceptionDoesNotInventOffState() {
        val s = DebuggingState.read(34) { throw SecurityException("private implementation detail") }
        assertEquals(SettingState.UNKNOWN, s.developer)
        assertEquals(SettingState.UNKNOWN, s.usb)
        assertEquals(SettingState.UNKNOWN, s.wireless)
        assertFalse(s.verifiablyOff)
    }

    @Test fun oneUnavailableKeyDoesNotHideOtherReadings() {
        val s = DebuggingState.read(34) { if (it == "adb_wifi_enabled") throw IllegalStateException() else 0 }
        assertEquals(SettingState.OFF, s.usb)
        assertEquals(SettingState.UNKNOWN, s.wireless)
        assertFalse(s.verifiablyOff)
    }

    @Test fun androidTenDoesNotReadUnsupportedWirelessKeyOrClaimOff() {
        val keys = mutableListOf<String>()
        val s = DebuggingState.read(29) { keys += it; 0 }
        assertFalse("adb_wifi_enabled" in keys)
        assertEquals(SettingState.UNKNOWN, s.wireless)
        assertFalse(s.verifiablyOff)
    }

    private val player = WorkingPlayer("example.player", "Example player", true, false)
    private fun view(active: Int? = 0, players: List<WorkingPlayer> = emptyList(), dump: Boolean = false) =
        WorkingState.derive(active, players, dump)

    @Test fun nothingPlayingNeverPromptsIncludingRetainedPausedSessions() {
        assertEquals(WorkingKind.IDLE, view().kind)
        assertNull(view().promptKey(emptySet()))
        assertNull(view(0, listOf(player.copy(active = false))).promptKey(emptySet()))
    }

    @Test fun anonymousPlaybackPromptsWithoutInventingAnAppName() {
        val s = view(1)
        assertEquals(WorkingKind.UNREACHABLE, s.kind)
        assertEquals("A player", s.players.single().name)
        assertEquals(WorkingState.ANONYMOUS_PLAYER, s.promptKey(emptySet()))
    }

    @Test fun namedUnreachablePlaybackHasPerAppPrompt() {
        assertEquals(player.key, view(1, listOf(player)).promptKey(emptySet()))
    }

    @Test fun attachmentInProgressDoesNotRecommendAnUnrelatedPermissionGrant() {
        assertNull(view(1, listOf(player.copy(attachable = true))).promptKey(emptySet()))
    }

    @Test fun bothRoutedEnginesSuppressPromptForTheirPlayer() {
        UiEngine.entries.forEach { engine ->
            val s = view(1, listOf(player.copy(attachable = true, engine = engine)))
            assertEquals(WorkingKind.ROUTED, s.kind)
            assertNull(s.promptKey(emptySet()))
        }
    }

    @Test fun permissionGrantedSuppressesSetupPromptButNotUnreachableStatus() {
        val s = view(1, listOf(player), true)
        assertEquals(WorkingKind.UNREACHABLE, s.kind)
        assertNull(s.promptKey(emptySet()))
    }

    @Test fun dismissalIsPerPlayerAndReversible() {
        val other = player.copy(key = "other", name = "Other")
        val s = view(2, listOf(player, other))
        assertEquals("other", s.promptKey(setOf(player.key)))
        assertNull(s.promptKey(setOf(player.key, other.key)))
        assertEquals(player.key, s.promptKey(emptySet()))
    }

    @Test fun anUnknownPlaybackReadIsNotSilenceOrASetupPrompt() {
        assertEquals(WorkingKind.UNKNOWN, view(null).kind)
        assertNull(view(null).promptKey(emptySet()))
    }

    @Test fun aRetainedBroadcastWithoutActiveEvidenceIsNotSuccess() {
        val route = player.copy(active = null, attachable = true, engine = UiEngine.SYSTEM_EFFECTS)
        assertEquals(WorkingKind.IDLE, view(0, listOf(route)).kind)
        assertEquals(WorkingKind.UNKNOWN, view(null, listOf(route)).kind)
        assertEquals(WorkingKind.ROUTED, view(1, listOf(route)).kind)
    }

    @Test fun explicitActivityCanWorkWhenPublicCountIsUnavailable() {
        assertEquals(WorkingKind.ROUTED, view(null, listOf(player.copy(attachable = true, engine = UiEngine.SYSTEM_EFFECTS))).kind)
    }

    @Test fun missingOrBypassedEffectIsReportedAsUnreachable() {
        val s = view(1, listOf(player.copy(attachable = true, reason = "Android is not applying the effect")))
        assertEquals(WorkingKind.UNREACHABLE, s.kind)
        assertEquals("Android is not applying the effect", s.players.single().reason)
        assertNull(s.promptKey(emptySet()))
    }

    @Test fun mixedPlayersDoNotHideAnUnreachablePlayerBehindOneWorkingRoute() {
        val routed = player.copy(key = "routed", attachable = true, engine = UiEngine.SYSTEM_EFFECTS)
        val s = view(2, listOf(routed, player))
        assertEquals(WorkingKind.UNREACHABLE, s.kind)
        assertEquals(player.key, s.promptKey(emptySet()))
    }

    @Test fun summaryContainsOnlyTheRequestedLocalFields() {
        val s = view(1, listOf(player.copy(name = "PRIVATE TITLE / ACCOUNT")))
        val summary = diagnosticSummary("0.5.5", 34, s.kind, 4, 1, 0, "Bluetooth")
        assertTrue(summary.contains("Android API: 34"))
        assertTrue(summary.contains("Scans: 4"))
        assertFalse(summary.contains("PRIVATE"))
        assertFalse(summary.contains(player.key))
    }

    @Test fun summaryReportsOnlyBoundedEngineTypes() {
        val summary = diagnosticSummary("0.5.5", 34, WorkingKind.ROUTED, 4, 1, 1,
            "Bluetooth", setOf(UiEngine.SYSTEM_EFFECTS, UiEngine.AUDIOPHILE), androidVersion = "14")
        assertTrue(summary.contains("Android version: 14"))
        assertTrue(summary.contains("Engine routes: System effects, Audiophile engine"))
        assertTrue(diagnosticSummary("0.5.5", 34, WorkingKind.IDLE, 0, 0, 0, "Unknown")
            .contains("Engine routes: None"))
    }

    @Test fun firstInstallDetectionNeverResetsExistingPreferencesOrUpdates() {
        assertTrue(FirstRunPolicy.needsFlatDefault(false, false, false))
        assertFalse(FirstRunPolicy.needsFlatDefault(true, false, false))
        assertFalse(FirstRunPolicy.needsFlatDefault(false, true, false))
        assertFalse(FirstRunPolicy.needsFlatDefault(false, false, true))
    }

    @Test fun existingFlatResetAndAudioDefaultsAreSafeWithoutProcessingChanges() {
        val eq = EqState()
        assertTrue(eq.enabled)
        assertEquals("Flat", eq.presetName)
        assertEquals(0.0, eq.preampDb, 0.0)
        assertTrue(eq.bands.all { it.gainDb == 0.0 })
        assertEquals(EngineMode.SYSTEM_ONLY, AudioSettings().engineMode)
    }

    @Test fun grantFailuresHavePlainRetryInstructionsWithoutRawExceptions() {
        listOf(false, true).forEach { pending ->
            val message = detectionGrantFailureMessage(pending)
            assertTrue(message.contains("retry"))
            assertFalse(message.contains("Exception"))
        }
        assertTrue(detectionGrantFailureMessage(true).contains("still answering"))
    }

    @Test fun compatibilityDataRequiresEvidenceAndCannotPromoteUnverifiedRows() {
        val list = CompatibilityEntry.parse("Player\tUnverified\tUnverified\tUnverified\tNot tested\n")
        assertEquals("Unverified", list.single().withoutSetup)
        assertEquals("Unverified", list.single().verification)
        assertTrue(CompatibilityEntry.parse("missing\tcolumns").isEmpty())
        assertTrue(CompatibilityEntry.parse("Player\tYes\tNo\tMade up\tGuess").isEmpty())
        val unverified = CompatibilityEntry.parse("Player\tYes\tYes\tUnverified\tNo measurement").single()
        assertEquals("Unverified", unverified.withoutSetup)
        assertEquals("Unverified", unverified.withDetection)
    }

    @Test fun batteryAdviceCoversRequestedSkinsWithConservativeWording() {
        listOf("TECNO", "Infinix", "Xiaomi", "Realme", "OPPO", "Samsung", "Other").forEach { maker ->
            val advice = batteryAdvice(maker)
            assertTrue(advice.isNotBlank())
            assertFalse(advice.contains("guarantee"))
            assertFalse(advice.contains("must"))
        }
        assertTrue(batteryAdvice("Infinix").contains("HiOS/XOS"))
        assertTrue(batteryAdvice("OPPO").contains("Realme"))
        assertTrue(batteryAdvice("Samsung").contains("sleeping"))
    }
}
