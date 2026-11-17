package com.launchpoint.stereosine.ui.screen.settings

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
        rule.runOnIdle { repo.updateApplicationState(EqualizerApplicationState(value)) }
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
        rule.onAllNodes(sliders).assertCountEquals(2)
        rule.onNodeWithText("Preset").performClick()
        rule.onAllNodesWithText("Deep Bass").assertCountEquals(0) // existing known-band compatibility rule
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(custom, requested().eqCustomBandLevels)
    }

    @After fun cleanup() {
        rule.runOnIdle { stores.forEach { it.clear() } }
        runBlocking { scopes.forEach { it.coroutineContext[Job]!!.cancelAndJoin() } }
        files.forEach { check(it.delete() || !it.exists()) }
    }
}
