package com.launchpoint.stereosine.ui.components

import android.content.IntentSender
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import com.launchpoint.stereosine.data.model.Song
import com.launchpoint.stereosine.data.model.SongStatsSummary
import com.launchpoint.stereosine.ui.screen.playlists.PlaylistSongItem
import com.launchpoint.stereosine.ui.screen.playlists.PlaylistSongRow
import com.launchpoint.stereosine.ui.screen.smart.MostPlayedSongRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * SONG-MENU-04A. Production [MostPlayedSongRow], [PlaylistSongRow] and [SongRowWithOverflow] with the production
 * [SongDeletionHost]/[SongDeletionViewModel] and a recording backend (no file is deleted, no Android screen is shown).
 */
class SongMenuConsistencyInstrumentedTest {

    @get:Rule val rule = createComposeRule()

    private class Backend : SongDeletionBackend {
        val requested = mutableListOf<String>()
        override fun createDeleteRequest(songUri: String): IntentSender? { requested += songUri; return null }
        override suspend fun cleanUp(songId: Long, songUri: String) {}
    }

    private val backend = Backend()
    private val calls = mutableListOf<String>()
    private val viewModel by lazy { SongDeletionViewModel(SavedStateHandle(), backend, sdkInt = 34) }

    private fun song(id: Long = 1L, title: String = "Song $id") = Song(
        id = id, title = title, artist = "Artist", album = "Album", albumId = id, duration = 200_000L,
        uri = "content://test/media/$id", dateAdded = 0L, trackNumber = 1, year = 2024,
    )

