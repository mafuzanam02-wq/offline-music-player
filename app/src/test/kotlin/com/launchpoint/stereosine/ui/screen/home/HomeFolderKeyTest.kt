package com.launchpoint.stereosine.ui.screen.home

import com.launchpoint.stereosine.data.library.FolderGrouper
import com.launchpoint.stereosine.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * UI-05E. Home offers View folder only for a song with a real, navigable folder, using the same key the Folders screen
 * groups by (FolderGrouper.folderKey), so the opened folder is the one the song actually belongs to.
 */
class HomeFolderKeyTest {

    private fun song(folderPath: String?) = Song(
        id = 1L, title = "T", artist = "A", album = "Al", albumId = 1L, duration = 1000L,
        uri = "content://media/1", dateAdded = 0L, trackNumber = 1, year = 2020, folderPath = folderPath,
    )

    @Test fun aRealFolderPathYieldsTheGroupingKey() {
        val s = song("Music/Rock")
        assertEquals("Music/Rock", s.validFolderKey())
        assertEquals(FolderGrouper.folderKey(s), s.validFolderKey())
    }

    @Test fun surroundingSeparatorsAndSpacesAreTrimmedExactlyLikeTheFoldersScreen() {
        assertEquals("Music/Rock", song("/Music/Rock/").validFolderKey())
        assertEquals("Music/Rock", song("  \\Music/Rock\\ ").validFolderKey())
        assertEquals(FolderGrouper.folderKey(song("/Music/Rock/")), song("/Music/Rock/").validFolderKey())
    }

    @Test fun aMissingFolderPathHasNoViewFolder() {
        assertNull(song(null).validFolderKey())
    }

    @Test fun blankOrSeparatorOnlyPathsHaveNoViewFolder() {
        assertNull(song("").validFolderKey())
        assertNull(song("   ").validFolderKey())
        assertNull(song("/").validFolderKey())
        assertNull(song("\\").validFolderKey())
        assertNull(song(" / \\ ").validFolderKey())
    }

    @Test fun theUnknownFolderPlaceholderIsNeverNavigable() {
        // A song whose real folder is literally named like the placeholder cannot be opened as a folder either.
        assertNull(song(FolderGrouper.UNKNOWN_FOLDER).validFolderKey())
        assertNotEquals(FolderGrouper.UNKNOWN_FOLDER, song("Music").validFolderKey())
    }
}
