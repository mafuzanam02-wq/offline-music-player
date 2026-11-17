package com.launchpoint.stereosine.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HeadphonePresetRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private val caps = EqualizerCapabilities(2, -500, 500, listOf(100, 1000), emptyList())
    private val key = stringPreferencesKey("eq_headphone_presets_v1")
    private fun testRepo(block: suspend (AudioEnhancementsRepository, DataStore<Preferences>) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { temp.root.resolve("headphones.preferences_pb") }
        try { block(AudioEnhancementsRepository(store), store) } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
    private suspend fun ready(repo: AudioEnhancementsRepository) {
        repo.selectProfile(OutputProfile.WIRED)
        repo.setEqCustomBandLevels(listOf(200, -100), OutputProfile.WIRED, caps.centerFrequenciesHz)
        repo.setEqEnabled(true); repo.updateCapabilities(caps)
    }
    private suspend fun save(repo: AudioEnhancementsRepository, name: String = "Commute"): HeadphoneEqPreset {
        val before = repo.headphonePresets.first().presets.map { it.id }.toSet()
        assertEquals(HeadphonePresetResult.SAVED, repo.saveHeadphonePreset(OutputProfile.WIRED, repo.settings.first(), caps, name))
        return repo.headphonePresets.first().presets.single { it.id !in before }
    }
    private fun document(vararg entries: JSONObject) = JSONObject().put("version", 1).put("entries", JSONArray(entries.toList())).toString()
    private fun entry(name: String = "Saved") = HeadphonePresetCodec.encode(HeadphoneEqPreset(UUID.randomUUID().toString(), name, listOf(200, -100), caps.centerFrequenciesHz))

    @Test fun saveIsAnIndependentNamedSnapshotAndDoesNotChangeRequestedSound() = testRepo { repo, _ ->
        ready(repo); val before = repo.profiles.first(); val p = save(repo, "  Commute  ")
        assertTrue(HeadphonePresetCodec.validId(p.id)); assertEquals("Commute", p.name)
        assertEquals(caps.centerFrequenciesHz, p.frequenciesHz); assertEquals(1, p.version)
        assertEquals(before, repo.profiles.first())
        repo.setEqCustomBandLevels(listOf(100, 100), OutputProfile.WIRED, caps.centerFrequenciesHz)
        assertEquals(p, repo.headphonePresets.first().presets.single())
    }
    @Test fun applyIndependentlyCopiesOnlyWiredOrBluetoothAndPreservesEnabledFlag() = testRepo { repo, store ->
        ready(repo); val p = save(repo); val library = store.data.first()[key]
        for (profile in listOf(OutputProfile.WIRED, OutputProfile.BLUETOOTH)) {
            repo.selectProfile(profile); repo.setEqCustomBandLevels(listOf(0, 0), profile, caps.centerFrequenciesHz)
            repo.setEqEnabled(false, profile); repo.updateCapabilities(caps)
            val before = repo.profiles.first()
            assertEquals(HeadphonePresetResult.COPIED_TO_PROFILE, repo.applyHeadphonePreset(profile, p, caps))
            val after = repo.profiles.first()
            assertFalse(after.effective.eqEnabled); assertEquals(EqPresetType.CUSTOM, after.effective.eqPresetType)
            assertEquals(p.levels, after.effective.eqCustomBandLevels); assertEquals(p.frequenciesHz, after.effective.eqCustomBandFrequenciesHz)
            OutputProfile.entries.filter { it != profile }.forEach { assertEquals(before.configurations[it], after.configurations[it]) }
            assertEquals(library, store.data.first()[key])
        }
    }
    @Test fun globalAndSpeakerCannotSaveOrApplyAndAreNotAutomaticallySwitched() = testRepo { repo, _ ->
        ready(repo); val p = save(repo)
        for (profile in listOf(OutputProfile.GLOBAL, OutputProfile.SPEAKER)) {
            repo.selectProfile(profile); val before = repo.profiles.first()
            assertEquals(HeadphonePresetResult.WRONG_PROFILE, repo.applyHeadphonePreset(profile, p, caps))
            assertEquals(HeadphonePresetResult.WRONG_PROFILE, repo.saveHeadphonePreset(profile, repo.settings.first(), caps, "No"))
            assertEquals(before, repo.profiles.first())
        }
    }
    @Test fun absentChangedOrIncompatibleCapabilitiesCannotApply() = testRepo { repo, _ ->
        ready(repo); val p = save(repo); repo.setEqCustomBandLevels(listOf(0, 0), OutputProfile.WIRED, caps.centerFrequenciesHz)
        val before = repo.profiles.first()
        repo.updateCapabilities(null)
        assertEquals(HeadphonePresetResult.CAPABILITIES_UNAVAILABLE, repo.applyHeadphonePreset(OutputProfile.WIRED, p, caps))
        assertEquals(HeadphonePresetResult.CAPABILITIES_UNAVAILABLE, repo.saveHeadphonePreset(OutputProfile.WIRED, repo.settings.first(), caps, "No"))
        val changed = caps.copy(centerFrequenciesHz = listOf(200, 2000))
        repo.updateCapabilities(changed)
        assertEquals(HeadphonePresetResult.CAPABILITIES_CHANGED, repo.applyHeadphonePreset(OutputProfile.WIRED, p, caps))
        assertEquals(HeadphonePresetResult.INCOMPATIBLE, repo.applyHeadphonePreset(OutputProfile.WIRED, p, changed))
        val range = caps.copy(minLevelMb = -50, maxLevelMb = 50); repo.updateCapabilities(range)
        assertEquals(HeadphonePresetResult.INCOMPATIBLE, repo.applyHeadphonePreset(OutputProfile.WIRED, p, range))
        assertEquals(before, repo.profiles.first())
    }
    @Test fun knownDifferentFrequenciesAndNonCustomRequestsCannotBeSaved() = testRepo { repo, _ ->
        ready(repo); repo.setEqCustomBandLevels(listOf(200, -100), OutputProfile.WIRED, listOf(200, 2000))
        assertEquals(HeadphonePresetResult.INCOMPATIBLE, repo.saveHeadphonePreset(OutputProfile.WIRED, repo.settings.first(), caps, "No"))
        repo.setEqFlatPreset()
        assertEquals(HeadphonePresetResult.NOT_CUSTOM, repo.saveHeadphonePreset(OutputProfile.WIRED, repo.settings.first(), caps, "No"))
        assertTrue(repo.headphonePresets.first().presets.isEmpty())
    }
    @Test fun legacyUnknownFrequenciesRequireMatchingSuccessfulApiReadback() = testRepo { repo, _ ->
        ready(repo); repo.setEqCustomBandLevels(listOf(200, -100)); val request = repo.settings.first()
        assertEquals(HeadphonePresetResult.INCOMPATIBLE, repo.saveHeadphonePreset(OutputProfile.WIRED, request, caps, "Legacy"))
        repo.updateApplicationState(EqualizerApplicationState(EqualizerApplicationStatus.APPLIED, 7, request, reportedBandLevels = request.eqCustomBandLevels))
        val p = save(repo, "Legacy")
        assertEquals(caps.centerFrequenciesHz, p.frequenciesHz); assertEquals(request, repo.settings.first())
        repo.updateApplicationState(repo.applicationState.value.copy(issues = setOf(EqualizerApplicationIssue.CLAMPED)))
        assertEquals(HeadphonePresetResult.INCOMPATIBLE, repo.saveHeadphonePreset(OutputProfile.WIRED, request, caps, "No"))
    }
    @Test fun duplicateNamesKeepDistinctIdsAndRenameDoesNotChangeCurveOrId() = testRepo { repo, _ ->
        ready(repo); val a = save(repo); val b = save(repo)
        assertNotEquals(a.id, b.id); assertEquals(a.name, b.name)
        val profiles = repo.profiles.first()
        assertEquals(HeadphonePresetResult.RENAMED, repo.renameHeadphonePreset(OutputProfile.WIRED, a, "Office"))
        val renamed = repo.headphonePresets.first().presets.single { it.id == a.id }
        assertEquals(a.copy(name = "Office"), renamed); assertEquals(profiles, repo.profiles.first())
    }
    @Test fun deletionOnlyRemovesExpectedPresetAndKeepsCopiedProfileCurve() = testRepo { repo, _ ->
        ready(repo); val a = save(repo); val b = save(repo, "Other"); val before = repo.profiles.first()
        assertEquals(HeadphonePresetResult.DELETED, repo.deleteHeadphonePreset(OutputProfile.WIRED, a))
        assertEquals(listOf(b), repo.headphonePresets.first().presets); assertEquals(before, repo.profiles.first())
        assertEquals(HeadphonePresetResult.PRESET_CHANGED, repo.deleteHeadphonePreset(OutputProfile.WIRED, a))
    }
    @Test fun staleProfileCurveAndPresetSnapshotsRejectQueuedActions() = testRepo { repo, _ ->
        ready(repo); val p = save(repo); val old = repo.settings.first()
        repo.setEqCustomBandLevels(listOf(0, 0), OutputProfile.WIRED, caps.centerFrequenciesHz)
        assertEquals(HeadphonePresetResult.CONFIGURATION_CHANGED, repo.saveHeadphonePreset(OutputProfile.WIRED, old, caps, "Old"))
        repo.selectProfile(OutputProfile.BLUETOOTH); val before = repo.profiles.first()
        assertEquals(HeadphonePresetResult.PROFILE_CHANGED, repo.applyHeadphonePreset(OutputProfile.WIRED, p, caps))
        assertEquals(HeadphonePresetResult.PROFILE_CHANGED, repo.renameHeadphonePreset(OutputProfile.WIRED, p, "No"))
        assertEquals(HeadphonePresetResult.PROFILE_CHANGED, repo.deleteHeadphonePreset(OutputProfile.WIRED, p))
        assertEquals(before, repo.profiles.first())
        repo.selectProfile(OutputProfile.WIRED); repo.renameHeadphonePreset(OutputProfile.WIRED, p, "Updated")
        assertEquals(HeadphonePresetResult.PRESET_CHANGED, repo.applyHeadphonePreset(OutputProfile.WIRED, p, caps))
        assertEquals(HeadphonePresetResult.PRESET_CHANGED, repo.deleteHeadphonePreset(OutputProfile.WIRED, p))
        assertEquals(HeadphonePresetResult.PRESET_CHANGED, repo.renameHeadphonePreset(OutputProfile.WIRED, p, "Stale"))
    }
    @Test fun repeatedApplyIsIdempotentAndDoesNotChangeLibraryOrEnabledIntent() = testRepo { repo, store ->
        ready(repo); val p = save(repo); val before = store.data.first()
        repeat(4) { assertEquals(HeadphonePresetResult.UNCHANGED, repo.applyHeadphonePreset(OutputProfile.WIRED, p, caps)) }
        assertEquals(before, store.data.first())
    }
    @Test fun malformedEntriesAndDuplicateIdsDoNotLoseUnrelatedValidCurves() = testRepo { repo, store ->
        ready(repo)
        val good = entry("Valid"); val bad = entry("Bad").put("id", "../not-an-id")
        val future = entry("Future").put("version", 2)
        val duplicate = entry("Duplicate")
        store.edit { it[key] = document(good, bad, future, duplicate, JSONObject(duplicate.toString())) }
        val lib = repo.headphonePresets.first()
        assertEquals(listOf("Valid"), lib.presets.map { it.name }); assertEquals(4, lib.unavailableEntries)
        assertEquals(HeadphonePresetResult.RENAMED, repo.renameHeadphonePreset(OutputProfile.WIRED, lib.presets.single(), "Renamed"))
        val raw = JSONObject(store.data.first()[key]!!).getJSONArray("entries")
        assertEquals(5, raw.length()); assertEquals("../not-an-id", raw.getJSONObject(1).getString("id"))
        assertEquals(2, raw.getJSONObject(2).getInt("version"))
    }
    @Test fun invalidArraysAndNonIntegralValuesAreSkippedIndependently() = testRepo { repo, store ->
        ready(repo)
        store.edit { it[key] = document(entry("Valid"), entry("Long").put("levels", JSONArray(List(129) { 0 })),
            entry("Mismatch").put("frequencies", JSONArray(listOf(100))), entry("Decimal").put("levels", JSONArray(listOf(1.5, 0))),
            entry("Zero frequency").put("frequencies", JSONArray(listOf(0, 1000)))) }
        assertEquals(listOf("Valid"), repo.headphonePresets.first().presets.map { it.name })
    }
    @Test fun unsupportedOversizedMalformedAndWrongPreferenceTypesRemainUntouched() = testRepo { repo, store ->
        ready(repo)
        for (raw in listOf("{bad", " ".repeat(65_537), "{\"version\":2,\"entries\":[]}", "[".repeat(20) + "]".repeat(20))) {
            store.edit { it[key] = raw }; val before = store.data.first()
            assertFalse(repo.headphonePresets.first().writable)
            assertEquals(HeadphonePresetResult.STORAGE_UNAVAILABLE, repo.saveHeadphonePreset(OutputProfile.WIRED, repo.settings.first(), caps, "No"))
            assertEquals(before, store.data.first())
        }
        store.edit { it[booleanPreferencesKey(key.name)] = true }; val before = store.data.first()
        assertFalse(repo.headphonePresets.first().writable)
        assertEquals(HeadphonePresetResult.STORAGE_UNAVAILABLE, repo.saveHeadphonePreset(OutputProfile.WIRED, repo.settings.first(), caps, "No"))
        assertEquals(before, store.data.first())
    }
    @Test fun namesAndLibraryCountAreBoundedButNamesAreNotIdentifiers() = testRepo { repo, _ ->
        ready(repo)
        for (name in listOf(" ", "x".repeat(41), "bad\nname")) assertEquals(HeadphonePresetResult.INVALID_NAME, repo.saveHeadphonePreset(OutputProfile.WIRED, repo.settings.first(), caps, name))
        save(repo, "x".repeat(40))
        repeat(31) { save(repo, "Duplicate") }
        assertEquals(32, repo.headphonePresets.first().presets.size)
        assertEquals(HeadphonePresetResult.LIMIT_REACHED, repo.saveHeadphonePreset(OutputProfile.WIRED, repo.settings.first(), caps, "Full"))
    }
    @Test fun concurrentSavesAndUnrelatedRenamesNeverLoseEntries() = testRepo { repo, _ ->
        ready(repo)
        coroutineScope { repeat(12) { n -> launch { assertEquals(HeadphonePresetResult.SAVED, repo.saveHeadphonePreset(OutputProfile.WIRED, repo.settings.first(), caps, "Preset $n")) } } }
        val all = repo.headphonePresets.first().presets
        assertEquals(12, all.size); assertEquals(12, all.map { it.id }.toSet().size)
        coroutineScope { all.forEach { p -> launch { assertEquals(HeadphonePresetResult.RENAMED, repo.renameHeadphonePreset(OutputProfile.WIRED, p, "Updated ${p.name}")) } } }
        assertEquals(all.map { it.id }.toSet(), repo.headphonePresets.first().presets.map { it.id }.toSet())
    }
    @Test fun libraryAndActiveCustomRequestSurviveAppStoreRecreation() = runBlocking {
        val file = temp.root.resolve("recreation.preferences_pb"); var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            var repo = AudioEnhancementsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
            ready(repo); val p = save(repo); repo.selectProfile(OutputProfile.BLUETOOTH); repo.updateCapabilities(caps)
            assertEquals(HeadphonePresetResult.COPIED_TO_PROFILE, repo.applyHeadphonePreset(OutputProfile.BLUETOOTH, p, caps))
            val profiles = repo.profiles.first(); val library = repo.headphonePresets.first()
            scope.coroutineContext[Job]!!.cancelAndJoin(); scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            repo = AudioEnhancementsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
            assertEquals(profiles, repo.profiles.first()); assertEquals(library, repo.headphonePresets.first())
            assertNull(repo.capabilities.value); assertEquals(EqualizerApplicationStatus.DISABLED, repo.applicationState.value.status)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
    @Test fun ioFailuresReturnUnavailableWithoutMutatingRequestedConfiguration() = testRepo { repo, store ->
        ready(repo); val before = repo.profiles.first()
        val failing = AudioEnhancementsRepository(object : DataStore<Preferences> {
            override val data = store.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = throw IOException("fixture")
        })
        failing.updateCapabilities(caps)
        assertEquals(HeadphonePresetResult.STORAGE_UNAVAILABLE, failing.saveHeadphonePreset(OutputProfile.WIRED, repo.settings.first(), caps, "No"))
        assertEquals(before, repo.profiles.first())
    }
}
