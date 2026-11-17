package com.launchpoint.stereosine.playback

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.launchpoint.stereosine.data.settings.*
import com.launchpoint.stereosine.data.settings.EqualizerApplicationIssue.*
import com.launchpoint.stereosine.data.settings.EqualizerApplicationStatus.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioEnhancementControllerTest {
    private fun withControllerScope(block: suspend (CoroutineScope) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try { block(scope) } finally { scope.cancel() }
    }
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
    private class Effect : EqualizerEffect {
        override var numberOfBands = 2
        override var bandLevelRange = shortArrayOf(-500, 500)
        override val numberOfPresets = 3
        override var currentPreset = 0
        override var enabled = false
        var control = true
        var enableResult = 0
        var enableThrows = false
        var enableReadsFalse = false
        var badName: Int? = null
        var badWrite: Int? = null
        var badRead = false
        var mismatch = false
        var presetThrows = false
        var releases = 0
        var writes = 0
        var enableCalls = 0
        val levels = mutableListOf(0, 0)
        var controlCallback: (Boolean) -> Unit = {}
        var enableCallback: (Boolean) -> Unit = {}
        override fun hasControl() = control
        override fun getPresetName(index: Short): String {
            if (index.toInt() == badName) throw IllegalStateException()
            return "Preset $index"
        }
        override fun getCenterFreq(band: Short) = (band + 1) * 100_000
        override fun setBandLevel(band: Short, level: Short) {
            writes++
            if (band.toInt() == badWrite) throw IllegalStateException()
            levels[band.toInt()] = level.toInt()
        }
        override fun getBandLevel(band: Short): Int {
            if (badRead) throw IllegalStateException()
            return levels[band.toInt()] + if (mismatch) 1 else 0
        }
        override fun usePreset(index: Short) {
            if (presetThrows) throw IllegalStateException()
            currentPreset = index.toInt()
            levels[0] = 100; levels[1] = -100
        }
        override fun setEnabled(enabled: Boolean): Int {
            enableCalls++
            if (enableThrows) throw IllegalStateException()
            this.enabled = enabled && !enableReadsFalse
            return enableResult
        }
        override fun setControlListener(listener: (Boolean) -> Unit) { controlCallback = listener }
        override fun setEnableListener(listener: (Boolean) -> Unit) { enableCallback = listener }
        override fun release() { releases++ }
    }
    private class Fixture(scope: CoroutineScope, factory: ((Int) -> EqualizerEffect)? = null) {
        val repo = AudioEnhancementsRepository(MemoryStore())
        val effects = mutableListOf<Effect>()
        val controller = AudioEnhancementController(repo, scope, factory ?: { Effect().also(effects::add) })
        val state get() = repo.applicationState.value
        init { controller.attachSession(7) }
    }

    @Test fun disabledDoesNotCreateAnEffect() = withControllerScope { controllerScope ->
        val f = Fixture(controllerScope)
        assertEquals(DISABLED, f.state.status); assertTrue(f.effects.isEmpty()); assertNull(f.repo.capabilities.value)
    }
    @Test fun successfulApplicationConfirmsOnlyApiReadback() = withControllerScope { controllerScope ->
        val f = Fixture(controllerScope); f.repo.setEqEnabled(true)
        assertEquals(APPLIED, f.state.status); assertTrue(f.state.requestedConfigurationConfirmed)
        assertEquals(listOf(0, 0), f.state.reportedBandLevels); assertNotNull(f.repo.capabilities.value)
    }
    @Test fun unsupportedConstructorDoesNotEscapeOrEraseIntent() = withControllerScope { controllerScope ->
        val f = Fixture(controllerScope) { throw UnsupportedOperationException() }
        f.repo.setEqEnabled(true)
        assertEquals(UNAVAILABLE, f.state.status); assertEquals(setOf(UNSUPPORTED), f.state.issues)
        assertTrue(f.repo.settings.first().eqEnabled); assertNull(f.repo.capabilities.value)
    }
    @Test fun constructorFailureIsFailedRatherThanApplied() = withControllerScope { controllerScope ->
        val f = Fixture(controllerScope) { throw IllegalStateException() }
        f.repo.setEqEnabled(true); assertEquals(setOf(CREATION_FAILED), f.state.issues)
        assertEquals(FAILED, f.state.status)
    }
    @Test fun invalidCapabilitiesReleaseAndInvalidate() = withControllerScope { controllerScope ->
        val eq = Effect().apply { bandLevelRange = shortArrayOf(500, -500) }
        val f = Fixture(controllerScope) { eq }; f.repo.setEqEnabled(true)
        assertEquals(UNAVAILABLE, f.state.status); assertEquals(1, eq.releases); assertNull(f.repo.capabilities.value)
    }
    @Test fun enableReturnAndReadbackMustBothSucceed() = withControllerScope { controllerScope ->
        for (eq in listOf(Effect().apply { enableResult = -1 }, Effect().apply { enableReadsFalse = true })) {
            val f = Fixture(controllerScope) { eq }; f.repo.setEqEnabled(true)
            assertEquals(FAILED, f.state.status); assertTrue(ENABLE_FAILED in f.state.issues)
        }
    }
    @Test fun enableExceptionReleasesCapabilities() = withControllerScope { controllerScope ->
        val eq = Effect().apply { enableThrows = true }
        val f = Fixture(controllerScope) { eq }; f.repo.setEqEnabled(true)
        assertEquals(FAILED, f.state.status); assertEquals(1, eq.releases); assertNull(f.repo.capabilities.value)
    }
    @Test fun partialWritesStillEnableAsBeforeButNeverClaimSuccess() = withControllerScope { controllerScope ->
        val eq = Effect().apply { badWrite = 1 }
        val f = Fixture(controllerScope) { eq }; f.repo.setEqCustomBandLevels(listOf(200, 300)); f.repo.setEqEnabled(true)
        assertEquals(2, eq.writes); assertEquals(1, eq.enableCalls)
        assertEquals(FAILED, f.state.status); assertTrue(WRITE_FAILED in f.state.issues)
    }
    @Test fun readbackFailureOrMismatchCannotBeApplied() = withControllerScope { controllerScope ->
        for (eq in listOf(Effect().apply { badRead = true }, Effect().apply { mismatch = true })) {
            val f = Fixture(controllerScope) { eq }; f.repo.setEqEnabled(true)
            assertEquals(FAILED, f.state.status); assertFalse(f.state.requestedConfigurationConfirmed)
        }
    }
    @Test fun missingPresetNamePreservesLaterOriginalIndex() = withControllerScope { controllerScope ->
        val eq = Effect().apply { badName = 1 }
        val f = Fixture(controllerScope) { eq }; f.repo.setEqPlatformPreset(2); f.repo.setEqEnabled(true)
        assertEquals(listOf(0, 2), f.repo.capabilities.value!!.platformPresets.map { it.index.toInt() })
        assertEquals(2, eq.currentPreset); assertEquals(2, f.state.reportedPlatformPresetIndex)
        assertTrue(f.state.requestedConfigurationConfirmed)
    }
    @Test fun validIndexWithMissingNameStillAppliesWithoutChangingSelection() = withControllerScope { controllerScope ->
        val eq = Effect().apply { badName = 1 }
        val f = Fixture(controllerScope) { eq }; f.repo.setEqPlatformPreset(1); f.repo.setEqEnabled(true)
        assertTrue(f.state.requestedConfigurationConfirmed); assertEquals(1, eq.currentPreset)
        assertEquals(3, f.repo.capabilities.value!!.platformPresetCount)
    }
    @Test fun unavailableSelectionFallsBackWithoutRewritingPreference() = withControllerScope { controllerScope ->
        val eq = Effect().apply { badName = 1 }
        val f = Fixture(controllerScope) { eq }; f.repo.setEqPlatformPreset(5); f.repo.setEqEnabled(true)
        assertEquals(APPLIED, f.state.status); assertTrue(FLAT_FALLBACK in f.state.issues)
        assertFalse(f.state.requestedConfigurationConfirmed); assertEquals(5, f.repo.settings.first().eqPlatformPresetIndex)
    }
    @Test fun presetWriteFailureRetainsFlatFallbackButReportsFailure() = withControllerScope { controllerScope ->
        val eq = Effect().apply { presetThrows = true }
        val f = Fixture(controllerScope) { eq }; f.repo.setEqPlatformPreset(2); f.repo.setEqEnabled(true)
        assertEquals(FAILED, f.state.status); assertTrue(FLAT_FALLBACK in f.state.issues); assertEquals(listOf(0, 0), eq.levels)
    }
    @Test fun clampingAndBandMismatchAreExplicitAndDoNotOverwriteSavedLevels() = withControllerScope { controllerScope ->
        val f = Fixture(controllerScope); f.repo.setEqCustomBandLevels(listOf(900, -900)); f.repo.setEqEnabled(true)
        assertEquals(listOf(500, -500), f.state.reportedBandLevels); assertEquals(setOf(CLAMPED), f.state.issues)
        assertEquals(listOf(900, -900), f.repo.settings.first().eqCustomBandLevels)
        f.repo.setEqCustomBandLevels(listOf(200))
        assertTrue(FLAT_FALLBACK in f.state.issues); assertEquals(listOf(200), f.repo.settings.first().eqCustomBandLevels)
    }
    @Test fun missingControlReportsUnavailableAndRegainReappliesOnce() = withControllerScope { controllerScope ->
        val eq = Effect().apply { control = false }
        val f = Fixture(controllerScope) { eq }; f.repo.setEqEnabled(true)
        assertEquals(UNAVAILABLE, f.state.status); assertEquals(0, eq.writes)
        eq.control = true; eq.controlCallback(true)
        assertEquals(APPLIED, f.state.status)
        eq.control = false; eq.controlCallback(false)
        assertEquals(setOf(CONTROL_UNAVAILABLE), f.state.issues)
    }
    @Test fun externalDisableReportsUnavailableWithoutChangingUserIntent() = withControllerScope { controllerScope ->
        val f = Fixture(controllerScope); f.repo.setEqEnabled(true)
        val eq = f.effects.single(); eq.enabled = false; eq.enableCallback(false)
        assertEquals(setOf(EXTERNALLY_DISABLED), f.state.issues); assertTrue(f.repo.settings.first().eqEnabled)
    }
    @Test fun repeatedIdenticalSettingsAndAttachAreNoOps() = withControllerScope { controllerScope ->
        val f = Fixture(controllerScope); f.repo.setEqEnabled(true)
        val writes = f.effects.single().writes
        repeat(4) { f.repo.setEqEnabled(true); f.controller.attachSession(7); f.controller.onAudioSessionIdChanged(7) }
        assertEquals(1, f.effects.size); assertEquals(writes, f.effects.single().writes)
    }
    @Test fun sessionRecreationInvalidatesOldCapabilitiesAndIgnoresOldCallbacks() = withControllerScope { controllerScope ->
        val f = Fixture(controllerScope); f.repo.setEqEnabled(true)
        val old = f.effects.single(); f.controller.onAudioSessionIdChanged(0)
        assertEquals(PENDING, f.state.status); assertNull(f.repo.capabilities.value); assertEquals(1, old.releases)
        f.controller.onAudioSessionIdChanged(8); assertEquals(APPLIED, f.state.status)
        old.controlCallback(false); old.enableCallback(false)
        assertEquals(APPLIED, f.state.status); assertEquals(8, f.state.sessionId); assertEquals(2, f.effects.size)
    }
    @Test fun disableAndReleaseAreIdempotentAndClearCapabilities() = withControllerScope { controllerScope ->
        val f = Fixture(controllerScope); f.repo.setEqEnabled(true)
        val old = f.effects.single(); f.repo.setEqEnabled(false)
        assertEquals(DISABLED, f.state.status); assertNull(f.repo.capabilities.value)
        f.controller.release(); f.controller.release(); f.controller.onAudioSessionIdChanged(8)
        old.controlCallback(true); assertEquals(1, old.releases); assertNull(f.repo.capabilities.value)
    }
    @Test fun releaseWhileEnabledCannotRetainAppliedStateOrCapabilities() = withControllerScope { controllerScope ->
        val f = Fixture(controllerScope); f.repo.setEqEnabled(true)
        val old = f.effects.single(); f.controller.release()
        assertEquals(UNAVAILABLE, f.state.status); assertEquals(setOf(RELEASED), f.state.issues)
        assertNull(f.repo.capabilities.value)
        old.controlCallback(true); old.enableCallback(false)
        assertEquals(setOf(RELEASED), f.state.issues); assertEquals(1, old.releases)
        assertTrue(f.repo.settings.first().eqEnabled)
    }
    @Test fun duplicateControlGrantDoesNotRewriteConfirmedSettings() = withControllerScope { controllerScope ->
        val f = Fixture(controllerScope); f.repo.setEqEnabled(true)
        val old = f.effects.single(); val writes = old.writes
        old.controlCallback(true); old.controlCallback(true)
        assertEquals(writes, old.writes); assertEquals(APPLIED, f.state.status)
    }
    @Test fun unavailableEffectStillBlocksCrossfadeByRequestedIntent() = withControllerScope { controllerScope ->
        val f = Fixture(controllerScope) { throw UnsupportedOperationException() }
        f.repo.setEqEnabled(true)
        assertTrue(crossfadeEqualizerBlocks(true, f.repo.settings.first().eqEnabled))
        assertTrue(crossfadeEqualizerBlocks(false, false))
        assertTrue(shouldCancelCrossfadeForEqualizerChange(false, true))
        assertFalse(shouldCancelCrossfadeForEqualizerChange(true, false))
    }
    @Test fun activeProfilesApplyThroughTheSameControllerAndSurviveRecreation() = withControllerScope { scope ->
        val f = Fixture(scope)
        f.repo.selectProfile(OutputProfile.SPEAKER)
        f.repo.setEqCustomBandLevels(listOf(200, -100), OutputProfile.SPEAKER, listOf(100, 200))
        f.repo.setEqEnabled(true)
        assertEquals(APPLIED, f.state.status)
        f.repo.selectProfile(OutputProfile.WIRED) // seeded disabled Global, not Speaker's later edit
        assertEquals(DISABLED, f.state.status); assertNull(f.repo.capabilities.value)
        f.repo.selectProfile(OutputProfile.SPEAKER)
        assertEquals(listOf(200, -100), f.state.reportedBandLevels)
        val requested = f.repo.settings.first()
        f.controller.release()
        val recreated = AudioEnhancementController(f.repo, scope) { Effect() }
        try {
            recreated.attachSession(11)
            assertEquals(APPLIED, f.state.status); assertEquals(requested, f.state.requested)
            assertEquals(OutputProfile.SPEAKER, f.repo.profiles.first().active)
        } finally { recreated.release() }
    }
    @Test fun sameBandCountWithKnownDifferentFrequenciesFallsBackWithoutErasingCurve() = withControllerScope { scope ->
        val f = Fixture(scope)
        f.repo.selectProfile(OutputProfile.BLUETOOTH)
        f.repo.setEqCustomBandLevels(listOf(200, -100), OutputProfile.BLUETOOTH, listOf(200, 2000))
        f.repo.setEqEnabled(true)
        assertEquals(APPLIED, f.state.status); assertTrue(FLAT_FALLBACK in f.state.issues)
        assertEquals(listOf(0, 0), f.state.reportedBandLevels)
        assertEquals(listOf(200, -100), f.repo.settings.first().eqCustomBandLevels)
        assertEquals(listOf(200, 2000), f.repo.settings.first().eqCustomBandFrequenciesHz)
    }
    @Test fun serviceOwnedObservationNeverRunsAnIndependentSettingsCollector() = withControllerScope { scope ->
        val repo = AudioEnhancementsRepository(MemoryStore())
        val controller = AudioEnhancementController(repo, scope) { Effect() }
        controller.attachSession(7, observeSettings = false)
        try {
            repo.selectProfile(OutputProfile.WIRED); repo.setEqEnabled(true)
            assertEquals(DISABLED, repo.applicationState.value.status) // only the service may deliver the request
            var eligibility = false
            applyEffectiveEqualizer(repo.settings.first(), false,
                updateEligibility = { eligibility = it }, cancelCrossfade = {},
                applyEffect = { assertTrue(eligibility); controller.onSettingsChanged(it) },
            )
            assertEquals(APPLIED, repo.applicationState.value.status)
        } finally { controller.release() }
    }

    @Test fun explicitRecoveryReplacesFallbackWithConfirmedCustomReadback() = withControllerScope { scope ->
        val f = Fixture(scope)
        f.repo.selectProfile(OutputProfile.SPEAKER)
        f.repo.setEqCustomBandLevels(listOf(200, -100), OutputProfile.SPEAKER, listOf(200, 2000))
        f.repo.setEqEnabled(true)
        assertTrue(FLAT_FALLBACK in f.state.issues)
        f.repo.resetCustomBandsForDevice(OutputProfile.SPEAKER, f.repo.capabilities.value!!)
        assertEquals(APPLIED, f.state.status)
        assertTrue(f.state.requestedConfigurationConfirmed)
        assertEquals(listOf(0, 0), f.state.reportedBandLevels)
        assertEquals(listOf(100, 200), f.state.requested!!.eqCustomBandFrequenciesHz)
        assertTrue(f.state.requested!!.eqEnabled)
    }

    @Test fun headphoneLibraryDoesNotReapplyEffectsAndCopiedCurvesSurviveControllerRecreation() = withControllerScope { scope ->
        val f = Fixture(scope)
        f.repo.selectProfile(OutputProfile.WIRED)
        f.repo.setEqCustomBandLevels(listOf(200, -100), OutputProfile.WIRED, listOf(100, 200)); f.repo.setEqEnabled(true)
        val caps = f.repo.capabilities.value!!
        val writes = f.effects.single().writes
        assertEquals(HeadphonePresetResult.SAVED, f.repo.saveHeadphonePreset(OutputProfile.WIRED, f.repo.settings.first(), caps, "Headphones"))
        val saved = f.repo.headphonePresets.first().presets.single()
        assertEquals(HeadphonePresetResult.RENAMED, f.repo.renameHeadphonePreset(OutputProfile.WIRED, saved, "Renamed"))
        val renamed = f.repo.headphonePresets.first().presets.single()
        assertEquals(writes, f.effects.single().writes)
        assertEquals(HeadphonePresetResult.UNCHANGED, f.repo.applyHeadphonePreset(OutputProfile.WIRED, renamed, caps))
        assertEquals(writes, f.effects.single().writes)
        f.repo.setEqCustomBandLevels(listOf(0, 0), OutputProfile.WIRED, caps.centerFrequenciesHz)
        assertEquals(HeadphonePresetResult.COPIED_TO_PROFILE, f.repo.applyHeadphonePreset(OutputProfile.WIRED, renamed, caps))
        assertEquals(listOf(200, -100), f.state.reportedBandLevels)
        assertTrue(crossfadeEqualizerBlocks(true, f.repo.settings.first().eqEnabled))
        val request = f.repo.settings.first(); val afterApply = f.effects.single().writes
        assertEquals(HeadphonePresetResult.DELETED, f.repo.deleteHeadphonePreset(OutputProfile.WIRED, renamed))
        assertEquals(afterApply, f.effects.single().writes); assertEquals(request, f.repo.settings.first())
        f.controller.release()
        val recreated = AudioEnhancementController(f.repo, scope) { Effect() }
        try {
            recreated.attachSession(17)
            assertEquals(request, f.state.requested); assertTrue(f.state.requestedConfigurationConfirmed)
        } finally { recreated.release() }
    }

}
