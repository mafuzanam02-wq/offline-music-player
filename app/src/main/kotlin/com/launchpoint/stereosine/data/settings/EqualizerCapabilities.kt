package com.launchpoint.stereosine.data.settings

data class EqualizerCapabilities(
    val bandCount: Int,
    val minLevelMb: Int,
    val maxLevelMb: Int,
    val centerFrequenciesHz: List<Int>,
    val platformPresets: List<EqualizerPlatformPreset>,
    /** Runtime count, independent of successful name lookups. Missing names do not invalidate an index. */
    val platformPresetCount: Int = platformPresets.maxOfOrNull { it.index.toInt() + 1 } ?: 0,
)

data class EqualizerPlatformPreset(
    val index: Short,
    val name: String,
)
