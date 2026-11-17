package com.launchpoint.stereosine.data.settings

data class AudioEnhancementSettings(
    val eqEnabled: Boolean = false,
    val eqPresetType: EqPresetType = EqPresetType.FLAT,
    val eqPlatformPresetIndex: Int? = null,
    val eqCustomBandLevels: List<Int> = emptyList(),
    val eqStereoSinePresetId: String? = null,
    /** Empty for legacy/unknown origin; new custom edits retain their actual runtime frequencies. */
    val eqCustomBandFrequenciesHz: List<Int> = emptyList(),
)

enum class EqPresetType { FLAT, WAVDROP_PRESET, PLATFORM, CUSTOM }

/** Known frequency metadata must match; legacy curves with unknown origin retain their prior count-based behavior. */
internal fun customEqMatchesCapabilities(settings: AudioEnhancementSettings, caps: EqualizerCapabilities): Boolean =
    settings.eqCustomBandLevels.size == caps.bandCount &&
        (settings.eqCustomBandFrequenciesHz.isEmpty() || settings.eqCustomBandFrequenciesHz == caps.centerFrequenciesHz)

/** Recovery requires complete live metadata and a representable flat (zero-gain) curve. */
internal fun customEqRecoveryAvailable(settings: AudioEnhancementSettings, caps: EqualizerCapabilities?): Boolean =
    caps != null && caps.bandCount in 1..128 && caps.centerFrequenciesHz.size == caps.bandCount &&
        caps.centerFrequenciesHz.all { it > 0 } && 0 in caps.minLevelMb..caps.maxLevelMb &&
        settings.eqPresetType == EqPresetType.CUSTOM && !customEqMatchesCapabilities(settings, caps)
