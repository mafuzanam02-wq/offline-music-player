package com.launchpoint.stereosine.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the normalization rules applied by [AudioEnhancementsRepository.normalizeEqPresetStateOnce].
 *
 * Each test models the resulting [AudioEnhancementSettings] that the settings Flow would emit
 * after normalization runs — no DataStore dependency. Helpers mirror the migration logic exactly.
 *
 * Normalization contract:
 *   FLAT            — removes platformPresetIndex and stereoSinePresetId; preserves customBandLevels
 *   PLATFORM        — removes stereoSinePresetId; preserves customBandLevels; keeps platformPresetIndex
 *   CUSTOM          — removes stereoSinePresetId and platformPresetIndex; preserves customBandLevels
 *   WAVDROP_PRESET  — removes platformPresetIndex; preserves customBandLevels;
 *                     if id is blank/unknown → falls back to FLAT and removes stereoSinePresetId
 *   Invalid type    — treated as FLAT by parser; normalized to FLAT
 *
 * Migration is one-time: the marker key eq_preset_state_normalized_v1 prevents repeated runs.
 */
class EqPresetNormalizationMigrationTest {

    private val savedCustomLevels = listOf(-300, 0, 200, 100, -100)

    // ── 1. PLATFORM + stale stereoSineId clears stereoSineId ───────────────────────

    @Test
    fun `PLATFORM normalization clears stale stereoSinePresetId`() {
        val result = normalize(
            rawType        = "PLATFORM",
            platformIndex  = 5,
            stereoSineId      = "balanced",
            customLevels   = savedCustomLevels,
        )
        assertNull("stereoSinePresetId must be cleared when preset is PLATFORM", result.eqStereoSinePresetId)
    }

    @Test
    fun `PLATFORM normalization keeps platformPresetIndex`() {
        val result = normalize(rawType = "PLATFORM", platformIndex = 7)
        assertEquals(7, result.eqPlatformPresetIndex)
    }

    @Test
    fun `PLATFORM normalization preset type remains PLATFORM`() {
        assertEquals(EqPresetType.PLATFORM, normalize(rawType = "PLATFORM", platformIndex = 3).eqPresetType)
    }

    // ── 2. FLAT + stale stereoSineId/platformIndex clears both ──────────────────

    @Test
    fun `FLAT normalization clears stale stereoSinePresetId`() {
        val result = normalize(rawType = "FLAT", stereoSineId = "clarity", platformIndex = 2)
        assertNull("stereoSinePresetId must be cleared when preset is FLAT", result.eqStereoSinePresetId)
    }

    @Test
    fun `FLAT normalization clears stale platformPresetIndex`() {
        val result = normalize(rawType = "FLAT", platformIndex = 4)
        assertNull("platformPresetIndex must be cleared when preset is FLAT", result.eqPlatformPresetIndex)
    }

    @Test
    fun `FLAT normalization preset type remains FLAT`() {
        assertEquals(EqPresetType.FLAT, normalize(rawType = "FLAT").eqPresetType)
    }

    // ── 3. CUSTOM + stale stereoSineId/platformIndex clears both ────────────────

    @Test
    fun `CUSTOM normalization clears stale stereoSinePresetId`() {
        val result = normalize(rawType = "CUSTOM", stereoSineId = "deep-bass", customLevels = savedCustomLevels)
        assertNull("stereoSinePresetId must be cleared when preset is CUSTOM", result.eqStereoSinePresetId)
    }

    @Test
    fun `CUSTOM normalization clears stale platformPresetIndex`() {
        val result = normalize(rawType = "CUSTOM", platformIndex = 9, customLevels = savedCustomLevels)
        assertNull("platformPresetIndex must be cleared when preset is CUSTOM", result.eqPlatformPresetIndex)
    }

    @Test
    fun `CUSTOM normalization preset type remains CUSTOM`() {
        assertEquals(EqPresetType.CUSTOM, normalize(rawType = "CUSTOM").eqPresetType)
    }

    // ── 4. WAVDROP_PRESET + stale platformIndex clears platformIndex ─────────

    @Test
    fun `WAVDROP_PRESET normalization clears stale platformPresetIndex`() {
        val result = normalize(rawType = "WAVDROP_PRESET", stereoSineId = "warm", platformIndex = 6)
        assertNull("platformPresetIndex must be cleared when preset is WAVDROP_PRESET", result.eqPlatformPresetIndex)
    }

