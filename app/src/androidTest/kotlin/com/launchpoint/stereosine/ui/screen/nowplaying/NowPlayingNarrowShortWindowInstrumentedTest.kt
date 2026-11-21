package com.launchpoint.stereosine.ui.screen.nowplaying

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.launchpoint.stereosine.data.lyrics.LyricsResult
import com.launchpoint.stereosine.data.model.Song
import com.launchpoint.stereosine.playback.NowPlayingState
import com.launchpoint.stereosine.ui.testing.FixedViewport
import com.launchpoint.stereosine.ui.testing.visibleBounds
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * UI-READABILITY-02B-B: the short-window Now Playing layout at the narrow content widths around its thresholds (320 dp and the
 * 360-400 dp band, content area ~232 dp tall). At these widths the five-button transport used to be clipped beside the artwork
 * (Next / Repeat unreachable) or the title was lost. Every control must be inside its viewport after scrolling, and the
 * transport must keep its full button width.
 */
@RunWith(Parameterized::class)
class NowPlayingNarrowShortWindowInstrumentedTest(private val widthDp: Int, private val fontScale: Float) {

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}dp font={1}")
        fun cases() = listOf(320, 360, 392, 400, 420).flatMap { w -> listOf(1.0f, 2.0f).map { arrayOf<Any>(w, it) } }
    }

    @get:Rule val rule = createComposeRule()

    private val song = Song(1, "Normal Song One", "Normal Artist", "Normal Album", 1, 78_000, "content://x/1", 0, 1, 2024)

    private fun show() {
        val state = NowPlayingState(
            song = song, isPlaying = true, presentedAsPlaying = true,
            queue = listOf(song), currentIndex = 0, isSeekable = true, durationMs = 78_000, positionMs = 32_000,
        )
        rule.setContent {
            FixedViewport(widthDp, 232, fontScale) {
                NowPlayingContent(
                    state = state,
                    onPrevious = {}, onTogglePlayPause = {}, onNext = {}, onToggleShuffle = {}, onCycleRepeatMode = {},
                    onSeek = {},
                    lyrics = LyricsResult.Loading,
                    showLyricsOverlay = false,
                    onToggleLyrics = {}, onEditLyrics = {}, onOpenTrackDetails = {}, onOpenAlbum = {}, onOpenArtist = {},
                    onOpenQueue = {},
                    quickActions = nowPlayingQuickActions(hasSong = true, isExternalAudio = false, isFavorite = false, sleepTimerActive = false),
                    onQuickAction = {},
                    modifier = Modifier,
                )
            }
        }
    }

    @Test fun identity_and_every_control_are_reachable_inside_the_window() {
        show()
        val density = rule.density.density
        rule.onNodeWithText("Normal Song One").assertIsDisplayed()
        rule.onNodeWithText("Normal Artist").assertIsDisplayed()
        rule.onNodeWithContentDescription("Sleep timer").assertIsDisplayed()
        listOf("Turn shuffle on", "Previous", "Pause", "Next", "Turn repeat all on", "Open queue").forEach { description ->
            val node = rule.onNodeWithContentDescription(description)
            node.performScrollTo().assertIsDisplayed()
            val b = node.visibleBounds()
            assertTrue("$description (${b.left}..${b.right}) must lie inside the $widthDp dp window", b.left >= 0f && b.right <= widthDp * density + 1f)
            if (description != "Open queue") {
                assertTrue("$description button ${b.width}x${b.height}px must keep a tappable size", b.width >= 30f * density && b.height >= 30f * density)
            }
        }
    }
}
