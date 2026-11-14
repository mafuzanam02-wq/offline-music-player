package com.launchpoint.stereosine.playback

import com.launchpoint.stereosine.data.model.Song
import com.launchpoint.stereosine.data.playback.PlaybackSessionSnapshot
import com.launchpoint.stereosine.data.settings.ResumeBehaviorSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StereoSineMediaLibraryTest {
    private val songs = (1L..5L).map { song(it) }

    @Test
    fun `root children are recent and songs both browsable and not playable`() {
        val children = StereoSineMediaLibrary.rootChildren()
        assertEquals(listOf(StereoSineLibraryIds.RECENT, StereoSineLibraryIds.SONGS), children.map { it.mediaId })
        children.forEach {
            assertTrue(it.mediaMetadata.isBrowsable == true)
            assertFalse(it.mediaMetadata.isPlayable == true)
        }
        val root = StereoSineMediaLibrary.rootItem()
        assertTrue(root.mediaMetadata.isBrowsable == true)
        assertFalse(root.mediaMetadata.isPlayable == true)
    }

    @Test
    fun `browse node ids are stable and not numeric`() {
        listOf(StereoSineLibraryIds.ROOT, StereoSineLibraryIds.RECENT, StereoSineLibraryIds.SONGS).forEach {
            assertTrue(StereoSineMediaLibrary.isBrowseNode(it))
            assertNull(it.toLongOrNull())
        }
        assertFalse(StereoSineMediaLibrary.isBrowseNode("42"))
    }

    @Test
    fun `song items are playable with stable id and preserved metadata`() {
        val item = StereoSineMediaLibrary.songItem(songs[2])
        assertEquals("3", item.mediaId)
        assertTrue(item.mediaMetadata.isPlayable == true)
        assertFalse(item.mediaMetadata.isBrowsable == true)
        assertEquals(songs[2].displayTitle, item.mediaMetadata.title.toString())
        assertEquals(songs[2].displayArtist, item.mediaMetadata.artist.toString())
        assertEquals(songs[2].album, item.mediaMetadata.albumTitle.toString())
    }

    @Test
    fun `song pagination slices correctly and handles edges`() {
        assertEquals(listOf("1", "2"), StereoSineMediaLibrary.songChildren(songs, 0, 2).map { it.mediaId })
        assertEquals(listOf("3", "4"), StereoSineMediaLibrary.songChildren(songs, 1, 2).map { it.mediaId })
        assertEquals(listOf("5"), StereoSineMediaLibrary.songChildren(songs, 2, 2).map { it.mediaId })
        assertTrue(StereoSineMediaLibrary.songChildren(songs, 3, 2).isEmpty())
        assertTrue(StereoSineMediaLibrary.songChildren(songs, -1, 2).isEmpty())
        assertTrue(StereoSineMediaLibrary.songChildren(songs, 0, 0).isEmpty())
        assertEquals(5, StereoSineMediaLibrary.songChildren(songs, 0, Int.MAX_VALUE).size)
    }

    @Test
    fun `empty library yields empty song children`() {
        assertTrue(StereoSineMediaLibrary.songChildren(emptyList(), 0, 50).isEmpty())
    }

    @Test
    fun `recent yields one playable item for a resumable session`() {
        val recent = StereoSineMediaLibrary.recentChildren(
            snapshot(currentSongId = 2, currentIndex = 1),
            ResumeBehaviorSettings(),
            songs,
        )
        assertEquals(listOf("2"), recent.map { it.mediaId })
        assertTrue(recent.single().mediaMetadata.isPlayable == true)
        assertTrue(recent.single().mediaMetadata.title.toString().isNotBlank())
    }

    @Test
    fun `recent is empty without a saved session`() {
        assertTrue(StereoSineMediaLibrary.recentChildren(null, ResumeBehaviorSettings(), songs).isEmpty())
    }

    @Test
    fun `recent is empty when the saved song is unavailable`() {
        val recent = StereoSineMediaLibrary.recentChildren(
            snapshot(queueIds = listOf(99), currentSongId = 99, currentIndex = 0),
            ResumeBehaviorSettings(),
            songs,
        )
        assertTrue(recent.isEmpty())
    }

    @Test
    fun `recent does not guess a duplicate occurrence when the saved index is unusable`() {
        // Song 1 appears twice and the saved index no longer matches: ambiguous, so empty.
        val recent = StereoSineMediaLibrary.recentChildren(
            snapshot(queueIds = listOf(1, 2, 1, 3), currentSongId = 1, currentIndex = 7),
            ResumeBehaviorSettings(),
            songs,
        )
        assertTrue(recent.isEmpty())
    }

    @Test
    fun `recent resolves the duplicate song when the saved index is valid`() {
        val recent = StereoSineMediaLibrary.recentChildren(
            snapshot(queueIds = listOf(1, 2, 1, 3), currentSongId = 1, currentIndex = 2),
            ResumeBehaviorSettings(),
            songs,
        )
        assertEquals(listOf("1"), recent.map { it.mediaId })
    }

    @Test
    fun `item lookup resolves nodes and songs and rejects unknown ids`() {
        assertEquals(StereoSineLibraryIds.ROOT, StereoSineMediaLibrary.item(StereoSineLibraryIds.ROOT, songs)?.mediaId)
        assertEquals("4", StereoSineMediaLibrary.item("4", songs)?.mediaId)
        assertNull(StereoSineMediaLibrary.item("999", songs))
        assertNull(StereoSineMediaLibrary.item("not-an-id", songs))
    }

    @Test
    fun `normal root request returns the normal root`() {
        assertEquals(StereoSineLibraryIds.ROOT, StereoSineMediaLibrary.rootFor(isRecent = false).mediaId)
    }

    @Test
    fun `recent query root is a stable non-numeric browse root distinct from other nodes`() {
        val root = StereoSineMediaLibrary.rootFor(isRecent = true)
        assertEquals(StereoSineLibraryIds.RECENT_ROOT, root.mediaId)
        assertNull(root.mediaId.toLongOrNull())
        assertTrue(root.mediaMetadata.isBrowsable == true)
        assertFalse(root.mediaMetadata.isPlayable == true)
        listOf(StereoSineLibraryIds.ROOT, StereoSineLibraryIds.RECENT, StereoSineLibraryIds.SONGS).forEach {
            assertFalse(it == root.mediaId)
        }
        assertTrue(StereoSineMediaLibrary.isBrowseNode(StereoSineLibraryIds.RECENT_ROOT))
        assertEquals(StereoSineLibraryIds.RECENT_ROOT, StereoSineMediaLibrary.item(StereoSineLibraryIds.RECENT_ROOT, songs)?.mediaId)
    }

    @Test
    fun `recent query root children are the playable song directly with no intermediate node`() {
        val children = StereoSineMediaLibrary.recentChildren(
            snapshot(currentSongId = 2, currentIndex = 1),
            ResumeBehaviorSettings(),
            songs,
        )
        assertEquals(listOf("2"), children.map { it.mediaId })
        assertFalse(children.any { it.mediaId == StereoSineLibraryIds.RECENT })
        val item = children.single()
        assertTrue(item.mediaMetadata.isPlayable == true)
        assertFalse(item.mediaMetadata.isBrowsable == true)
        assertTrue(item.mediaMetadata.title.toString().isNotBlank())
        assertEquals(songs[1].displayArtist, item.mediaMetadata.artist.toString())
    }

    @Test
    fun `normal root still lists recent and songs`() {
        assertEquals(
            listOf(StereoSineLibraryIds.RECENT, StereoSineLibraryIds.SONGS),
            StereoSineMediaLibrary.rootChildren().map { it.mediaId },
        )
    }

    private fun snapshot(
        queueIds: List<Long> = listOf(1, 2, 3),
        currentSongId: Long = 1,
        currentIndex: Int = 0,
    ) = PlaybackSessionSnapshot(
        queueSongIds = queueIds,
        currentSongId = currentSongId,
        currentIndex = currentIndex,
        positionMs = 1_000L,
        repeatMode = RepeatMode.OFF,
        shuffleEnabled = false,
        updatedAtMs = 0L,
    )

    private fun song(id: Long) = Song(
        id = id,
        title = "Title $id",
        artist = "Artist $id",
        album = "Album $id",
        albumId = id,
        duration = 200_000L,
        uri = "content://media/$id",
        dateAdded = 0L,
        trackNumber = 1,
        year = 2020,
    )
}