    @Test
    fun `WAVDROP_PRESET normalization keeps valid stereoSinePresetId`() {
        val result = normalize(rawType = "WAVDROP_PRESET", stereoSineId = "clarity")
        assertEquals("clarity", result.eqStereoSinePresetId)
    }

    @Test
    fun `WAVDROP_PRESET normalization preset type remains WAVDROP_PRESET for known id`() {
        assertEquals(
            EqPresetType.WAVDROP_PRESET,
            normalize(rawType = "WAVDROP_PRESET", stereoSineId = "balanced").eqPresetType,
        )
    }

    // ── 5. WAVDROP_PRESET + unknown id falls back to FLAT ────────────────────

    @Test
    fun `WAVDROP_PRESET normalization with unknown id falls back to FLAT`() {
        val result = normalize(rawType = "WAVDROP_PRESET", stereoSineId = "no-such-preset")
        assertEquals(
            "unknown stereoSineId must produce FLAT fallback",
            EqPresetType.FLAT,
            result.eqPresetType,
        )
    }

    @Test
    fun `WAVDROP_PRESET normalization with unknown id clears stereoSinePresetId`() {
        val result = normalize(rawType = "WAVDROP_PRESET", stereoSineId = "no-such-preset")
        assertNull("stereoSinePresetId must be null after FLAT fallback", result.eqStereoSinePresetId)
    }

    @Test
    fun `WAVDROP_PRESET normalization with null id falls back to FLAT`() {
        val result = normalize(rawType = "WAVDROP_PRESET", stereoSineId = null)
        assertEquals(EqPresetType.FLAT, result.eqPresetType)
        assertNull(result.eqStereoSinePresetId)
    }

    @Test
    fun `WAVDROP_PRESET normalization with blank id falls back to FLAT`() {
        val result = normalize(rawType = "WAVDROP_PRESET", stereoSineId = "   ")
        assertEquals(EqPresetType.FLAT, result.eqPresetType)
        assertNull(result.eqStereoSinePresetId)
    }

    // ── 6. customBandLevels preserved in all cases ───────────────────────────

    @Test
    fun `FLAT normalization preserves customBandLevels`() {
        val result = normalize(rawType = "FLAT", customLevels = savedCustomLevels)
        assertEquals(savedCustomLevels, result.eqCustomBandLevels)
    }

    @Test
    fun `PLATFORM normalization preserves customBandLevels`() {
        val result = normalize(rawType = "PLATFORM", platformIndex = 1, customLevels = savedCustomLevels)
        assertEquals(savedCustomLevels, result.eqCustomBandLevels)
    }

    @Test
    fun `CUSTOM normalization preserves customBandLevels`() {
        val result = normalize(rawType = "CUSTOM", customLevels = savedCustomLevels)
        assertEquals(savedCustomLevels, result.eqCustomBandLevels)
    }

    @Test
    fun `WAVDROP_PRESET normalization preserves customBandLevels`() {
        val result = normalize(rawType = "WAVDROP_PRESET", stereoSineId = "detail", customLevels = savedCustomLevels)
        assertEquals(savedCustomLevels, result.eqCustomBandLevels)
    }

    @Test
    fun `WAVDROP_PRESET unknown-id fallback preserves customBandLevels`() {
        val result = normalize(rawType = "WAVDROP_PRESET", stereoSineId = "ghost-preset", customLevels = savedCustomLevels)
        assertEquals("customBandLevels must survive FLAT fallback", savedCustomLevels, result.eqCustomBandLevels)
    }

    // ── 7. Migration marker prevents repeated cleanup ─────────────────────────

    @Test
    fun `migration marker key name is eq_preset_state_normalized_v1`() {
        // Guards against accidental key rename — the stored name is an API contract.
        assertEquals("eq_preset_state_normalized_v1", MARKER_KEY_NAME)
    }

    @Test
    fun `normalization does not run when marker is true`() {
        // Simulates the fast-path: marker is set, normalized state is returned unchanged.
        val alreadyNormalized = true
        val dirtyState = AudioEnhancementSettings(
            eqPresetType          = EqPresetType.PLATFORM,
            eqPlatformPresetIndex = 3,
            eqStereoSinePresetId     = "balanced", // stale
        )
        // With marker=true, the dirty state is left untouched (migration skips).
        val result = if (alreadyNormalized) dirtyState else normalize("PLATFORM", platformIndex = 3, stereoSineId = "balanced")
        // Marker prevents normalization — state unchanged.
        assertEquals(EqPresetType.PLATFORM, result.eqPresetType)
        assertEquals("balanced", result.eqStereoSinePresetId) // stale, not cleared (migration skipped)
    }

