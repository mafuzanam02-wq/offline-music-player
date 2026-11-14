package com.launchpoint.stereosine.playback

import com.launchpoint.stereosine.data.settings.AudioEnhancementSettings
import com.launchpoint.stereosine.data.settings.EqPresetType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EqDebugSummaryTest {

    private val bandCount      = 5
    private val customLevels   = listOf(-300, 0, 200, 100, -100)

    // ── WAVDROP_PRESET — shows catalog levels, not stored custom levels ───────

    @Test
    fun `WAVDROP_PRESET summary shows stereoSineId and catalog activeLevels`() {
        val summary = buildEqApplyDebugSummary(
            settings = AudioEnhancementSettings(
                eqPresetType       = EqPresetType.WAVDROP_PRESET,
                eqStereoSinePresetId  = "balanced",
                eqCustomBandLevels = customLevels,  // stored but must NOT appear
            ),
            bandCount = bandCount,
        )
        assertTrue("summary must contain stereoSineId", "stereoSineId=balanced" in summary)
        assertTrue("summary must contain stereoSineName", "stereoSineName=Balanced" in summary)
        assertTrue("summary must contain catalog activeLevels=[100, 100, 0, 100, 100]",
            "activeLevels=[100, 100, 0, 100, 100]" in summary)
        assertFalse("summary must NOT reference stored custom levels",
            customLevels.toString() in summary)
    }

    @Test
    fun `WAVDROP_PRESET summary for deep-bass shows its catalog levels`() {
        val summary = buildEqApplyDebugSummary(
            settings = AudioEnhancementSettings(
                eqPresetType      = EqPresetType.WAVDROP_PRESET,
                eqStereoSinePresetId = "deep-bass",
            ),
            bandCount = bandCount,
        )
        assertTrue("stereoSineId=deep-bass" in summary)
        assertTrue("stereoSineName=Deep Bass" in summary)
        assertTrue("activeLevels=[500, 300, -100, 0, 0]" in summary)
    }

    @Test
    fun `WAVDROP_PRESET unknown id summary marks activeLevels unavailable`() {
        val summary = buildEqApplyDebugSummary(
            settings = AudioEnhancementSettings(
                eqPresetType      = EqPresetType.WAVDROP_PRESET,
                eqStereoSinePresetId = "ghost-preset",
            ),
            bandCount = bandCount,
        )
        assertTrue("stereoSineId=ghost-preset" in summary)
        assertTrue("activeLevels=unavailable" in summary)
    }

    @Test
    fun `WAVDROP_PRESET band mismatch summary marks activeLevels unavailable`() {
        val summary = buildEqApplyDebugSummary(
            settings = AudioEnhancementSettings(
                eqPresetType      = EqPresetType.WAVDROP_PRESET,
                eqStereoSinePresetId = "balanced",
            ),
            bandCount = 10,  // catalog has 5 bands — mismatch
        )
        assertTrue("activeLevels=unavailable" in summary)
    }

    // ── CUSTOM — shows custom levels ──────────────────────────────────────────

    @Test
    fun `CUSTOM summary shows stored activeLevels`() {
        val summary = buildEqApplyDebugSummary(
            settings = AudioEnhancementSettings(
                eqPresetType       = EqPresetType.CUSTOM,
                eqCustomBandLevels = customLevels,
            ),
            bandCount = bandCount,
        )
        assertTrue("preset=CUSTOM" in summary)
        assertTrue("activeLevels=$customLevels" in summary)
    }

    @Test
    fun `CUSTOM size mismatch marks activeLevels unavailable`() {
        val summary = buildEqApplyDebugSummary(
            settings = AudioEnhancementSettings(
                eqPresetType       = EqPresetType.CUSTOM,
                eqCustomBandLevels = listOf(0, 100),  // wrong size for 5-band device
            ),
            bandCount = bandCount,
        )
        assertTrue("preset=CUSTOM" in summary)
        assertTrue("activeLevels=unavailable" in summary)
    }

    // ── PLATFORM — does not show custom levels ────────────────────────────────

    @Test
    fun `PLATFORM summary does not contain custom levels`() {
        val summary = buildEqApplyDebugSummary(
            settings = AudioEnhancementSettings(
                eqPresetType          = EqPresetType.PLATFORM,
                eqPlatformPresetIndex = 9,
                eqCustomBandLevels    = customLevels,  // stored but must NOT appear
            ),
            bandCount    = bandCount,
            platformName = "Rock",
        )
        assertTrue("preset=PLATFORM" in summary)
        assertTrue("platformIndex=9" in summary)
        assertTrue("platformName=Rock" in summary)
        assertFalse("custom levels must not appear in PLATFORM summary",
            customLevels.toString() in summary)
    }

    @Test
    fun `PLATFORM summary with readback shows readbackLevels`() {
        val readback = listOf(500, 300, -100, 300, 500)
        val summary = buildEqApplyDebugSummary(
            settings = AudioEnhancementSettings(
                eqPresetType          = EqPresetType.PLATFORM,
                eqPlatformPresetIndex = 9,
            ),
            bandCount        = bandCount,
            platformName     = "Rock",
            platformReadback = readback,
        )
        assertTrue("readbackLevels=$readback" in summary)
    }

    @Test
    fun `PLATFORM summary without readback shows readbackLevels=unavailable`() {
        val summary = buildEqApplyDebugSummary(
            settings = AudioEnhancementSettings(
                eqPresetType          = EqPresetType.PLATFORM,
                eqPlatformPresetIndex = 3,
            ),
            bandCount        = bandCount,
            platformName     = "Flat",
            platformReadback = null,
        )
        assertTrue("readbackLevels=unavailable" in summary)
    }

    @Test
    fun `PLATFORM null index summary marks activeLevels unavailable`() {
        val summary = buildEqApplyDebugSummary(
            settings = AudioEnhancementSettings(
                eqPresetType          = EqPresetType.PLATFORM,
                eqPlatformPresetIndex = null,
            ),
            bandCount = bandCount,
        )
        assertTrue("platformIndex=null" in summary)
        assertTrue("activeLevels=unavailable" in summary)
    }

    // ── FLAT — does not show custom levels ────────────────────────────────────

    @Test
    fun `FLAT summary does not contain custom levels`() {
        val summary = buildEqApplyDebugSummary(
            settings = AudioEnhancementSettings(
                eqPresetType       = EqPresetType.FLAT,
                eqCustomBandLevels = customLevels,  // stored but must NOT appear
            ),
            bandCount = bandCount,
        )
        assertTrue("preset=FLAT" in summary)
        assertTrue("activeLevels=[0, 0, 0, 0, 0]" in summary)
        assertFalse("custom levels must not appear in FLAT summary",
            customLevels.toString() in summary)
    }

    @Test
    fun `FLAT summary shows zero activeLevels for band count`() {
        val summary = buildEqApplyDebugSummary(
            settings  = AudioEnhancementSettings(eqPresetType = EqPresetType.FLAT),
            bandCount = 5,
        )
        assertTrue("activeLevels=[0, 0, 0, 0, 0]" in summary)
    }

    // ── preset= field always present ─────────────────────────────────────────

    @Test
    fun `all preset types include preset= field`() {
        for (type in EqPresetType.entries) {
            val settings = AudioEnhancementSettings(
                eqPresetType          = type,
                eqStereoSinePresetId     = if (type == EqPresetType.WAVDROP_PRESET) "balanced" else null,
                eqPlatformPresetIndex = if (type == EqPresetType.PLATFORM) 0 else null,
                eqCustomBandLevels    = if (type == EqPresetType.CUSTOM) List(bandCount) { 0 } else emptyList(),
            )
            val summary = buildEqApplyDebugSummary(settings, bandCount, platformName = "Normal")
            assertTrue("summary for $type must start with preset=", summary.startsWith("preset="))
        }
    }
}
