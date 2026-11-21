package com.launchpoint.stereosine.ui.screen.nowplaying

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.launchpoint.stereosine.data.lyrics.LyricsResult
import com.launchpoint.stereosine.data.model.Song
import com.launchpoint.stereosine.playback.NowPlayingState
import com.launchpoint.stereosine.ui.components.PrimaryDestination
import com.launchpoint.stereosine.ui.components.PrimaryNavigationBar
import com.launchpoint.stereosine.ui.testing.ForcedFontScale
import com.launchpoint.stereosine.ui.testing.visibleBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * UI-READABILITY-02B / F-02: on a short window (compact landscape) Now Playing keeps the track identity, the Sleep Timer entry,
 * the transport and the Queue entry reachable, clear of the persistent navigation, and unchanged in portrait. The host is a
 * Scaffold with the real bottom navigation around the real [NowPlayingContent]. Bounds are clipped (visible) bounds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(Parameterized::class)
class NowPlayingShortWindowInstrumentedTest(private val fontScale: Float) {

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "font={0}")
        fun scales() = listOf(1.0f, 1.5f, 2.0f)
    }

    @get:Rule val rule = createComposeRule()

    /** The real window decides: run under `wm size` 1700x880 (618x320 dp, short) and 1080x2340 / 880x1760 (portrait). */
    private val heightDp get() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.screenHeightDp
    private val short get() = heightDp < 480
    private var previous = 0
    private var toggle = 0
    private var next = 0
    private var shuffle = 0
    private var repeat = 0
    private var queue = 0
    private val timerTaps = mutableListOf<NowPlayingQuickActionType>()
    private val song = Song(1, "Normal Song One", "Normal Artist", "Normal Album", 1, 78_000, "content://x/1", 0, 1, 2024)

    private fun show() {
        val state = NowPlayingState(
            song = song,
            isPlaying = true,
            presentedAsPlaying = true,
            queue = listOf(song, song.copy(id = 2, title = "Midnight City", artist = "M83")),
            currentIndex = 0,
            isSeekable = true,
            durationMs = 78_000,
            positionMs = 32_000,
        )
        rule.setContent {
            ForcedFontScale(fontScale) {
                Scaffold(
                    topBar = { TopAppBar(title = { Text("Now Playing") }) },
                    bottomBar = { PrimaryNavigationBar(null, {}, {}, {}, {}) },
                ) { inner ->
                    NowPlayingContent(
                        state = state,
                        onPrevious = { previous++ },
                        onTogglePlayPause = { toggle++ },
                        onNext = { next++ },
                        onToggleShuffle = { shuffle++ },
                        onCycleRepeatMode = { repeat++ },
                        onSeek = {},
                        lyrics = LyricsResult.Loading,
                        showLyricsOverlay = false,
                        onToggleLyrics = {},
                        onEditLyrics = {},
                        onOpenTrackDetails = {},
                        onOpenAlbum = {},
                        onOpenArtist = {},
                        onOpenQueue = { queue++ },
                        quickActions = nowPlayingQuickActions(
                            hasSong = true, isExternalAudio = false, isFavorite = false, sleepTimerActive = false,
                        ),
                        onQuickAction = { timerTaps += it },
                        modifier = Modifier.padding(inner),
                    )
                }
            }
        }
    }

    /** Short windows scroll the right pane to reach a control; portrait has nothing to scroll and shows it in place. */
    private fun androidx.compose.ui.test.SemanticsNodeInteraction.reveal() =
        if (short) this.performScrollTo() else this

    /** Top edge of the persistent bottom navigation: the "Home" label sits inside it. */
    private fun navTop() = rule.onNodeWithText("Home", useUnmergedTree = true).visibleBounds().top

    @Test fun track_identity_and_the_sleep_timer_entry_are_visible_without_scrolling() {
        show()
        rule.onNodeWithText("Normal Song One").assertIsDisplayed()
        rule.onNodeWithText("Normal Artist").assertIsDisplayed()
        rule.onNodeWithContentDescription("Sleep timer").assertIsDisplayed()
        rule.onNodeWithContentDescription("Sleep timer").performClick()
        assertEquals(listOf(NowPlayingQuickActionType.Timer), timerTaps)
    }

    @Test fun queue_and_every_transport_control_are_reachable_by_scrolling_and_clear_of_the_navigation() {
        show()
        listOf("Previous", "Pause", "Next", "Turn shuffle on", "Turn repeat all on", "Open queue").forEach { description ->
            val node = rule.onNodeWithContentDescription(description)
            node.reveal().assertIsDisplayed()
            val bottom = node.visibleBounds().bottom
            assertTrue("$description (bottom $bottom) must sit above the bottom navigation (top ${navTop()})", bottom <= navTop() + 1f)
        }
    }

    @Test fun transport_and_queue_actions_fire_exactly_once() {
        show()
        rule.onNodeWithContentDescription("Pause").reveal().performClick()
        rule.onNodeWithContentDescription("Previous").reveal().performClick()
        rule.onNodeWithContentDescription("Next").reveal().performClick()
        rule.onNodeWithContentDescription("Turn shuffle on").reveal().performClick()
        rule.onNodeWithContentDescription("Turn repeat all on").reveal().performClick()
        rule.onNodeWithContentDescription("Open queue").reveal().performClick()
        assertEquals(listOf(1, 1, 1, 1, 1, 1), listOf(toggle, previous, next, shuffle, repeat, queue))
    }

    @Test fun layout_class_matches_the_window() {
        show()
        val art = rule.onNodeWithContentDescription("Sleep timer").visibleBounds()
        val title = rule.onNodeWithText("Normal Song One").visibleBounds()
        if (short) {
            assertTrue("short window: artwork sits left of the title (art right ${art.right}, title left ${title.left})", art.right <= title.left + 1f)
        } else {
            assertTrue("portrait: artwork stays above the title (art bottom ${art.bottom}, title top ${title.top})", art.bottom <= title.top + 1f)
            rule.onNodeWithContentDescription("Open queue").assertIsDisplayed() // unchanged: reachable without scrolling
        }
    }
}
