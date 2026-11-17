package com.launchpoint.stereosine.playback

import com.launchpoint.stereosine.data.settings.AudioEnhancementSettings

/** Main-thread, non-suspending transaction shared by production service wiring and order tests. */
internal fun applyEffectiveEqualizer(
    settings: AudioEnhancementSettings,
    previousEnabled: Boolean,
    updateEligibility: (Boolean) -> Unit,
    cancelCrossfade: () -> Unit,
    applyEffect: (AudioEnhancementSettings) -> Unit,
) {
    updateEligibility(settings.eqEnabled)
    if (shouldCancelCrossfadeForEqualizerChange(previousEnabled, settings.eqEnabled)) cancelCrossfade()
    applyEffect(settings)
}
