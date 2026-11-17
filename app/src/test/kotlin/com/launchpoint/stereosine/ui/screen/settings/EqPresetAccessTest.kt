package com.launchpoint.stereosine.ui.screen.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.launchpoint.stereosine.data.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EqPresetAccessTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun disabledIsNotDescribedAsWaitingForASession() {
        assertEquals("Equalizer is disabled.", equalizerApplicationSummary(EqualizerApplicationState()))
        assertEquals("Equalizer application pending.", equalizerApplicationSummary(EqualizerApplicationState(), requestedEnabled = true))
    }

    @Test fun unavailableAndFailedMessagesDoNotBecomeAppliedWhenARequestIsEnabled() {
        for (status in listOf(EqualizerApplicationStatus.PENDING, EqualizerApplicationStatus.FAILED, EqualizerApplicationStatus.UNAVAILABLE)) {
            val state = EqualizerApplicationState(status)
            assertEquals(equalizerApplicationSummary(state), equalizerApplicationSummary(state, requestedEnabled = true))
            assertFalse(equalizerApplicationSummary(state, requestedEnabled = true).contains("reports EQ enabled"))
        }
    }

    @Test fun deviceIndependentSelectionsPersistWithoutCapabilitiesAndRetainCustomData() = runBlocking {
        val file = temporary.root.resolve("eq.preferences_pb")
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            var repo = AudioEnhancementsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
            val custom = listOf(-300, 0, 200, 100, -100)
            repo.setEqCustomBandLevels(custom)
            repo.setEqEnabled(true)
            repo.setEqFlatPreset()
            assertEquals(EqPresetType.FLAT, repo.settings.first().eqPresetType)
            assertEquals(custom, repo.settings.first().eqCustomBandLevels)
            repo.setEqStereoSinePreset("deep-bass")
            val saved = repo.settings.first()
            assertNull(repo.capabilities.value)
            assertNotEquals(EqualizerApplicationStatus.APPLIED, repo.applicationState.value.status)
            scope.coroutineContext[Job]!!.cancelAndJoin()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            repo = AudioEnhancementsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
            assertEquals(saved, repo.settings.first())
            assertEquals(custom, repo.settings.first().eqCustomBandLevels)
            assertNull(repo.capabilities.value)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
}
