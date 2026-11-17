package com.launchpoint.stereosine.data.settings

data class StereoSinePreset(
    val id: String,
    val displayName: String,
    val description: String,
    val bandLevels: List<Int>,
)

// StereoSine-owned EQ preset catalog.
//
// Levels are in millibels (mB) targeting a 5-band device (S21 frequencies):
//   Band 0: 60 Hz  · Band 1: 230 Hz · Band 2: 910 Hz
//   Band 3: 3.6 kHz · Band 4: 14 kHz
//
// All values are within ±500 mB (±5 dB) — bounded boosts, not a clipping or hearing-safety guarantee.
// IDs are stable lowercase-kebab strings. Never rename or reorder after shipping.
object StereoSinePresetCatalog {

    val all: List<StereoSinePreset> = listOf(
        StereoSinePreset(
            id          = "balanced",
            displayName = "Balanced",
            description = "Clear and natural for any genre.",
            bandLevels  = listOf(100, 100, 0, 100, 100),
        ),
        StereoSinePreset(
            id          = "deep-bass",
            displayName = "Deep Bass",
            description = "Extended low end and sub-bass presence.",
            bandLevels  = listOf(500, 300, -100, 0, 0),
        ),
        StereoSinePreset(
            id          = "clarity",
            displayName = "Clarity",
            description = "Forward vocals and clean midrange.",
            bandLevels  = listOf(-200, -100, 200, 500, 200),
        ),
        StereoSinePreset(
            id          = "warm",
            displayName = "Warm",
            description = "Smooth highs and rich low-midrange body.",
            bandLevels  = listOf(300, 200, 100, -100, -300),
        ),
        StereoSinePreset(
            id          = "detail",
            displayName = "Detail",
            description = "Air, clarity, and presence.",
            bandLevels  = listOf(-100, 0, 100, 300, 500),
        ),
        StereoSinePreset(
            id          = "late-night",
            displayName = "Late Night",
            description = "Balanced presence at lower volume.",
            bandLevels  = listOf(200, 100, 0, 300, 200),
        ),
        StereoSinePreset(
            id          = "spoken-word",
            displayName = "Spoken Word",
            description = "Optimised for podcasts and audiobooks.",
            bandLevels  = listOf(-400, -200, 200, 500, 100),
        ),
    )

    private val byId: Map<String, StereoSinePreset> = all.associateBy { it.id }

    fun findById(id: String?): StereoSinePreset? = id?.let { byId[it] }
}