    private fun host(fontScale: Float = 1f, content: @Composable () -> Unit) {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                SongDeletionHost(viewModel = viewModel, authorize = { _ -> { _ -> } }) { Column(Modifier) { content() } }
            }
        }
    }

    private fun mostPlayed(s: Song = song(), plays: Int = 12) = @Composable {
        MostPlayedSongRow(
            summary = SongStatsSummary(s, plays, 0, null, 0L),
            isCurrent = false, isFavorite = false,
            onClick = { calls += "Play" }, onPlayNext = { calls += "Next" }, onAddToQueue = { calls += "Queue" },
            onToggleFavorite = { calls += "Favorite" }, onAddToPlaylist = { calls += "Playlist" },
            onOpenDetails = { calls += "Details" }, onAlbumClick = { calls += "Album" }, onArtistClick = { calls += "Artist" },
            onShare = { calls += "Share" },
        )
    }

    private fun playlistRow(
        s: Song = song(), isFirst: Boolean = false, isLast: Boolean = false, allowMove: Boolean = true, preview: Boolean = false,
    ) = @Composable {
        PlaylistSongRow(
            entry = PlaylistSongItem(1L, s.id, 0, s), isFirst = isFirst, isLast = isLast, allowMove = allowMove,
            isCurrent = false, isFavorite = false, isDragging = false, isDimmed = false, isDragPreview = preview,
            onClick = { calls += "Play" }, onToggleFavorite = { calls += "Favorite" }, onOpenDetails = { calls += "Details" },
            onAlbumClick = { calls += "Album" }, onArtistClick = { calls += "Artist" }, onPlayNext = { calls += "Next" },
            onAddToQueue = { calls += "Queue" }, onAddToPlaylist = { calls += "Playlist" }, onShare = { calls += "Share" },
            onRemove = { calls += "Remove" }, onMoveUp = { calls += "Up" }, onMoveDown = { calls += "Down" },
            onDragStart = { calls += "DragStart" }, onDragDelta = {}, onDragEnd = { calls += "DragEnd" }, onDragCancel = { calls += "DragCancel" },
        )
    }

    private fun openByButton(description: String) {
        rule.onNode(hasClickAction() and hasContentDescription(description)).performClick(); rule.waitForIdle()
    }

    private fun menuLabels(): List<String> =
        rule.onAllNodes(hasClickAction() and hasAnyAncestor(isPopup())).fetchSemanticsNodes()
            .map { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty() }

    private fun item(label: String) = rule.onNode(hasText(label) and hasClickAction() and hasAnyAncestor(isPopup()))
    private fun confirmShown() = rule.onAllNodes(hasText("Delete from device?")).fetchSemanticsNodes().isNotEmpty()
    private fun menuOpen() = rule.onAllNodes(isPopup()).fetchSemanticsNodes().isNotEmpty()

    private val commonIcons = listOf(
        "Play", "Play next", "Add to queue", "Add to playlist", "Add to Favorites", "Go to artist", "Go to album", "Track details", "Share",
    )

    // ── Most Played ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun mostPlayed_menu_has_icons_and_offers_delete_last_for_an_eligible_song() {
        host { mostPlayed()() }
        openByButton("More actions")
        (commonIcons + "Delete from device").forEach { rule.onNode(hasTestTag(songMenuIconTag(it)), useUnmergedTree = true).assertExists("icon for $it") }
        assertEquals("Delete from device", menuLabels().last())
    }

    @Test fun mostPlayed_ineligible_song_has_no_delete() {
        host { mostPlayed(song(id = Long.MIN_VALUE))() }
        openByButton("More actions")
        rule.onNode(hasText("Delete from device")).assertDoesNotExist()
        item("Share").assertIsDisplayed()
    }

    @Test fun mostPlayed_delete_opens_the_shared_confirmation_and_nothing_else_fires() {
        host { mostPlayed()() }
        openByButton("More actions")
        item("Delete from device").performScrollTo().performClick(); rule.waitForIdle()
        assertFalse("menu closes", menuOpen())
        assertTrue("shared confirmation shown", confirmShown())
        assertEquals(emptyList<String>(), calls)
        assertEquals("nothing is requested before the user confirms", emptyList<String>(), backend.requested)
    }

    @Test fun mostPlayed_long_press_and_three_dot_both_open_the_same_menu() {
        host { mostPlayed()() }
        openByButton("More actions")
        val viaButton = menuLabels()
        rule.onNode(hasText("Play next") and hasAnyAncestor(isPopup())).performClick(); rule.waitForIdle()
        calls.clear()
        rule.onNode(hasText("Song 1")).performTouchInput { longClick() }; rule.waitForIdle()
        assertEquals(viaButton, menuLabels())
    }

    @Test fun mostPlayed_title_and_ranking_metric_stay_visible_at_large_font_scale_and_actions_fire_once() {
        host(fontScale = 2f) { mostPlayed(song(title = "A very long song title that must wrap onto a second line rather than vanish"), plays = 123)() }
        rule.onNode(hasText("123 plays", substring = true)).assertIsDisplayed()
        rule.onNode(hasText("A very long song title", substring = true)).assertIsDisplayed()
        openByButton("More actions")
        (commonIcons + "Delete from device").forEach { rule.onNode(hasTestTag(songMenuIconTag(it)), useUnmergedTree = true).assertExists() }
        item("Add to queue").performScrollTo().performClick(); rule.waitForIdle()
        assertEquals(listOf("Queue"), calls)
    }

    @Test fun mostPlayed_playback_and_favorite_callbacks_fire_once() {
        host { mostPlayed()() }
        openByButton("More actions"); item("Play").performClick(); rule.waitForIdle()
        openByButton("More actions"); item("Add to Favorites").performClick(); rule.waitForIdle()
        assertEquals(listOf("Play", "Favorite"), calls)
    }

    // ── Playlist Details ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun playlist_remove_and_delete_are_distinct_and_remove_never_starts_deletion() {
        host { playlistRow()() }
        openByButton("Playlist song options")
        val labels = menuLabels()
        assertTrue(labels.containsAll(listOf("Remove from playlist", "Delete from device", "Move up", "Move down")))
        assertEquals("Delete from device", labels.last())
        item("Remove from playlist").performScrollTo().performClick(); rule.waitForIdle()
        assertEquals(listOf("Remove"), calls)
        assertFalse(confirmShown()); assertEquals(emptyList<String>(), backend.requested)
    }

    @Test fun playlist_delete_uses_the_shared_flow_and_never_calls_remove() {
        host { playlistRow()() }
        openByButton("Playlist song options")
        item("Delete from device").performScrollTo().performClick(); rule.waitForIdle()
        assertTrue(confirmShown())
        assertEquals("Remove is not triggered by device deletion", emptyList<String>(), calls)
    }

    @Test fun playlist_move_actions_respect_position_and_fire_once() {
        host { playlistRow(isFirst = true)() }
        openByButton("Playlist song options")
        item("Move up").assertIsNotEnabled()
        item("Move down").assertIsEnabled().performScrollTo().performClick(); rule.waitForIdle()
        assertEquals(listOf("Down"), calls)
    }

    @Test fun playlist_search_filtered_rows_hide_move_actions_but_keep_remove_and_delete() {
        host { playlistRow(allowMove = false)() }
        openByButton("Playlist song options")
        val labels = menuLabels()
        assertFalse("Move up" in labels); assertFalse("Move down" in labels)
        assertTrue("Remove from playlist" in labels && "Delete from device" in labels)
    }

    @Test fun playlist_drag_preview_never_offers_device_deletion() {
        host { playlistRow(preview = true)() }
        openByButton("Playlist song options")
        rule.onNode(hasText("Delete from device")).assertDoesNotExist()
    }

    @Test fun playlist_drag_handle_is_present_without_opening_the_menu_and_the_favorite_button_still_works() {
        // The drag gesture code is unchanged by SONG-MENU-04A; a simulated swipe could not be made to start the gesture in this
        // harness, so only the handle's presence and its separation from the menu are asserted here (reported as a limitation).
        host { playlistRow()() }
        rule.onNode(hasContentDescription("Drag to reorder")).assertIsDisplayed()
        assertFalse(menuOpen())
        rule.onNode(hasContentDescription("Favorite") and hasClickAction()).performClick(); rule.waitForIdle()
        assertEquals(listOf("Favorite"), calls)
    }

    // ── Cross-screen consistency ─────────────────────────────────────────────────────────────────────────────────────────

    @Test fun standard_song_menu_order_is_the_reference_for_the_custom_rows() {
        host {
            SongRowWithOverflow(
                song = song(), isCurrent = false, isFavorite = false, onPlay = {}, onPlayNext = {}, onAddToQueue = {}, onToggleFavorite = {},
                onAddToPlaylist = {}, onTrackDetails = {}, onShare = {}, onAlbumClick = {}, onArtistClick = {},
            )
        }
        openByButton("More actions")
        assertEquals(commonIcons + "Delete from device", menuLabels())
    }

    @Test fun mostPlayed_menu_order_matches_the_standard_song_menu() {
        host { mostPlayed()() }
        openByButton("More actions")
        assertEquals(commonIcons + "Delete from device", menuLabels())
    }

    @Test fun playlist_menu_keeps_common_actions_in_the_standard_order_with_screen_actions_before_delete() {
        host { playlistRow()() }
        openByButton("Playlist song options")
        assertEquals(commonIcons + listOf("Move up", "Move down", "Remove from playlist", "Delete from device"), menuLabels())
    }
}
