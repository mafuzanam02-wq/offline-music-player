package com.launchpoint.stereosine.ui.navigation

import android.content.Context
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.runtime.key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.launchpoint.stereosine.BuildConfig
import com.launchpoint.stereosine.MainActivity
import com.launchpoint.stereosine.data.playback.PlaybackSessionRepository
import com.launchpoint.stereosine.data.settings.AppSettingsRepository
import com.launchpoint.stereosine.data.settings.StartupDestination
import com.launchpoint.stereosine.ui.theme.StereoSineTheme
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual app graph and screen callbacks; startup preferences use disposable stores, never a data reset. */
class PrimaryNavigationInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private lateinit var nav: NavHostController
    private val stores = mutableListOf<ViewModelStore>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val files = mutableListOf<File>()
    private lateinit var settings: AppSettingsRepository
    private val tabs = linkedMapOf("Home" to "home", "Songs" to "songs", "Library" to "library", "Insights" to "settings/statistics")
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun launch(start: StartupDestination = StartupDestination.HOME) {
        val file = File(context.cacheDir, "nav-01-${System.nanoTime()}.preferences_pb").also(files::add)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also(scopes::add)
        val data = PreferenceDataStoreFactory.create(scope = scope) { file }
        val repository = AppSettingsRepository(data)
        settings = repository
        runBlocking {
            repository.setStartupDestination(start)
            repository.setHasCompletedOnboarding(true)
            repository.setLastCompletedOnboardingVersion(StereoSineNavViewModel.CURRENT_ONBOARDING_VERSION)
            repository.setLastSeenChangelogVersion(BuildConfig.VERSION_CODE)
        }
        val token = System.nanoTime()
        rule.activity.runOnUiThread {
            val vm = StereoSineNavViewModel(repository, PlaybackSessionRepository(data))
            ViewModelStore().also { it.put("navigation", vm); stores += it }
            rule.activity.setContent {
                StereoSineTheme {
                    key(token) {
                        val controller = rememberNavController()
                        nav = controller
                        StereoSineNavGraph(navController = controller, viewModel = vm)
                    }
                }
            }
        }
        val route = when (start) {
            StartupDestination.HOME, StartupDestination.NOW_PLAYING -> "home" // isolated store has no saved session
            StartupDestination.SONGS -> "songs"
            StartupDestination.LIBRARY -> "library"
            StartupDestination.SETTINGS -> "settings"
        }
        waitRoute(route)
    }

    private fun matcher(label: String) = hasText(label) and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected) and hasClickAction()

    private fun tab(label: String) = rule.onNode(matcher(label))

    private fun waitRoute(route: String) {
        rule.waitUntil(20_000) { ::nav.isInitialized && nav.currentDestination?.route == route }
        rule.waitForIdle()
    }

    private fun click(label: String) {
        tab(label).assertIsDisplayed().performClick()
        waitRoute(tabs.getValue(label))
        tab(label).assertIsSelected()
    }

    private fun routes() = nav.currentBackStack.value.mapNotNull { it.destination.route }

    @Test fun singleTapSequencesAndRepeatedSelectionPreserveTheEntryAndUiState() {
        launch()
        for (label in listOf("Songs", "Home", "Library", "Home", "Insights", "Home", "Songs", "Library", "Insights", "Home")) click(label)
        for (label in tabs.keys) {
            click(label)
            val entry = nav.currentBackStackEntry!!
            val events = AtomicInteger()
            rule.runOnIdle { nav.addOnDestinationChangedListener { _, _, _ -> events.incrementAndGet() } }
            val before = events.get()
            repeat(5) { tab(label).performClick() }
            rule.runOnIdle {
                assertSame(entry, nav.currentBackStackEntry)
                assertEquals(before, events.get())
            }
        }
        repeat(3) {
            for (label in tabs.keys) {
                click(label)
                val stack = routes()
                assertEquals(stack.distinct(), stack)
                assertTrue(stack.size <= 2)
            }
        }
    }

    private fun openSearch(label: String = "Search") {
        rule.onNodeWithContentDescription(label).performClick()
        waitRoute(Screen.GlobalSearch.route)
    }

    private fun closeSearch() {
        rule.onNodeWithContentDescription("Close search").performClick()
    }

    @Test fun allLibrarySearchButtonsReturnToTheirOriginalBackStackEntry() {
        launch()
        val origins = listOf(
            Screen.Home, Screen.Songs, Screen.Library, Screen.Albums, Screen.Artists,
            Screen.Folders, Screen.Playlists, Screen.SettingsStatistics, Screen.PlaylistDetails,
        )
        for (origin in origins) {
            if (nav.currentDestination?.route != origin.route) {
                rule.runOnIdle {
                    nav.navigate(if (origin == Screen.PlaylistDetails) Screen.PlaylistDetails.createRoute(Long.MAX_VALUE) else origin.route)
                }
            }
            waitRoute(origin.route)
            val originEntry = nav.currentBackStackEntry
            val originStack = routes()
            openSearch(if (origin == Screen.Library) "Search library" else "Search")
            rule.onNode(hasSetTextAction()).performTextInput("uncommitted")
            rule.onNodeWithContentDescription("Clear search").performClick()
            rule.onNodeWithText("uncommitted").assertDoesNotExist()
            rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
            waitRoute(origin.route)
            assertSame(originEntry, nav.currentBackStackEntry)
            assertEquals(originStack, routes())
        }
    }

    @Test fun resultNavigationCommitsAcrossOriginsAndReusesTheLaunchingPlaylist() {
        launch()
        val title = "Search fixture ${System.nanoTime()}"
        rule.runOnIdle { nav.navigate(Screen.Playlists.route) }
        waitRoute(Screen.Playlists.route)
        rule.onNodeWithContentDescription("Create playlist").performClick()
        rule.onNode(hasSetTextAction()).performTextInput(title)
        rule.onNodeWithText("Create").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(title).performClick()
        waitRoute(Screen.PlaylistDetails.route)
        val id = checkNotNull(nav.currentBackStackEntry?.arguments?.getLong("playlistId"))
        try {
            rule.runOnIdle { nav.popBackStack(Screen.Home.route, inclusive = false) }
            waitRoute(Screen.Home.route)
            click("Songs")
            val songsEntry = nav.currentBackStackEntry
            openSearch()
            val searchEntry = nav.currentBackStackEntry
            rule.onNode(hasSetTextAction()).performTextInput(title)
            rule.onNodeWithContentDescription("Clear search").performClick()
            rule.onNodeWithText(title).assertDoesNotExist()
            rule.onNode(hasSetTextAction()).performTextInput("unrelated text")
            rule.onNode(hasSetTextAction()).performTextReplacement(title)
            val result = hasText(title) and hasClickAction() and !hasSetTextAction()
            rule.waitUntil(10_000) { rule.onAllNodes(result).fetchSemanticsNodes().size == 1 }
            rule.onNode(result).performClick()
            waitRoute(Screen.PlaylistDetails.route)
            val playlistEntry = nav.currentBackStackEntry
            assertEquals(id, playlistEntry?.arguments?.getLong("playlistId"))
            rule.runOnIdle { nav.popBackStack() }
            waitRoute(Screen.GlobalSearch.route)
            assertSame(searchEntry, nav.currentBackStackEntry)
            rule.onNode(hasSetTextAction()).assertTextContains(title)
            closeSearch()
            waitRoute(Screen.Songs.route)
            assertSame(songsEntry, nav.currentBackStackEntry)

            rule.runOnIdle { nav.navigate(Screen.PlaylistDetails.createRoute(id)) }
            waitRoute(Screen.PlaylistDetails.route)
            val existingPlaylistEntry = nav.currentBackStackEntry
            openSearch()
            rule.waitUntil(10_000) { rule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText(title).performClick()
            rule.waitUntil(10_000) { rule.onAllNodes(result).fetchSemanticsNodes().size == 1 }
            rule.onNode(result).performClick()
            waitRoute(Screen.PlaylistDetails.route)
            assertSame(existingPlaylistEntry, nav.currentBackStackEntry)

            openSearch()
            val remove = hasContentDescription("Remove $title from recent searches")
            rule.waitUntil(10_000) { rule.onAllNodes(remove).fetchSemanticsNodes().size == 1 }
            rule.onNode(remove).performClick()
            rule.waitUntil(10_000) { rule.onAllNodes(remove).fetchSemanticsNodes().isEmpty() }
            closeSearch()
            waitRoute(Screen.PlaylistDetails.route)
            rule.runOnIdle { nav.popBackStack() }
            waitRoute(Screen.Songs.route)
            openSearch()
            rule.onNodeWithText(title).assertDoesNotExist()
        } finally {
            rule.runOnIdle { nav.navigate(Screen.PlaylistDetails.createRoute(id)) }
            waitRoute(Screen.PlaylistDetails.route)
            rule.onNodeWithContentDescription("More options").performClick()
            rule.onNodeWithText("Delete playlist").performClick()
            rule.onNodeWithText("Delete playlist").performClick()
        }
    }

    @Test fun nonHomeStartupAndPreferencesRemainIntact() {
        for (start in listOf(StartupDestination.SONGS, StartupDestination.LIBRARY, StartupDestination.SETTINGS, StartupDestination.NOW_PLAYING)) {
            launch(start)
            if (start == StartupDestination.SETTINGS) {
                rule.onAllNodes(matcher("Home")).assertCountEquals(0)
                // The same secondary navigation used by the Settings Insights row.
                rule.runOnIdle { nav.navigate("settings/statistics") }
                waitRoute("settings/statistics")
            }
            click("Home")
            assertEquals(start, runBlocking { settings.startupDestination.first() })
        }
    }

    @Test fun outgoingSelectedTabOverridesTheActualDestinationWithOneTap() {
        launch()
        for ((from, to) in listOf("Home" to "Songs", "Songs" to "Library", "Library" to "Insights", "Insights" to "Home")) {
            if (nav.currentDestination?.route != tabs.getValue(from)) click(from)
            rule.mainClock.autoAdvance = false
            try {
                tab(to).performClick()
                rule.mainClock.advanceTimeByFrame()
                assertEquals(tabs.getValue(to), nav.currentDestination?.route)
                // During NavHost's fade, the outgoing screen still exposes its selected tab.
                rule.onAllNodes(matcher(from) and isSelected()).onFirst().performClick()
                assertEquals("Outgoing $from bar must override $to immediately", tabs.getValue(from), nav.currentDestination?.route)
            } finally {
                rule.mainClock.autoAdvance = true
                rule.waitForIdle()
            }
            tab(from).assertIsSelected()
        }
    }

    @Test fun ordinarySecondaryBackAndHiddenBarsStayUnchanged() {
        launch()
        rule.onNodeWithContentDescription("Settings").performClick()
        waitRoute("settings")
        rule.onAllNodes(matcher("Home")).assertCountEquals(0)
        assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(1))
        waitRoute("home")
        click("Library")
        rule.onNodeWithText("Browse every track with search and A-Z jump.").performClick()
        waitRoute("songs")
        assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(1))
        waitRoute("library")
        click("Home")
        click("Songs"); click("Library"); click("Insights")
        assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(1))
        waitRoute("home")
        assertEquals(listOf("home"), routes())
    }

    @Test fun primarySwitchesAndADeeperBarDoNotInterruptRealPlayback() {
        launch()
        val wav = File(context.cacheDir, "nav-01-silence.wav").also(files::add)
        val dataSize = 120 * 16_000 * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(dataSize).array()
        wav.outputStream().use { out -> out.write(header); repeat(120) { out.write(ByteArray(32_000)) } }
        val player = rule.activity.playerController
        rule.runOnIdle { player.playExternalUri(Uri.fromFile(wav), "NAV-01 synthetic silence") }
        try {
            rule.waitUntil(30_000) { player.nowPlayingState.value.isPlaying }
            val queue = player.nowPlayingState.value.queue
            val position = player.nowPlayingState.value.positionMs
            for (label in listOf("Songs", "Library", "Insights", "Home")) {
                click(label)
                assertTrue(player.nowPlayingState.value.isPlaying)
                assertEquals(queue, player.nowPlayingState.value.queue)
            }
            click("Songs")
            openSearch()
            rule.onNode(hasSetTextAction()).performTextInput("playback-navigation-fixture")
            closeSearch()
            waitRoute(Screen.Songs.route)
            assertTrue(player.nowPlayingState.value.isPlaying)
            assertEquals(queue, player.nowPlayingState.value.queue)

            rule.runOnIdle { nav.navigate("now_playing") }
            waitRoute("now_playing")
            click("Library")
            assertTrue(player.nowPlayingState.value.isPlaying)
            assertEquals(queue, player.nowPlayingState.value.queue)
            assertTrue(player.nowPlayingState.value.positionMs >= position)
        } finally {
            rule.runOnIdle { if (player.nowPlayingState.value.isPlaying) player.togglePlayPause() }
        }
    }

    @After fun releaseFixtureStores() {
        rule.mainClock.autoAdvance = true
        rule.runOnIdle { stores.forEach { it.clear() } }
        scopes.forEach { it.cancel() }
        files.forEach { it.delete() }
    }
}
