package com.launchpoint.stereosine.ui.screen.settings

import androidx.compose.ui.geometry.Offset
import android.media.AudioManager
import android.net.Uri
import androidx.compose.runtime.*
import androidx.media3.common.MediaItem
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import com.launchpoint.stereosine.playback.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.launchpoint.stereosine.MainActivity
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.launchpoint.stereosine.data.settings.*
import com.launchpoint.stereosine.ui.theme.StereoSineTheme
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real screen/ViewModel/repository, isolated preferences; no app-data reset or actual EQ effect needed. */
class EqPresetAccessInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val stores = mutableListOf<ViewModelStore>()
    private val files = mutableListOf<File>()
    private lateinit var repo: AudioEnhancementsRepository
    private val custom = listOf(-300, 0, 200, 100, -100)
    private val sliders = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
    private lateinit var scope: CoroutineScope
    private lateinit var file: File

    private fun repository(): AudioEnhancementsRepository {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also(scopes::add)
        return AudioEnhancementsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
    }
    private fun viewModel(): SettingsViewModel {
        lateinit var live: SettingsViewModel
        rule.runOnIdle { live = ViewModelProvider(rule.activity)[SettingsViewModel::class.java] }
        // Read-only test wiring: reuse the activity's real dependencies while replacing only EQ storage.
        // This avoids adding a production injection API or writing the application's actual preferences.
        fun <T> dependency(name: String): T {
            val field = SettingsViewModel::class.java.getDeclaredField(name).also { it.isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            return field.get(live) as T
        }
        return SettingsViewModel(
            dependency("backupRepository"), dependency("autoBackupRepository"), dependency("autoBackupWorkScheduler"),
            dependency("appSettingsRepository"), dependency("appIconAliasManager"), dependency("scanSettingsRepository"),
            dependency("resumeBehaviorRepository"), repo, dependency("songRepository"), dependency("playerController"),
        )
            .also { vm -> ViewModelStore().also { it.put("eq", vm); stores += it } }
    }
    private fun start() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = File(context.cacheDir, "listen-01a-${System.nanoTime()}.preferences_pb").also(files::add)
        repo = repository()
        runBlocking { repo.setEqCustomBandLevels(custom); repo.setEqEnabled(true) }
        val vm = viewModel()
        rule.runOnIdle { rule.activity.setContent { StereoSineTheme { SettingsEqualizerScreen({}, vm) } } }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Preset").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Preset").assertIsDisplayed()
    }
    private fun requested() = runBlocking { repo.settings.first() }
    private fun status(value: EqualizerApplicationStatus) {
        rule.runOnIdle { repo.updateApplicationState(EqualizerApplicationState(value, requested = requested())) }
    }
    private fun noHardwareEditors() {
        rule.onAllNodes(sliders).assertCountEquals(0)
        rule.onAllNodesWithText("System").assertCountEquals(0)
        rule.onAllNodesWithText("Normal").assertCountEquals(0)
    }

    @Test fun savedSelectionsStayAccessibleWhilePendingUnavailableOrFailed() {
        start()
        rule.onNodeWithText("Equalizer application pending.").assertIsDisplayed()
        noHardwareEditors()
        rule.onNodeWithText("Preset").performClick()
        rule.onNodeWithText("Reset EQ").assertIsDisplayed().performClick()
        rule.waitUntil(10_000) { requested().eqPresetType == EqPresetType.FLAT }
        assertEquals(EqPresetType.FLAT, requested().eqPresetType)
        assertEquals(custom, requested().eqCustomBandLevels)
        status(EqualizerApplicationStatus.UNAVAILABLE)
        rule.onNodeWithText("Equalizer is unavailable. Playback can continue.").assertIsDisplayed()
        rule.onNodeWithText("Preset").performClick()
        noHardwareEditors()
        rule.onAllNodes(hasText("Custom") and hasAnyAncestor(isDialog())).assertCountEquals(0)
        rule.onNodeWithText("Deep Bass").performScrollTo().performClick()
        rule.waitUntil(10_000) { requested().eqStereoSinePresetId == "deep-bass" }
        assertEquals("deep-bass", requested().eqStereoSinePresetId)
        assertEquals(custom, requested().eqCustomBandLevels)
        assertNull(repo.capabilities.value)
        assertEquals(EqualizerApplicationStatus.UNAVAILABLE, repo.applicationState.value.status)
        status(EqualizerApplicationStatus.FAILED)
        rule.onNodeWithText("Equalizer application could not be confirmed. Playback can continue.").assertIsDisplayed()
        rule.onNodeWithText("Preset").assertIsDisplayed()
        val saved = requested()
        // Reopen the same on-disk store after observer/repository recreation; no migration or reset.
        rule.runOnIdle { stores.forEach { it.clear() }; stores.clear() }
        runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
        repo = repository()
        assertEquals(saved, requested())
        assertEquals(custom, requested().eqCustomBandLevels)
    }

    @Test fun runtimeCapabilitiesRestoreEditorsAndDisappearWithoutStaleBands() {
        start()
        noHardwareEditors()
        val caps = EqualizerCapabilities(5, -1500, 1500, listOf(60, 230, 910, 3600, 14000), listOf(EqualizerPlatformPreset(7, "Runtime preset")))
        rule.runOnIdle { repo.updateCapabilities(caps) }
        rule.onAllNodes(sliders).assertCountEquals(5)
        rule.onNodeWithText("Preset").performClick()
        rule.onNode(hasText("Custom") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        rule.onNodeWithText("Runtime preset").performScrollTo().assertIsDisplayed()
        // Capability loss while the dialog is open removes hardware-specific choices immediately.
        rule.runOnIdle { repo.updateCapabilities(null) }
        rule.onAllNodesWithText("Runtime preset").assertCountEquals(0)
        rule.onAllNodes(hasText("Custom") and hasAnyAncestor(isDialog())).assertCountEquals(0)
        rule.onNodeWithText("Reset EQ").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        noHardwareEditors()
        assertEquals(custom, requested().eqCustomBandLevels)
        assertEquals(EqPresetType.CUSTOM, requested().eqPresetType)
        rule.runOnIdle { repo.updateCapabilities(caps.copy(bandCount = 2, centerFrequenciesHz = listOf(100, 1000))) }
        rule.onAllNodes(sliders).assertCountEquals(0) // incompatible saved curve is retained, not remapped to invented bands
        rule.onNodeWithText("Preset").performClick()
        rule.onAllNodesWithText("Deep Bass").assertCountEquals(0) // existing known-band compatibility rule
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(custom, requested().eqCustomBandLevels)
    }

    private val resetAction = "Reset Custom Bands for This Device"
    private fun openRecovery() {
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(resetAction))
        rule.onNodeWithText(resetAction).performClick()
        rule.onNodeWithText("Reset custom bands?").assertIsDisplayed()
    }

    @Test fun incompatibleRecoveryRequiresConfirmationAndRestoresOnlySelectedSliders() {
        start()
        runBlocking { repo.selectProfile(OutputProfile.SPEAKER) }
        val caps = EqualizerCapabilities(3, -1500, 1500, listOf(100, 1000, 5000), emptyList())
        rule.runOnIdle { repo.updateCapabilities(caps) }
        val before = runBlocking { repo.profiles.first() }
        noHardwareEditors()
        openRecovery()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(before, runBlocking { repo.profiles.first() })
        openRecovery()
        rule.onNodeWithText("Reset Custom Bands").performClick()
        rule.waitUntil(10_000) { requested().eqCustomBandLevels == listOf(0, 0, 0) }
        assertEquals(caps.centerFrequenciesHz, requested().eqCustomBandFrequenciesHz)
        assertTrue(requested().eqEnabled)
        rule.onAllNodes(sliders).assertCountEquals(3)
        rule.onAllNodesWithText(resetAction).assertCountEquals(0)
        val after = runBlocking { repo.profiles.first() }
        listOf(OutputProfile.GLOBAL, OutputProfile.WIRED, OutputProfile.BLUETOOTH).forEach { assertEquals(before.configurations[it], after.configurations[it]) }
        assertNotEquals(EqualizerApplicationStatus.APPLIED, repo.applicationState.value.status)
    }

    @Test fun staleRecoveryAndCapabilityLossPreserveCurvesAndCompatibleCurvesNeedNoReset() {
        start()
        runBlocking { repo.selectProfile(OutputProfile.SPEAKER) }
        val caps = EqualizerCapabilities(2, -1500, 1500, listOf(100, 1000), emptyList())
        rule.runOnIdle { repo.updateCapabilities(caps) }
        openRecovery()
        runBlocking { repo.selectProfile(OutputProfile.WIRED) }
        val before = runBlocking { repo.profiles.first() }
        rule.onNodeWithText("Reset Custom Bands").performClick()
        runBlocking { repo.selectProfile(OutputProfile.WIRED) } // serialized barrier after the queued stale confirmation
        assertEquals(before, runBlocking { repo.profiles.first() })
        openRecovery()
        rule.runOnIdle { repo.updateCapabilities(null) }
        rule.onAllNodesWithText("Reset custom bands?").assertCountEquals(0)
        rule.onAllNodesWithText(resetAction).assertCountEquals(0)
        assertEquals(before, runBlocking { repo.profiles.first() })
        rule.runOnIdle { repo.updateCapabilities(caps.copy(bandCount = 5, centerFrequenciesHz = listOf(60, 230, 910, 3600, 14000))) }
        rule.onAllNodes(sliders).assertCountEquals(5)
        rule.onAllNodesWithText(resetAction).assertCountEquals(0)
        assertEquals(custom, requested().eqCustomBandLevels)
    }

    private fun savedHeadphones() = runBlocking { repo.headphonePresets.first().presets }
    private fun scrollTo(matcher: SemanticsMatcher) {
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
    }
    private fun namePreset(name: String) {
        rule.onNode(hasSetTextAction()).performTextReplacement(name)
        rule.onNodeWithText("Save").performClick()
        rule.runOnIdle {
            (rule.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(rule.activity.window.decorView.windowToken, 0)
        }
    }
    private fun readyHeadphoneLibrary(): HeadphoneEqPreset {
        start()
        val caps = EqualizerCapabilities(5, -1500, 1500, listOf(60, 230, 910, 3600, 14000), emptyList())
        runBlocking {
            repo.selectProfile(OutputProfile.WIRED)
            repo.setEqCustomBandLevels(custom, OutputProfile.WIRED, caps.centerFrequenciesHz)
        }
        rule.runOnIdle { repo.updateCapabilities(caps) }
        assertEquals(HeadphonePresetResult.SAVED, runBlocking { repo.saveHeadphonePreset(OutputProfile.WIRED, requested(), caps, "Existing") })
        return savedHeadphones().single()
    }
    private fun showHeadphones() {
        val vm = viewModel()
        rule.runOnIdle { rule.activity.setContent { StereoSineTheme { SettingsHeadphonesScreen({}, {}, {}, vm) } } }
    }
    @Test fun headphonesOnGlobalOrSpeakerNeverSelectsOrAppliesAutomatically() {
        val saved = readyHeadphoneLibrary()
        for (profile in listOf(OutputProfile.GLOBAL, OutputProfile.SPEAKER)) {
            runBlocking { repo.selectProfile(profile) }
            if (profile == OutputProfile.GLOBAL) showHeadphones()
            rule.waitUntil(10_000) { rule.onAllNodesWithText("Selected profile: ${profile.displayName}.", substring = true).fetchSemanticsNodes().isNotEmpty() }
            val before = runBlocking { repo.profiles.first() }
            scrollTo(hasTestTag("headphone-save")); rule.onNodeWithTag("headphone-save").assertIsNotEnabled()
            scrollTo(hasTestTag("headphone-apply-${saved.id}")); rule.onNodeWithTag("headphone-apply-${saved.id}").assertIsNotEnabled()
            assertEquals(before, runBlocking { repo.profiles.first() })
        }
    }
    @Test fun cancelledOrStaleHeadphoneActionsRetainTheLibraryAndProfiles() {
        val saved = readyHeadphoneLibrary(); showHeadphones()
        scrollTo(hasTestTag("headphone-rename-${saved.id}")); rule.onNodeWithTag("headphone-rename-${saved.id}").performClick()
        runBlocking { repo.selectProfile(OutputProfile.BLUETOOTH) }
        namePreset("Stale rename")
        runBlocking { repo.selectProfile(OutputProfile.BLUETOOTH) } // serialized barrier after the queued UI mutation
        assertEquals(saved, savedHeadphones().single())
        scrollTo(hasTestTag("headphone-delete-${saved.id}")); rule.onNodeWithTag("headphone-delete-${saved.id}").performClick()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(saved, savedHeadphones().single())
        val profiles = runBlocking { repo.profiles.first() }
        rule.onNodeWithTag("headphone-delete-${saved.id}").performClick()
        assertEquals(HeadphonePresetResult.RENAMED, runBlocking { repo.renameHeadphonePreset(OutputProfile.BLUETOOTH, saved, "Changed elsewhere") })
        rule.onNodeWithText("Delete preset").performClick()
        runBlocking { repo.selectProfile(OutputProfile.BLUETOOTH) }
        assertEquals(saved.copy(name = "Changed elsewhere"), savedHeadphones().single())
        assertEquals(profiles, runBlocking { repo.profiles.first() })
        rule.runOnIdle { repo.updateCapabilities(null) }
        rule.onNodeWithTag("headphone-apply-${saved.id}").assertIsNotEnabled()
    }

    @Test
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    fun manualProfileEditsRestoreAndPlaybackAndSystemVolumeStayUnchanged() {
        start()
        val vm = viewModel()
        rule.runOnIdle {
            rule.activity.setContent {
                StereoSineTheme {
                    var page by remember { mutableStateOf("profiles") }
                    var editorReturn by remember { mutableStateOf("profiles") }
                    when (page) {
                        "eq" -> SettingsEqualizerScreen({ page = editorReturn }, vm)
                        "profiles" -> SettingsOutputProfilesScreen({ page = "settings" }, { editorReturn = "profiles"; page = "eq" }, vm)
                        "headphones" -> SettingsHeadphonesScreen({ page = "settings" }, { page = "profiles" }, { editorReturn = "headphones"; page = "eq" }, vm)
                        else -> SettingsScreen({}, { editorReturn = "settings"; page = "eq" }, {}, {}, {}, {}, {}, {}, {},
                            onOutputProfilesClick = { page = "profiles" }, onHeadphonesClick = { page = "headphones" })
                    }
                }
            }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val wav = File(context.cacheDir, "listen-02-${System.nanoTime()}.wav").also(files::add)
        val size = 300 * 16_000 * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(size).array()
        wav.outputStream().use { out -> out.write(header); repeat(300) { out.write(ByteArray(32_000)) } }
        lateinit var assembly: PlaybackAssembly
        lateinit var controller: AudioEnhancementController
        val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also(scopes::add)
        var known = false
        var enabled = false
        rule.runOnIdle {
            assembly = assemblePlayback(context, true, AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build())
            controller = AudioEnhancementController(repo, playbackScope).also { it.attach(assembly.currentPlayer, observeSettings = false) }
            playbackScope.launch {
                repo.settings.collect { request ->
                    applyEffectiveEqualizer(request, enabled,
                        updateEligibility = { known = true; enabled = it }, cancelCrossfade = {},
                        applyEffect = { controller.onSettingsChanged(it) },
                    )
                }
            }
            assembly.logicalPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(wav)))
            assembly.logicalPlayer.prepare(); assembly.logicalPlayer.play()
        }
        fun select(profile: OutputProfile) {
            rule.onNode(hasText(profile.displayName) and hasClickAction()).performClick()
            rule.waitUntil(10_000) { runBlocking { repo.profiles.first().active } == profile }
            rule.onNode(hasText(profile.displayName) and hasClickAction()).assertIsSelected()
            rule.runOnIdle { assertTrue(assembly.currentPlayer.isPlaying); assertTrue(known); assertEquals(requested().eqEnabled, enabled) }
        }
        fun edit() {
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Edit Equalizer"))
            rule.onNodeWithText("Edit Equalizer").performClick()
        }
        try {
            rule.waitUntil(20_000) {
                rule.runOnIdle { assembly.currentPlayer.isPlaying } && repo.applicationState.value.status == EqualizerApplicationStatus.APPLIED
            }
            val initialPosition = rule.runOnIdle { assembly.currentPlayer.currentPosition }
            select(OutputProfile.SPEAKER)
            edit()
            rule.onNodeWithText("Preset").performClick()
            rule.onNodeWithText("Deep Bass").performScrollTo().performClick()
            rule.waitUntil(10_000) { requested().eqStereoSinePresetId == "deep-bass" }
            rule.onNodeWithContentDescription("Back").performClick()
            select(OutputProfile.WIRED)
            assertEquals(EqPresetType.CUSTOM, requested().eqPresetType)
            edit()
            rule.onNode(hasText("Equalizer") and hasClickAction()).performClick()
            rule.waitUntil(10_000) { !requested().eqEnabled }
            rule.onNodeWithContentDescription("Back").performClick()
            select(OutputProfile.SPEAKER)
            assertTrue(requested().eqEnabled)
            assertEquals("deep-bass", requested().eqStereoSinePresetId)
            rule.waitUntil(10_000) { repo.applicationState.value.requested == requested() && repo.applicationState.value.status == EqualizerApplicationStatus.APPLIED }
            // A saved curve from another device must require an explicit reset, even at the same band count.
            val runtimeFrequencies = repo.capabilities.value!!.centerFrequenciesHz
            runBlocking { repo.setEqCustomBandLevels(custom, OutputProfile.SPEAKER, listOf(1, 2, 3, 4, 5)) }
            edit()
            openRecovery()
            rule.onNodeWithText("Reset Custom Bands").performClick()
            rule.waitUntil(10_000) { requested().eqCustomBandLevels == List(5) { 0 } }
            assertEquals(runtimeFrequencies, requested().eqCustomBandFrequenciesHz)
            rule.waitUntil(10_000) { repo.applicationState.value.requested == requested() && repo.applicationState.value.requestedConfigurationConfirmed }
            rule.onAllNodes(sliders).assertCountEquals(5)
            rule.onNodeWithContentDescription("Back").performClick()
            select(OutputProfile.BLUETOOTH)
            select(OutputProfile.GLOBAL)
            assertEquals(custom, requested().eqCustomBandLevels)
            // LISTEN-03: actual Settings entry, existing slider editor and named library under real playback.
            select(OutputProfile.WIRED); edit()
            rule.onNode(hasText("Equalizer") and hasClickAction()).performClick()
            rule.waitUntil(10_000) { requested().eqEnabled && repo.capabilities.value != null }
            val oldLevel = requested().eqCustomBandLevels.first()
            rule.onAllNodes(sliders).onFirst().performTouchInput { swipe(center, Offset(width * 0.8f, center.y), durationMillis = 300) }
            rule.waitUntil(10_000) { requested().eqCustomBandLevels.first() != oldLevel }
            rule.onNodeWithContentDescription("Back").performClick()
            rule.onNodeWithContentDescription("Back").performClick()
            rule.onNodeWithText("Headphones").performClick()
            val beforeSave = requested()
            scrollTo(hasTestTag("headphone-save")); rule.onNodeWithTag("headphone-save").assertIsEnabled().performClick()
            namePreset("Daily")
            rule.waitUntil(10_000) { savedHeadphones().size == 1 }
            val saved = savedHeadphones().single()
            assertEquals(beforeSave, requested())
            scrollTo(hasTestTag("headphone-rename-${saved.id}")); rule.onNodeWithTag("headphone-rename-${saved.id}").performClick()
            namePreset("Commute")
            rule.waitUntil(10_000) { savedHeadphones().single().name == "Commute" }
            val renamed = saved.copy(name = "Commute")
            assertEquals(renamed, savedHeadphones().single())
            scrollTo(hasTestTag("headphone-delete-${saved.id}")); rule.onNodeWithTag("headphone-delete-${saved.id}").performClick()
            rule.onNodeWithText("Cancel").performClick()
            assertEquals(renamed, savedHeadphones().single())
            edit()
            rule.onAllNodes(sliders).onFirst().performTouchInput { swipe(center, Offset(width * 0.2f, center.y), durationMillis = 300) }
            rule.waitUntil(10_000) { requested().eqCustomBandLevels != saved.levels }
            rule.onNodeWithContentDescription("Back").performClick()
            assertEquals(renamed, savedHeadphones().single())
            scrollTo(hasTestTag("headphone-apply-${saved.id}")); rule.onNodeWithTag("headphone-apply-${saved.id}").assertIsEnabled().performClick()
            rule.waitUntil(10_000) { requested().eqCustomBandLevels == saved.levels }
            assertTrue(requested().eqEnabled)
            rule.waitUntil(10_000) { repo.applicationState.value.requested == requested() && repo.applicationState.value.requestedConfigurationConfirmed }
            scrollTo(hasText("Output Profiles")); rule.onNodeWithText("Output Profiles").performClick()
            select(OutputProfile.BLUETOOTH)
            rule.onNodeWithContentDescription("Back").performClick()
            rule.onNodeWithText("Headphones").performClick()
            scrollTo(hasTestTag("headphone-apply-${saved.id}")); rule.onNodeWithTag("headphone-apply-${saved.id}").assertIsEnabled().performClick()
            rule.waitUntil(10_000) { requested().eqCustomBandLevels == saved.levels }
            rule.waitUntil(10_000) { repo.applicationState.value.requested == requested() && repo.applicationState.value.requestedConfigurationConfirmed }
            assertEquals(custom, runBlocking { repo.profiles.first().configurations.getValue(OutputProfile.GLOBAL).eqCustomBandLevels })
            val beforeDelete = runBlocking { repo.profiles.first() }
            scrollTo(hasTestTag("headphone-delete-${saved.id}")); rule.onNodeWithTag("headphone-delete-${saved.id}").performClick()
            rule.onNodeWithText("Delete preset").performClick()
            rule.waitUntil(10_000) { savedHeadphones().isEmpty() }
            assertEquals(beforeDelete, runBlocking { repo.profiles.first() })
            rule.runOnIdle {
                assertTrue(assembly.currentPlayer.isPlaying)
                assertEquals(1, assembly.currentPlayer.mediaItemCount)
                assertTrue(assembly.currentPlayer.currentPosition >= initialPosition)
            }
            assertEquals(volume, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        } finally {
            rule.runOnIdle { playbackScope.cancel(); controller.release(); assembly.engine!!.release() }
        }
    }

    @After fun cleanup() {
        rule.runOnIdle { stores.forEach { it.clear() } }
        runBlocking { scopes.forEach { it.coroutineContext[Job]!!.cancelAndJoin() } }
        files.forEach { check(it.delete() || !it.exists()) }
    }
}
