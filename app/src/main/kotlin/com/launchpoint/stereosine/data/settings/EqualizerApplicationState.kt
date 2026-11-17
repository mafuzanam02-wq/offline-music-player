package com.launchpoint.stereosine.data.settings

enum class EqualizerApplicationStatus { DISABLED, PENDING, APPLIED, FAILED, UNAVAILABLE }

enum class EqualizerApplicationIssue {
    WAITING_FOR_SESSION, RELEASED, UNSUPPORTED, CREATION_FAILED, CAPABILITIES_FAILED,
    CONTROL_UNAVAILABLE, EXTERNALLY_DISABLED, WRITE_FAILED, ENABLE_FAILED, READBACK_FAILED,
    READBACK_MISMATCH, FLAT_FALLBACK, CLAMPED,
}

/** Transient API observations, never a measurement or guarantee of audible processing. */
data class EqualizerApplicationState(
    val status: EqualizerApplicationStatus = EqualizerApplicationStatus.DISABLED,
    val sessionId: Int? = null,
    val requested: AudioEnhancementSettings? = null,
    val issues: Set<EqualizerApplicationIssue> = emptySet(),
    /** Present only after all band reads succeed. These are API values in millibels. */
    val reportedBandLevels: List<Int>? = null,
    val reportedPlatformPresetIndex: Int? = null,
) {
    val requestedConfigurationConfirmed: Boolean
        get() = status == EqualizerApplicationStatus.APPLIED && issues.isEmpty()
}

/** Presentation never attributes an old request's API confirmation to a newer saved configuration. */
fun EqualizerApplicationState.forRequest(settings: AudioEnhancementSettings): EqualizerApplicationState =
    if (requested == settings) this else EqualizerApplicationState(
        if (!settings.eqEnabled && status == EqualizerApplicationStatus.DISABLED)
            EqualizerApplicationStatus.DISABLED else EqualizerApplicationStatus.PENDING,
        requested = settings,
    )
