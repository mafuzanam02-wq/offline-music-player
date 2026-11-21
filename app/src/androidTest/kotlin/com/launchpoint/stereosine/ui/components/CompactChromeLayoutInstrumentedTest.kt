package com.launchpoint.stereosine.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import com.launchpoint.stereosine.data.model.Song
import com.launchpoint.stereosine.playback.NowPlayingState
import com.launchpoint.stereosine.ui.testing.ForcedFontScale
import com.launchpoint.stereosine.ui.testing.visibleBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * UI-READABILITY-02B / F-01: the persistent chrome (top bar, MiniPlayer, bottom navigation) must leave a usable list area on a
 * compact-landscape window while something is playing, without removing any control, and must not change portrait.
 *
 * The host mirrors the Songs screen: a Scaffold whose bottomBar is `Column { MiniPlayer; PrimaryNavigationBar }`. Both chrome
 * composables are the real production ones. Bounds are clipped (visible) bounds, not un-clipped layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(Parameterized::class)
class CompactChromeLayoutInstrumentedTest(private val fontScale: Float) {

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "font={0}")
        fun scales() = listOf(1.0f, 1.5f, 2.0f)
    }

    @get:Rule val rule = createComposeRule()

    /** The real window decides: run under `wm size` 1700x880 (618x320 dp, compact) and 1080x2340 / 880x1760 (portrait). */
    private val heightDp get() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.screenHeightDp
    private val compact get() = heightDp < 480
    private var toggles = 0
    private var previous = 0
    private var next = 0
    private var shuffle = 0
    private var repeat = 0
    private var opened = 0
    private val song = Song(1, "Normal Song One", "Normal Artist", "Normal Album", 1, 78_000, "content://x/1", 0, 1, 2024)

    private fun show() {
        val playing = NowPlayingState(song = song, isPlaying = true, presentedAsPlaying = true)
        rule.setContent {
            ForcedFontScale(fontScale) {
                Scaffold(
                    topBar = { TopAppBar(title = { Text("Browse") }) },
                    bottomBar = {
                        Column {
                            MiniPlayer(
                                nowPlaying = playing,
                                onOpenNowPlaying = { opened++ },
                                onTogglePlayPause = { toggles++ },
                                onPrevious = { previous++ },
                                onNext = { next++ },
                                onToggleShuffle = { shuffle++ },
                                onCycleRepeatMode = { repeat++ },
                                applyNavigationBarsPadding = false,
                            )
                            PrimaryNavigationBar(PrimaryDestination.SONGS, {}, {}, {}, {})
                        }
                    },
                ) { inner ->
                    Box(Modifier.fillMaxSize().padding(inner)) {
                        LazyColumn(Modifier.fillMaxSize().testTag("list")) {
                            items((0 until 30).toList()) { Text("Row $it", Modifier.fillMaxWidth().height(72.dp)) }
                        }
                    }
                }
            }
        }
    }

    @Test fun list_keeps_a_usable_viewport_beside_the_chrome() {
        show()
        val density = rule.density.density
        val list = rule.onNodeWithTag("list").visibleBounds()
        val oneRowPx = 72 * density
        assertTrue("list viewport ${list.height}px must hold at least one 72 dp row ($oneRowPx px)", list.height >= oneRowPx)
        // the chrome sits below the list, never over it
        val miniTop = rule.onNodeWithContentDescription("Pause").visibleBounds().top
        assertTrue("MiniPlayer controls (top $miniTop) must not overlap the list (bottom ${list.bottom})", miniTop >= list.bottom - 1f)
        val navTop = rule.onNodeWithText("Library", useUnmergedTree = true).visibleBounds().top
        assertTrue("bottom navigation (top $navTop) must be below the MiniPlayer controls", navTop >= rule.onNodeWithContentDescription("Pause").visibleBounds().bottom - 1f)
    }

    @Test fun rows_scroll_and_the_last_row_is_reachable() {
        show()
        rule.onNodeWithTag("list").performScrollToIndex(29)
        rule.onNodeWithText("Row 29").assertIsDisplayed()
    }

    @Test fun every_transport_control_and_destination_survives_at_standard_size() {
        show()
        val density = rule.density.density
        listOf("Turn shuffle on", "Previous", "Pause", "Next", "Turn repeat all on").forEach { description ->
            val node = rule.onNodeWithContentDescription(description)
            node.assertIsDisplayed()
            val b = node.visibleBounds()
            assertTrue("$description button ${b.width}x${b.height} must keep the standard 40 dp size", b.width >= 39f * density && b.height >= 39f * density)
        }
        listOf("Home", "Songs", "Library", "Insights").forEach { rule.onNodeWithText(it, useUnmergedTree = true).assertIsDisplayed() }
    }

    @Test fun transport_buttons_fire_exactly_once_and_the_row_still_opens_now_playing() {
        show()
        rule.onNodeWithContentDescription("Pause").performClick()
        rule.onNodeWithContentDescription("Previous").performClick()
        rule.onNodeWithContentDescription("Next").performClick()
        rule.onNodeWithContentDescription("Turn shuffle on").performClick()
        rule.onNodeWithContentDescription("Turn repeat all on").performClick()
        rule.onNodeWithText("Normal Song One", substring = true).performClick()
        assertEquals(listOf(1, 1, 1, 1, 1, 1), listOf(toggles, previous, next, shuffle, repeat, opened))
    }

    @Test fun chrome_presentation_matches_the_window_class() {
        show()
        val pause = rule.onNodeWithContentDescription("Pause").visibleBounds()
        val text = rule.onNodeWithText("Normal Song One", substring = true).visibleBounds()
        val textBottom = rule.onAllNodesWithText("Normal Artist", substring = true)[0].visibleBounds().bottom
        val centreY = (pause.top + pause.bottom) / 2f
        if (compact) {
            assertTrue("compact: transport shares the text row (pause centre $centreY, text ${text.top}..$textBottom)", centreY in text.top..textBottom)
        } else {
            assertTrue("portrait: transport stays on its own row below the text (pause top ${pause.top}, text bottom $textBottom)", pause.top >= textBottom - 1f)
        }
    }
}