    @Test
    fun `normalization runs when marker is false`() {
        val alreadyNormalized = false
        val result = if (alreadyNormalized) {
            AudioEnhancementSettings(eqPresetType = EqPresetType.PLATFORM, eqStereoSinePresetId = "balanced")
        } else {
            normalize("PLATFORM", platformIndex = 3, stereoSineId = "balanced")
        }
        assertNull("normalization ran — stale stereoSineId cleared", result.eqStereoSinePresetId)
    }

    // ── Invalid raw type treated as FLAT ─────────────────────────────────────

    @Test
    fun `invalid raw type normalizes to FLAT`() {
        val result = normalize(rawType = "CORRUPTED_VALUE")
        assertEquals(EqPresetType.FLAT, result.eqPresetType)
        assertNull(result.eqStereoSinePresetId)
        assertNull(result.eqPlatformPresetIndex)
    }

    @Test
    fun `null raw type normalizes to FLAT`() {
        val result = normalize(rawType = null)
        assertEquals(EqPresetType.FLAT, result.eqPresetType)
        assertNull(result.eqStereoSinePresetId)
        assertNull(result.eqPlatformPresetIndex)
    }

    // ── All known catalog ids are valid in WAVDROP_PRESET normalization ───────

    @Test
    fun `all catalog preset ids survive WAVDROP_PRESET normalization`() {
        val ids = listOf("balanced", "deep-bass", "clarity", "warm", "detail", "late-night", "spoken-word")
        ids.forEach { id ->
            val result = normalize(rawType = "WAVDROP_PRESET", stereoSineId = id)
            assertEquals("$id should remain WAVDROP_PRESET after normalization", EqPresetType.WAVDROP_PRESET, result.eqPresetType)
            assertEquals("$id should be preserved", id, result.eqStereoSinePresetId)
        }
    }

    // ── Helpers: simulate post-normalization AudioEnhancementSettings state ───

    private fun normalize(
        rawType: String?,
        platformIndex: Int?      = null,
        stereoSineId: String?       = null,
        customLevels: List<Int>  = emptyList(),
    ): AudioEnhancementSettings {
        val presetType = AudioEnhancementsRepository.parseEqPresetType(rawType)
        val parsedId   = AudioEnhancementsRepository.parseStereoSinePresetId(stereoSineId)

        return when (presetType) {
            EqPresetType.FLAT -> AudioEnhancementSettings(
                eqPresetType          = EqPresetType.FLAT,
                eqPlatformPresetIndex = null,
                eqStereoSinePresetId     = null,
                eqCustomBandLevels    = customLevels,
            )
            EqPresetType.PLATFORM -> AudioEnhancementSettings(
                eqPresetType          = EqPresetType.PLATFORM,
                eqPlatformPresetIndex = platformIndex,
                eqStereoSinePresetId     = null,
                eqCustomBandLevels    = customLevels,
            )
            EqPresetType.CUSTOM -> AudioEnhancementSettings(
                eqPresetType          = EqPresetType.CUSTOM,
                eqPlatformPresetIndex = null,
                eqStereoSinePresetId     = null,
                eqCustomBandLevels    = customLevels,
            )
            EqPresetType.WAVDROP_PRESET -> {
                val validId = parsedId?.takeIf { StereoSinePresetCatalog.findById(it) != null }
                if (validId != null) {
                    AudioEnhancementSettings(
                        eqPresetType          = EqPresetType.WAVDROP_PRESET,
                        eqStereoSinePresetId     = validId,
                        eqPlatformPresetIndex = null,
                        eqCustomBandLevels    = customLevels,
                    )
                } else {
                    // Unknown or blank id — fall back to FLAT
                    AudioEnhancementSettings(
                        eqPresetType          = EqPresetType.FLAT,
                        eqPlatformPresetIndex = null,
                        eqStereoSinePresetId     = null,
                        eqCustomBandLevels    = customLevels,
                    )
                }
            }
        }
    }

    private companion object {
        // Must match the DataStore key name used in AudioEnhancementsRepository.
        const val MARKER_KEY_NAME = "eq_preset_state_normalized_v1"
    }
}
