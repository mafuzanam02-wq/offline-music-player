package com.launchpoint.stereosine.playback

import com.launchpoint.stereosine.data.settings.*
import org.junit.Assert.*
import org.junit.Test

class EffectiveEqualizerTest {
    @Test fun eligibilityAndCancellationHappenBeforeApplyingTheExactSnapshot() {
        val settings = AudioEnhancementSettings(eqEnabled = true, eqPresetType = EqPresetType.WAVDROP_PRESET, eqStereoSinePresetId = "warm")
        val events = mutableListOf<String>()
        var blocked = false
        applyEffectiveEqualizer(settings, false,
            updateEligibility = { blocked = crossfadeEqualizerBlocks(true, it); events += "eligibility" },
            cancelCrossfade = { assertTrue(blocked); events += "cancel" },
            applyEffect = { assertSame(settings, it); assertTrue(blocked); events += "effect" },
        )
        assertEquals(listOf("eligibility", "cancel", "effect"), events)
    }
    @Test fun disablingAndRepeatedEnabledRequestsDoNotInventCancellationOrGainChanges() {
        for ((previous, enabled) in listOf(true to true, true to false, false to false)) {
            var cancellations = 0
            var blocked = !enabled
            applyEffectiveEqualizer(AudioEnhancementSettings(eqEnabled = enabled), previous,
                updateEligibility = { blocked = crossfadeEqualizerBlocks(true, it) },
                cancelCrossfade = { cancellations++ },
                applyEffect = { assertEquals(enabled, blocked) },
            )
            assertEquals(0, cancellations)
        }
    }
    @Test fun oldConfirmationIsNotAttributedToANewProfileConfiguration() {
        val old = AudioEnhancementSettings(eqEnabled = true)
        val state = EqualizerApplicationState(EqualizerApplicationStatus.APPLIED, requested = old)
        val new = old.copy(eqPresetType = EqPresetType.WAVDROP_PRESET, eqStereoSinePresetId = "warm")
        assertEquals(EqualizerApplicationStatus.PENDING, state.forRequest(new).status)
        assertEquals(EqualizerApplicationStatus.PENDING, state.forRequest(old.copy(eqEnabled = false)).status)
        assertEquals(EqualizerApplicationStatus.DISABLED, EqualizerApplicationState().forRequest(old.copy(eqEnabled = false)).status)
        assertSame(state, state.forRequest(old))
    }
}
