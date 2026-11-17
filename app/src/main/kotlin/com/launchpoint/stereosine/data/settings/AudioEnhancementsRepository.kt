package com.launchpoint.stereosine.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Singleton
class AudioEnhancementsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val _capabilities = MutableStateFlow<EqualizerCapabilities?>(null)
    val capabilities: StateFlow<EqualizerCapabilities?> = _capabilities.asStateFlow()

    fun updateCapabilities(caps: EqualizerCapabilities?) {
        _capabilities.value = caps
    }

    private val _applicationState = MutableStateFlow(EqualizerApplicationState())
    val applicationState: StateFlow<EqualizerApplicationState> = _applicationState.asStateFlow()

    internal fun updateApplicationState(state: EqualizerApplicationState) {
        _applicationState.value = state
    }

    val profiles: Flow<OutputProfilesState> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map(::resolveProfiles)
        .distinctUntilChanged()

    /** Sole effective EQ authority for the editor, effects and crossfade. */
    val settings: Flow<AudioEnhancementSettings> = profiles.map { it.effective }.distinctUntilChanged()

    val headphonePresets: Flow<HeadphonePresetLibrary> = dataStore.data
        .map { headphoneDocument(it)?.library ?: HeadphonePresetLibrary(writable = false) }
        .catch { error -> if (error is IOException) emit(HeadphonePresetLibrary(writable = false)) else throw error }
        .distinctUntilChanged()

    private fun headphoneDocument(p: Preferences): HeadphonePresetCodec.Document? {
        val raw = p.asMap()[HEADPHONE_PRESETS]
        return if (raw == null) HeadphonePresetCodec.document(null) else (raw as? String)?.let(HeadphonePresetCodec::document)
    }

    private suspend fun headphoneEdit(action: (MutablePreferences) -> HeadphonePresetResult): HeadphonePresetResult {
        var result = HeadphonePresetResult.STORAGE_UNAVAILABLE
        try { dataStore.edit { result = action(it) } }
        catch (_: IOException) { return HeadphonePresetResult.STORAGE_UNAVAILABLE }
        return result
    }

    suspend fun saveHeadphonePreset(expected: OutputProfile, request: AudioEnhancementSettings, confirmed: EqualizerCapabilities, rawName: String): HeadphonePresetResult {
        return headphoneEdit { p ->
            val state = resolveProfiles(p)
            val currentCaps = capabilities.value
            when {
                state.active != expected -> HeadphonePresetResult.PROFILE_CHANGED
                !expected.supportsHeadphonePresets() -> HeadphonePresetResult.WRONG_PROFILE
                state.usingGlobalFallback -> HeadphonePresetResult.STORAGE_UNAVAILABLE
                state.effective != request -> HeadphonePresetResult.CONFIGURATION_CHANGED
                request.eqPresetType != EqPresetType.CUSTOM -> HeadphonePresetResult.NOT_CUSTOM
                currentCaps == null -> HeadphonePresetResult.CAPABILITIES_UNAVAILABLE
                currentCaps != confirmed -> HeadphonePresetResult.CAPABILITIES_CHANGED
                !headphoneSaveCompatible(request, currentCaps, applicationState.value) -> HeadphonePresetResult.INCOMPATIBLE
                else -> {
                    val name = HeadphonePresetCodec.name(rawName)
                    val doc = headphoneDocument(p)
                    when {
                        name == null -> HeadphonePresetResult.INVALID_NAME
                        doc == null || !doc.library.writable -> HeadphonePresetResult.STORAGE_UNAVAILABLE
                        doc.indexed.size >= HeadphonePresetCodec.MAX_PRESETS || doc.entries.length() >= HeadphonePresetCodec.MAX_ENTRIES -> HeadphonePresetResult.LIMIT_REACHED
                        else -> {
                            val id = java.util.UUID.randomUUID().toString()
                            if ((0 until doc.entries.length()).any { doc.entries.optJSONObject(it)?.optString("id") == id }) {
                                HeadphonePresetResult.PRESET_CHANGED
                            } else {
                                val preset = HeadphoneEqPreset(id, name, request.eqCustomBandLevels.toList(), confirmed.centerFrequenciesHz.toList())
                                doc.entries.put(HeadphonePresetCodec.encode(preset))
                                val raw = doc.serialized()
                                if (raw == null) HeadphonePresetResult.LIMIT_REACHED else {
                                    p[HEADPHONE_PRESETS] = raw; HeadphonePresetResult.SAVED
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun applyHeadphonePreset(expected: OutputProfile, preset: HeadphoneEqPreset, confirmed: EqualizerCapabilities): HeadphonePresetResult {
        return headphoneEdit { p ->
            val state = resolveProfiles(p)
            val currentCaps = capabilities.value
            val doc = headphoneDocument(p)
            when {
                state.active != expected -> HeadphonePresetResult.PROFILE_CHANGED
                !expected.supportsHeadphonePresets() -> HeadphonePresetResult.WRONG_PROFILE
                state.usingGlobalFallback || doc == null -> HeadphonePresetResult.STORAGE_UNAVAILABLE
                doc.indexed[preset.id]?.second != preset -> HeadphonePresetResult.PRESET_CHANGED
                currentCaps == null -> HeadphonePresetResult.CAPABILITIES_UNAVAILABLE
                currentCaps != confirmed -> HeadphonePresetResult.CAPABILITIES_CHANGED
                !headphoneCurveCompatible(preset.levels, preset.frequenciesHz, currentCaps) -> HeadphonePresetResult.INCOMPATIBLE
                else -> {
                    val updated = state.effective.copy(eqPresetType = EqPresetType.CUSTOM,
                        eqCustomBandLevels = preset.levels.toList(), eqCustomBandFrequenciesHz = preset.frequenciesHz.toList(),
                        eqPlatformPresetIndex = null, eqStereoSinePresetId = null)
                    if (updated == state.effective) HeadphonePresetResult.UNCHANGED else {
                        // Same canonical category encoding used by every active-profile edit; never touches other profiles/library.
                        p[CATEGORY_KEYS.getValue(state.active)] = OutputProfileCodec.encode(updated)
                        HeadphonePresetResult.COPIED_TO_PROFILE
                    }
                }
            }
        }
    }

    suspend fun renameHeadphonePreset(expected: OutputProfile, preset: HeadphoneEqPreset, rawName: String): HeadphonePresetResult =
        editHeadphonePreset(expected, preset, rawName)

    suspend fun deleteHeadphonePreset(expected: OutputProfile, preset: HeadphoneEqPreset): HeadphonePresetResult =
        editHeadphonePreset(expected, preset, null)

    private suspend fun editHeadphonePreset(expected: OutputProfile, preset: HeadphoneEqPreset, rawName: String?): HeadphonePresetResult {
        return headphoneEdit { p ->
            val state = resolveProfiles(p)
            val doc = headphoneDocument(p)
            when {
                state.active != expected -> HeadphonePresetResult.PROFILE_CHANGED
                doc == null || !doc.library.writable -> HeadphonePresetResult.STORAGE_UNAVAILABLE
                doc.indexed[preset.id]?.second != preset -> HeadphonePresetResult.PRESET_CHANGED
                rawName != null && HeadphonePresetCodec.name(rawName) == null -> HeadphonePresetResult.INVALID_NAME
                rawName != null && HeadphonePresetCodec.name(rawName) == preset.name -> HeadphonePresetResult.UNCHANGED
                else -> {
                    val index = doc.indexed.getValue(preset.id).first
                    if (rawName == null) doc.entries.remove(index)
                    else doc.entries.getJSONObject(index).put("name", HeadphonePresetCodec.name(rawName))
                    val raw = doc.serialized()
                    if (raw == null) HeadphonePresetResult.LIMIT_REACHED else {
                        p[HEADPHONE_PRESETS] = raw
                        if (rawName == null) HeadphonePresetResult.DELETED else HeadphonePresetResult.RENAMED
                    }
                }
            }
        }
    }

    private fun global(p: Preferences) = AudioEnhancementSettings(
        eqEnabled = p[EQ_ENABLED] ?: false,
        eqPresetType = parseEqPresetType(p[EQ_PRESET_TYPE]),
        eqPlatformPresetIndex = p[EQ_PLATFORM_PRESET_INDEX],
        eqCustomBandLevels = parseBandLevels(p[EQ_CUSTOM_BAND_LEVELS]),
        eqStereoSinePresetId = parseStereoSinePresetId(p[EQ_STEREOSINE_PRESET_ID]),
        eqCustomBandFrequenciesHz = parseBandLevels(p[EQ_CUSTOM_FREQUENCIES]),
    )

    private fun resolveProfiles(p: Preferences): OutputProfilesState {
        val raw = p[ACTIVE_PROFILE]
        val selected = raw?.let { runCatching { OutputProfile.valueOf(it) }.getOrNull() }
        val configs = mutableMapOf(OutputProfile.GLOBAL to global(p))
        CATEGORY_KEYS.forEach { (profile, key) -> OutputProfileCodec.decode(p[key])?.let { configs[profile] = it } }
        return OutputProfilesState(selected ?: OutputProfile.GLOBAL, configs, p[PROFILES_INITIALIZED] ?: false, raw != null && selected == null)
    }

    suspend fun selectProfile(profile: OutputProfile) {
        dataStore.edit { p ->
            val state = resolveProfiles(p)
            if (profile != OutputProfile.GLOBAL && !state.initialized) {
                // All categories inherit one snapshot. First selection cannot change effective sound.
                val seed = OutputProfileCodec.encode(state.configurations.getValue(OutputProfile.GLOBAL))
                CATEGORY_KEYS.values.forEach { key -> if (p[key] == null) p[key] = seed }
                p[PROFILES_INITIALIZED] = true
            }
            if (p[ACTIVE_PROFILE] != profile.name) p[ACTIVE_PROFILE] = profile.name
        }
    }

    private suspend fun editActive(expected: OutputProfile?, transform: (AudioEnhancementSettings) -> AudioEnhancementSettings) {
        dataStore.edit { p ->
            val state = resolveProfiles(p)
            if (expected != null && expected != state.active) return@edit // stale UI action
            if (state.usingGlobalFallback) return@edit // retain corrupt/missing raw profile data
            val updated = transform(state.effective)
            if (updated == state.effective) return@edit
            if (state.active == OutputProfile.GLOBAL) {
                p[EQ_ENABLED] = updated.eqEnabled
                p[EQ_PRESET_TYPE] = updated.eqPresetType.name
                p[EQ_CUSTOM_BAND_LEVELS] = updated.eqCustomBandLevels.joinToString(",")
                p[EQ_CUSTOM_FREQUENCIES] = updated.eqCustomBandFrequenciesHz.joinToString(",")
                updated.eqPlatformPresetIndex?.let { p[EQ_PLATFORM_PRESET_INDEX] = it } ?: p.remove(EQ_PLATFORM_PRESET_INDEX)
                updated.eqStereoSinePresetId?.let { p[EQ_STEREOSINE_PRESET_ID] = it } ?: p.remove(EQ_STEREOSINE_PRESET_ID)
            } else p[CATEGORY_KEYS.getValue(state.active)] = OutputProfileCodec.encode(updated)
        }
    }

    suspend fun setEqEnabled(enabled: Boolean, expected: OutputProfile? = null) =
        editActive(expected) { it.copy(eqEnabled = enabled) }

    suspend fun setEqFlatPreset(expected: OutputProfile? = null) =
        editActive(expected) { it.copy(eqPresetType = EqPresetType.FLAT, eqPlatformPresetIndex = null, eqStereoSinePresetId = null) }

    suspend fun setEqPlatformPreset(index: Int, expected: OutputProfile? = null) =
        editActive(expected) { it.copy(eqPresetType = EqPresetType.PLATFORM, eqPlatformPresetIndex = index, eqStereoSinePresetId = null) }

    suspend fun setEqCustomPreset(bandCount: Int, expected: OutputProfile? = null, frequenciesHz: List<Int> = emptyList()) =
        editActive(expected) {
            if (bandCount !in 1..128) return@editActive it
            // An existing incompatible curve stays saved/read-only; only an empty curve is initialized.
            val empty = it.eqCustomBandLevels.isEmpty()
            it.copy(eqPresetType = EqPresetType.CUSTOM, eqPlatformPresetIndex = null, eqStereoSinePresetId = null,
                eqCustomBandLevels = if (empty) List(bandCount.coerceIn(0, 128)) { 0 } else it.eqCustomBandLevels,
                eqCustomBandFrequenciesHz = if (empty) validFrequencies(frequenciesHz, bandCount) else it.eqCustomBandFrequenciesHz)
        }

    suspend fun setEqCustomBandLevels(levels: List<Int>, expected: OutputProfile? = null, frequenciesHz: List<Int> = emptyList()) =
        editActive(expected) {
            if (levels.size > 128) return@editActive it
            it.copy(eqPresetType = EqPresetType.CUSTOM, eqCustomBandLevels = levels.toList(),
                eqCustomBandFrequenciesHz = validFrequencies(frequenciesHz, levels.size), eqPlatformPresetIndex = null, eqStereoSinePresetId = null)
        }

    /** Explicit destructive recovery only. Selection and capability checks occur in the serialized edit. */
    suspend fun resetCustomBandsForDevice(expected: OutputProfile, confirmedCapabilities: EqualizerCapabilities) =
        editActive(expected) { saved ->
            val current = capabilities.value ?: return@editActive saved
            if (current != confirmedCapabilities || !customEqRecoveryAvailable(saved, current)) return@editActive saved
            saved.copy(
                eqPresetType = EqPresetType.CUSTOM,
                eqCustomBandLevels = List(current.bandCount) { 0 },
                eqCustomBandFrequenciesHz = current.centerFrequenciesHz.toList(),
                eqPlatformPresetIndex = null,
                eqStereoSinePresetId = null,
            )
        }

    private fun validFrequencies(values: List<Int>, size: Int): List<Int> =
        values.takeIf { it.size == size && it.all { hz -> hz > 0 } }?.toList() ?: emptyList()

    // One-time startup cleanup: removes stale EQ keys left by earlier builds.
    // Idempotent after the first run — guarded by EQ_PRESET_STATE_NORMALIZED_V1.
    // Safe to call concurrently with settings collection; DataStore serializes edits.
    suspend fun normalizeEqPresetStateOnce() {
        // Fast path: if already normalized, skip the edit lock entirely.
        if (dataStore.data.first()[EQ_PRESET_STATE_NORMALIZED_V1] == true) return

        dataStore.edit { prefs ->
            // Re-check inside the atomic edit block to handle unlikely concurrent calls.
            if (prefs[EQ_PRESET_STATE_NORMALIZED_V1] == true) return@edit

            val rawType   = prefs[EQ_PRESET_TYPE]
            val presetType = parseEqPresetType(rawType)

            // If the stored type string is non-null but unrecognised, write FLAT so
            // the stored value matches what the parser already returns.
            if (rawType != null && runCatching { EqPresetType.valueOf(rawType) }.isFailure) {
                prefs[EQ_PRESET_TYPE] = EqPresetType.FLAT.name
            }

            when (presetType) {
                EqPresetType.FLAT -> {
                    prefs.remove(EQ_PLATFORM_PRESET_INDEX)
                    prefs.remove(EQ_STEREOSINE_PRESET_ID)
                }
                EqPresetType.PLATFORM -> {
                    prefs.remove(EQ_STEREOSINE_PRESET_ID)
                    // eq_platform_preset_index is the active key — leave it
                }
                EqPresetType.CUSTOM -> {
                    prefs.remove(EQ_STEREOSINE_PRESET_ID)
                    prefs.remove(EQ_PLATFORM_PRESET_INDEX)
                    // eq_custom_band_levels preserved
                }
                EqPresetType.WAVDROP_PRESET -> {
                    prefs.remove(EQ_PLATFORM_PRESET_INDEX)
                    val id = parseStereoSinePresetId(prefs[EQ_STEREOSINE_PRESET_ID])
                    val validInCatalog = id != null && StereoSinePresetCatalog.findById(id) != null
                    if (!validInCatalog) {
                        // Unknown or blank StereoSine id — fall back to FLAT safely.
                        prefs[EQ_PRESET_TYPE] = EqPresetType.FLAT.name
                        prefs.remove(EQ_STEREOSINE_PRESET_ID)
                    }
                    // eq_custom_band_levels preserved
                }
            }

            prefs[EQ_PRESET_STATE_NORMALIZED_V1] = true
        }
    }

    suspend fun setEqStereoSinePreset(id: String, expected: OutputProfile? = null) =
        editActive(expected) { it.copy(eqPresetType = EqPresetType.WAVDROP_PRESET, eqStereoSinePresetId = id, eqPlatformPresetIndex = null) }

    companion object {
        private val HEADPHONE_PRESETS = stringPreferencesKey("eq_headphone_presets_v1")
        private val ACTIVE_PROFILE = stringPreferencesKey("eq_active_output_profile")
        private val PROFILES_INITIALIZED = booleanPreferencesKey("eq_output_profiles_initialized_v1")
        private val CATEGORY_KEYS = mapOf(
            OutputProfile.SPEAKER to stringPreferencesKey("eq_profile_speaker_v1"),
            OutputProfile.WIRED to stringPreferencesKey("eq_profile_wired_v1"),
            OutputProfile.BLUETOOTH to stringPreferencesKey("eq_profile_bluetooth_v1"),
        )
        private val EQ_CUSTOM_FREQUENCIES = stringPreferencesKey("eq_custom_band_frequencies_hz")
        private val EQ_ENABLED                    = booleanPreferencesKey("eq_enabled")
        private val EQ_PRESET_TYPE                = stringPreferencesKey("eq_preset_type")
        private val EQ_PLATFORM_PRESET_INDEX      = intPreferencesKey("eq_platform_preset_index")
        private val EQ_CUSTOM_BAND_LEVELS         = stringPreferencesKey("eq_custom_band_levels")
        private val EQ_STEREOSINE_PRESET_ID          = stringPreferencesKey("eq_wavdrop_preset_id")
        private val EQ_PRESET_STATE_NORMALIZED_V1 = booleanPreferencesKey("eq_preset_state_normalized_v1")

        // Extracted for unit-testability — no DataStore dependency.
        internal fun parseEqPresetType(raw: String?): EqPresetType =
            raw?.let { runCatching { EqPresetType.valueOf(it) }.getOrNull() } ?: EqPresetType.FLAT

        internal fun parseBandLevels(raw: String?): List<Int> {
            if (raw.isNullOrBlank()) return emptyList()
            return raw.split(",").mapNotNull { runCatching { it.trim().toInt() }.getOrNull() }
        }

        internal fun parseStereoSinePresetId(raw: String?): String? =
            raw?.trim()?.takeIf { it.isNotEmpty() }
    }
}
