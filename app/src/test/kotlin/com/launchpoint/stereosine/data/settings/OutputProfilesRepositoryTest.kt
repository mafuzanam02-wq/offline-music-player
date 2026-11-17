package com.launchpoint.stereosine.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OutputProfilesRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private fun repositoryTest(block: suspend (AudioEnhancementsRepository, DataStore<Preferences>) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { temp.root.resolve("settings.preferences_pb") }
        try { block(AudioEnhancementsRepository(store), store) } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
    private suspend fun AudioEnhancementsRepository.snapshot() = profiles.first()
    private suspend fun AudioEnhancementsRepository.seedGlobal() {
        setEqCustomBandLevels(listOf(200, -100), frequenciesHz = listOf(100, 1000)); setEqEnabled(true)
    }

    @Test fun legacyGlobalIsUnchangedAndNothingSelectsACategory() = repositoryTest { repo, store ->
        store.edit { p ->
            p[booleanPreferencesKey("eq_enabled")] = true
            p[stringPreferencesKey("eq_preset_type")] = "CUSTOM"
            p[stringPreferencesKey("eq_custom_band_levels")] = "200,-100"
        }
        val before = store.data.first()
        val s = repo.snapshot()
        assertEquals(OutputProfile.GLOBAL, s.active); assertFalse(s.initialized)
        assertEquals(listOf(200, -100), s.effective.eqCustomBandLevels); assertTrue(s.effective.eqEnabled)
        assertEquals(before, store.data.first()); assertNull(repo.capabilities.value)
    }
    @Test fun firstExplicitSelectionSeedsAllCategoriesFromOneGlobalSnapshot() = repositoryTest { repo, _ ->
        repo.seedGlobal(); val before = repo.settings.first()
        repo.selectProfile(OutputProfile.WIRED)
        val s = repo.snapshot()
        assertEquals(OutputProfile.WIRED, s.active); assertTrue(s.initialized)
        OutputProfile.entries.forEach { assertEquals(before, s.configurations[it]) }
        assertEquals(before, repo.settings.first())
    }
    @Test fun globalSelectionAloneDoesNotInitializeCategories() = repositoryTest { repo, _ ->
        repo.selectProfile(OutputProfile.GLOBAL)
        assertFalse(repo.snapshot().initialized); assertEquals(setOf(OutputProfile.GLOBAL), repo.snapshot().configurations.keys)
    }
    @Test fun eachCategoryKeepsItsOwnConfigurationAndGlobalIsPreserved() = repositoryTest { repo, _ ->
        repo.seedGlobal(); val global = repo.settings.first()
        val categories = OutputProfile.entries.filter { it != OutputProfile.GLOBAL }
        categories.forEachIndexed { i, profile ->
            repo.selectProfile(profile); repo.setEqCustomBandLevels(listOf(i * 100, -i * 100), profile, listOf(100, 1000))
            repo.setEqEnabled(i != 1, profile)
        }
        categories.forEachIndexed { i, profile ->
            repo.selectProfile(profile)
            assertEquals(listOf(i * 100, -i * 100), repo.settings.first().eqCustomBandLevels)
            assertEquals(i != 1, repo.settings.first().eqEnabled)
        }
        repo.selectProfile(OutputProfile.GLOBAL); assertEquals(global, repo.settings.first())
    }
    @Test fun editingActivePresetRetainsItsCustomDataAndDoesNotTouchOthers() = repositoryTest { repo, _ ->
        repo.seedGlobal(); repo.selectProfile(OutputProfile.SPEAKER)
        val before = repo.snapshot()
        repo.setEqStereoSinePreset("deep-bass", OutputProfile.SPEAKER)
        val after = repo.snapshot()
        assertEquals("deep-bass", after.effective.eqStereoSinePresetId)
        assertEquals(before.effective.eqCustomBandLevels, after.effective.eqCustomBandLevels)
        assertEquals(before.effective.eqCustomBandFrequenciesHz, after.effective.eqCustomBandFrequenciesHz)
        listOf(OutputProfile.GLOBAL, OutputProfile.WIRED, OutputProfile.BLUETOOTH).forEach { assertEquals(before.configurations[it], after.configurations[it]) }
    }
    @Test fun repeatedSelectionDoesNotReseedOrChangeConfiguration() = repositoryTest { repo, store ->
        repo.selectProfile(OutputProfile.SPEAKER); repo.setEqEnabled(true)
        val raw = store.data.first(); repeat(5) { repo.selectProfile(OutputProfile.SPEAKER) }
        assertEquals(raw, store.data.first())
    }
    @Test fun selectionAndConfigurationsSurviveDiskRestart() = runBlocking {
        val file = temp.root.resolve("restart.preferences_pb")
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            var repo = AudioEnhancementsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
            repo.selectProfile(OutputProfile.BLUETOOTH); repo.setEqStereoSinePreset("warm"); repo.setEqEnabled(true)
            val saved = repo.snapshot(); scope.coroutineContext[Job]!!.cancelAndJoin()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            repo = AudioEnhancementsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
            assertEquals(saved, repo.snapshot()); assertNull(repo.capabilities.value)
            assertEquals(EqualizerApplicationStatus.DISABLED, repo.applicationState.value.status)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
    @Test fun staleEditorCannotWriteIntoNewlySelectedProfile() = repositoryTest { repo, _ ->
        repo.seedGlobal(); val global = repo.settings.first()
        repo.selectProfile(OutputProfile.SPEAKER)
        repo.setEqEnabled(false, OutputProfile.GLOBAL)
        assertEquals(global, repo.settings.first())
        repo.selectProfile(OutputProfile.WIRED)
        repo.setEqStereoSinePreset("warm", OutputProfile.SPEAKER)
        assertEquals(global, repo.settings.first())
        assertEquals(global, repo.snapshot().configurations[OutputProfile.SPEAKER])
    }
    @Test fun concurrentSelectionsAndEditsAreLinearizableAndNeverCrossWrite() = repositoryTest { repo, _ ->
        repo.selectProfile(OutputProfile.SPEAKER)
        coroutineScope {
            repeat(60) { n ->
                launch {
                    val profile = listOf(OutputProfile.SPEAKER, OutputProfile.WIRED, OutputProfile.BLUETOOTH)[n % 3]
                    repo.selectProfile(profile)
                    repo.setEqCustomBandLevels(listOf(profile.ordinal * 100), profile)
                }
            }
        }
        val state = repo.snapshot()
        assertFalse(state.configurations.getValue(OutputProfile.GLOBAL).eqEnabled)
        listOf(OutputProfile.SPEAKER, OutputProfile.WIRED, OutputProfile.BLUETOOTH).forEach {
            val levels = state.configurations.getValue(it).eqCustomBandLevels
            assertTrue(levels.isEmpty() || levels == listOf(it.ordinal * 100))
        }
    }
    @Test fun selectingAndSavingWithoutCapabilitiesDoesNotClaimApplication() = repositoryTest { repo, _ ->
        repo.selectProfile(OutputProfile.WIRED); repo.setEqEnabled(true); repo.setEqFlatPreset(); repo.setEqStereoSinePreset("balanced")
        assertNull(repo.capabilities.value); assertEquals("balanced", repo.settings.first().eqStereoSinePresetId)
        assertNotEquals(EqualizerApplicationStatus.APPLIED, repo.applicationState.value.status)
    }
    @Test fun runtimeFailureDoesNotChangeSelectionOrRequestedSettings() = repositoryTest { repo, _ ->
        repo.selectProfile(OutputProfile.BLUETOOTH); repo.setEqEnabled(true)
        val before = repo.snapshot()
        repo.updateApplicationState(EqualizerApplicationState(EqualizerApplicationStatus.UNAVAILABLE, issues = setOf(EqualizerApplicationIssue.CONTROL_UNAVAILABLE)))
        assertEquals(before, repo.snapshot()); assertTrue(repo.settings.first().eqEnabled)
    }
    @Test fun corruptSelectedProfileFallsBackVisiblyWithoutOverwritingRawData() = repositoryTest { repo, store ->
        repo.seedGlobal(); repo.selectProfile(OutputProfile.SPEAKER)
        store.edit { it[stringPreferencesKey("eq_profile_speaker_v1")] = "{corrupt" }
        val raw = store.data.first(); val s = repo.snapshot()
        assertEquals(OutputProfile.SPEAKER, s.active); assertTrue(s.usingGlobalFallback)
        assertEquals(s.configurations[OutputProfile.GLOBAL], repo.settings.first())
        repo.setEqEnabled(false, OutputProfile.SPEAKER)
        assertEquals(raw, store.data.first())
        repo.selectProfile(OutputProfile.GLOBAL); assertTrue(repo.settings.first().eqEnabled)
    }
    @Test fun missingProfileAndUnknownSelectionAreConservative() = repositoryTest { repo, store ->
        repo.selectProfile(OutputProfile.WIRED)
        store.edit { it.remove(stringPreferencesKey("eq_profile_wired_v1")) }
        assertTrue(repo.snapshot().usingGlobalFallback)
        store.edit { it[stringPreferencesKey("eq_active_output_profile")] = "FUTURE_DEVICE" }
        val raw = store.data.first()
        assertEquals(OutputProfile.GLOBAL, repo.snapshot().active); assertTrue(repo.snapshot().invalidSelection)
        assertEquals(raw, store.data.first())
    }
    @Test fun knownCustomFrequenciesMustMatchAndIncompatibleDataStaysSaved() = repositoryTest { repo, _ ->
        repo.seedGlobal(); repo.selectProfile(OutputProfile.SPEAKER)
        val caps = EqualizerCapabilities(2, -500, 500, listOf(200, 2000), emptyList())
        assertFalse(customEqMatchesCapabilities(repo.settings.first(), caps))
        repo.setEqCustomPreset(2, OutputProfile.SPEAKER, caps.centerFrequenciesHz)
        assertEquals(listOf(100, 1000), repo.settings.first().eqCustomBandFrequenciesHz)
        assertEquals(listOf(200, -100), repo.settings.first().eqCustomBandLevels)
        assertTrue(customEqMatchesCapabilities(repo.settings.first().copy(eqCustomBandFrequenciesHz = emptyList()), caps))
    }
    @Test fun invalidVersionTypeOrFrequencyMetadataIsRejectedWithoutCoercion() {
        val valid = OutputProfileCodec.encode(AudioEnhancementSettings(eqCustomBandLevels = listOf(100), eqCustomBandFrequenciesHz = listOf(1000)))
        assertNotNull(OutputProfileCodec.decode(valid))
        assertNull(OutputProfileCodec.decode(valid.replace("\"version\":1", "\"version\":2")))
        assertNull(OutputProfileCodec.decode(valid.replace("\"enabled\":false", "\"enabled\":\"false\"")))
        assertNull(OutputProfileCodec.decode(valid.replace("[1000]", "[0]")))
    }
    private val recoveryCaps = EqualizerCapabilities(2, -500, 500, listOf(200, 2000), emptyList())

    @Test fun confirmedRecoveryCreatesFlatActualBandsOnlyInSelectedCategory() = repositoryTest { repo, _ ->
        repo.seedGlobal(); repo.selectProfile(OutputProfile.SPEAKER)
        repo.updateCapabilities(recoveryCaps)
        val before = repo.snapshot()
        assertTrue(customEqRecoveryAvailable(before.effective, recoveryCaps))
        repo.resetCustomBandsForDevice(OutputProfile.SPEAKER, recoveryCaps)
        val after = repo.snapshot()
        assertEquals(listOf(0, 0), after.effective.eqCustomBandLevels)
        assertEquals(recoveryCaps.centerFrequenciesHz, after.effective.eqCustomBandFrequenciesHz)
        assertEquals(EqPresetType.CUSTOM, after.effective.eqPresetType)
        assertEquals(before.effective.eqEnabled, after.effective.eqEnabled)
        assertTrue(customEqMatchesCapabilities(after.effective, recoveryCaps))
        listOf(OutputProfile.GLOBAL, OutputProfile.WIRED, OutputProfile.BLUETOOTH).forEach { assertEquals(before.configurations[it], after.configurations[it]) }
        assertEquals(EqualizerApplicationStatus.DISABLED, repo.applicationState.value.status) // saving never fabricates runtime confirmation
    }
    @Test fun staleRecoveryCannotResetTheNewProfileOrTheOldOne() = repositoryTest { repo, store ->
        repo.seedGlobal(); repo.selectProfile(OutputProfile.SPEAKER); repo.updateCapabilities(recoveryCaps)
        repo.selectProfile(OutputProfile.WIRED)
        val before = store.data.first()
        repo.resetCustomBandsForDevice(OutputProfile.SPEAKER, recoveryCaps)
        assertEquals(before, store.data.first()); assertEquals(OutputProfile.WIRED, repo.snapshot().active)
    }
    @Test fun unavailableOrChangedCapabilitiesRejectConfirmation() = repositoryTest { repo, store ->
        repo.seedGlobal(); repo.selectProfile(OutputProfile.SPEAKER)
        val before = store.data.first()
        repo.resetCustomBandsForDevice(OutputProfile.SPEAKER, recoveryCaps)
        assertEquals(before, store.data.first())
        repo.updateCapabilities(recoveryCaps.copy(centerFrequenciesHz = listOf(300, 3000)))
        repo.resetCustomBandsForDevice(OutputProfile.SPEAKER, recoveryCaps)
        assertEquals(before, store.data.first())
        repo.updateCapabilities(null)
        repo.resetCustomBandsForDevice(OutputProfile.SPEAKER, recoveryCaps)
        assertEquals(before, store.data.first())
    }
    @Test fun compatibleCurveAndNonCustomPresetDoNotOfferOrPerformRecovery() = repositoryTest { repo, store ->
        repo.seedGlobal(); repo.selectProfile(OutputProfile.SPEAKER)
        val compatible = recoveryCaps.copy(centerFrequenciesHz = listOf(100, 1000))
        repo.updateCapabilities(compatible)
        assertFalse(customEqRecoveryAvailable(repo.settings.first(), compatible))
        val before = store.data.first()
        repo.resetCustomBandsForDevice(OutputProfile.SPEAKER, compatible)
        assertEquals(before, store.data.first())
        repo.setEqFlatPreset(); assertFalse(customEqRecoveryAvailable(repo.settings.first(), recoveryCaps))
    }
    @Test fun incompleteOrNonFlatCapableMetadataCannotAuthorizeReset() = repositoryTest { repo, store ->
        repo.seedGlobal(); repo.selectProfile(OutputProfile.SPEAKER)
        val before = store.data.first()
        for (invalid in listOf(
            recoveryCaps.copy(centerFrequenciesHz = listOf(0, 2000)),
            recoveryCaps.copy(centerFrequenciesHz = listOf(200)),
            recoveryCaps.copy(bandCount = 0),
            recoveryCaps.copy(minLevelMb = 100),
        )) {
            repo.updateCapabilities(invalid)
            assertFalse(customEqRecoveryAvailable(repo.settings.first(), invalid))
            repo.resetCustomBandsForDevice(OutputProfile.SPEAKER, invalid)
            assertEquals(before, store.data.first())
        }
    }
    @Test fun discoverySwitchingAndSelectingCustomNeverAutomaticallyReset() = repositoryTest { repo, _ ->
        repo.seedGlobal(); repo.selectProfile(OutputProfile.SPEAKER)
        val curve = repo.settings.first()
        repo.updateCapabilities(recoveryCaps)
        assertEquals(curve, repo.settings.first())
        repo.setEqCustomPreset(2, OutputProfile.SPEAKER, recoveryCaps.centerFrequenciesHz)
        assertEquals(curve, repo.settings.first())
        repo.selectProfile(OutputProfile.BLUETOOTH); repo.selectProfile(OutputProfile.SPEAKER)
        assertEquals(curve, repo.settings.first())
    }
    @Test fun explicitGlobalRecoveryLeavesEveryCategoryUntouched() = repositoryTest { repo, _ ->
        repo.seedGlobal(); repo.selectProfile(OutputProfile.SPEAKER); repo.selectProfile(OutputProfile.GLOBAL)
        repo.updateCapabilities(recoveryCaps)
        val before = repo.snapshot()
        repo.resetCustomBandsForDevice(OutputProfile.GLOBAL, recoveryCaps)
        val after = repo.snapshot()
        assertEquals(listOf(0, 0), after.effective.eqCustomBandLevels)
        listOf(OutputProfile.SPEAKER, OutputProfile.WIRED, OutputProfile.BLUETOOTH).forEach { assertEquals(before.configurations[it], after.configurations[it]) }
    }

}
