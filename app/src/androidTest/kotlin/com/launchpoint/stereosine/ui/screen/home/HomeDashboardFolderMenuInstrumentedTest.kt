package com.launchpoint.stereosine.ui.screen.home

import android.content.IntentSender
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import com.launchpoint.stereosine.data.model.Song
import com.launchpoint.stereosine.ui.components.SongDeletionBackend
import com.launchpoint.stereosine.ui.components.SongDeletionHost
import com.launchpoint.stereosine.ui.components.SongDeletionViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * UI-05E. Production Home dashboardSection (Recently Played / Most Played rows) with the production
 * SongRowWithOverflow and SongDeletionHost, a recording backend (no file is deleted) and recording callbacks.
 */
class HomeDashboardFolderMenuInstrumentedTest {

    @get:Rule val rule = createComposeRule()

    private class Backend : SongDeletionBackend {
        val requested = mutableListOf<String>()
        override fun createDeleteRequest(songUri: String): IntentSender? { requested += songUri; return null }
        override suspend fun cleanUp(songId: Long, songUri: String) {}
    }

    private val backend = Backend()
    private val calls = mutableListOf<String>()
    private val viewModel by lazy { SongDeletionViewModel(SavedStateHandle(), backend, sdkInt = 34) }

    private fun song(id: Long, folderPath: String?) = Song(
        id = id, title = "Song $id", artist = "Artist", album = "Album", albumId = id, duration = 200_000L,
        uri = "content://test/media/$id", dateAdded = 0L, trackNumber = 1, year = 2024, folderPath = folderPath,
    )

    private fun host(songs: List<Song>, title: String = "Recently Played") {
        rule.setContent {
            SongDeletionHost(viewModel = viewModel, authorize = { _ -> { _ -> } }) {
                LazyColumn {
                    dashboardSection(
                        title = title,
                        songs = songs,
                        emptyText = "",
                        currentSongId = null,
                        favoriteSongIds = emptySet(),
                        onSongClick = { calls += "Play:${it.id}" },
                        onToggleFavorite = { s, _ -> calls += "Favorite:${s.id}" },
                        onTrackDetailsClick = { calls += "Details:$it" },
                        onPlayNext = { calls += "Next:${it.id}" },
                        onAddToQueue = { calls += "Queue:${it.id}" },
                        onAddToPlaylist = { calls += "Playlist:${it.id}" },
                        onShare = { calls += "Share:${it.id}" },
                        onAlbumClick = { calls += "Album:$it" },
                        onArtistClick = { calls += "Artist:$it" },
                        onFolderClick = { calls += "Folder:$it" },
                    )
                }
            }
        }
    }

    private fun openMenuOfRow(index: Int) {
        rule.onAllNodes(hasClickAction() and hasContentDescription("More actions"))[index].performClick()
        rule.waitForIdle()
    }

    private fun menuLabels(): List<String> =
        rule.onAllNodes(hasClickAction() and hasAnyAncestor(isPopup())).fetchSemanticsNodes()
            .map { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty() }

    private fun item(label: String) = rule.onNode(hasText(label) and hasClickAction() and hasAnyAncestor(isPopup()))
    private fun menuOpen() = rule.onAllNodes(isPopup()).fetchSemanticsNodes().isNotEmpty()

    @Test fun aSongWithAValidFolderShowsViewFolderAndOpensItsFolderKey() {
        host(listOf(song(1, "Music/Rock")))
        openMenuOfRow(0)
        item("View folder").assertIsDisplayed()
        item("View folder").performScrollTo().performClick(); rule.waitForIdle()
        assertEquals(listOf("Folder:Music/Rock"), calls)
        assertFalse("menu closes after the action", menuOpen())
    }

    @Test fun eachRowOpensItsOwnFolder() {
        host(listOf(song(1, "Music/Rock"), song(2, "Download/Jazz")))
        openMenuOfRow(1)
        item("View folder").performScrollTo().performClick(); rule.waitForIdle()
        assertEquals(listOf("Folder:Download/Jazz"), calls)
    }

    @Test fun aSongWithNoFolderPathHasNoViewFolder() {
        host(listOf(song(1, null)))
        openMenuOfRow(0)
        rule.onNode(hasText("View folder")).assertDoesNotExist()
        assertTrue("Share" in menuLabels())
    }

    private fun assertNoViewFolderFor(path: String) {
        host(listOf(song(10, path)))
        openMenuOfRow(0)
        rule.onNode(hasText("View folder")).assertDoesNotExist()
        assertTrue("Share" in menuLabels())
    }

    @Test fun emptyFolderPathHasNoViewFolder() = assertNoViewFolderFor("")
    @Test fun spacesOnlyFolderPathHasNoViewFolder() = assertNoViewFolderFor("   ")
    @Test fun slashOnlyFolderPathHasNoViewFolder() = assertNoViewFolderFor("/")
    @Test fun backslashOnlyFolderPathHasNoViewFolder() = assertNoViewFolderFor("\\")

    @Test fun existingActionOrderIsUnchangedAndViewFolderSitsBeforeDelete() {
        host(listOf(song(1, "Music/Rock")))
        openMenuOfRow(0)
        assertEquals(
            listOf(
                "Play", "Play next", "Add to queue", "Add to playlist", "Add to Favorites",
                "Go to artist", "Go to album", "Track details", "Share", "View folder", "Delete from device",
            ),
            menuLabels(),
        )
    }

    @Test fun withoutAFolderTheOtherActionsKeepTheirOrder() {
        host(listOf(song(1, null)))
        openMenuOfRow(0)
        assertEquals(
            listOf(
                "Play", "Play next", "Add to queue", "Add to playlist", "Add to Favorites",
                "Go to artist", "Go to album", "Track details", "Share", "Delete from device",
            ),
            menuLabels(),
        )
    }

    @Test fun playbackShareAndFavoriteStillFireExactlyOnce() {
        host(listOf(song(1, "Music/Rock")))
        rule.onNode(hasText("Song 1")).performClick(); rule.waitForIdle()
        openMenuOfRow(0); item("Share").performClick(); rule.waitForIdle()
        openMenuOfRow(0); item("Add to Favorites").performClick(); rule.waitForIdle()
        // SongRow's single tap is delayed by its double-tap detector (combinedClickable), so assert "exactly once each", not order.
        rule.waitUntil(5_000) { calls.size >= 3 }
        assertEquals(3, calls.size)
        assertEquals(setOf("Play:1", "Share:1", "Favorite:1"), calls.toSet())
    }

    @Test fun deletionStillUsesTheSharedConfirmationAndNeverFiresViewFolder() {
        host(listOf(song(1, "Music/Rock")))
        openMenuOfRow(0)
        item("Delete from device").performScrollTo().performClick(); rule.waitForIdle()
        assertFalse(menuOpen())
        assertTrue("shared confirmation shown", rule.onAllNodes(hasText("Delete from device?")).fetchSemanticsNodes().isNotEmpty())
        assertEquals("no navigation or other action fired", emptyList<String>(), calls)
        assertEquals("nothing is requested before the user confirms", emptyList<String>(), backend.requested)
    }

    @Test fun mostPlayedSectionAlsoOffersViewFolder() {
        host(listOf(song(3, "Music/Pop")), title = "Most Played")
        openMenuOfRow(0)
        item("View folder").performScrollTo().performClick(); rule.waitForIdle()
        assertEquals(listOf("Folder:Music/Pop"), calls)
    }
}
