package com.launchpoint.stereosine.ui.components

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.launchpoint.stereosine.data.local.StereoSineDatabase
import com.launchpoint.stereosine.data.local.entity.SongEntity
import com.launchpoint.stereosine.data.local.entity.TrackListenEventEntity
import com.launchpoint.stereosine.data.local.entity.TrackStatsEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI-READABILITY-03D: the database steps of the shared cleanup ([MediaStoreSongDeleter.cleanUp]) run against a real in-memory Room
 * database, repeated as a recovery retry would. They remove the song row only for the cleaned id and never touch track_stats or
 * track_listen_events. This exercises the same DAO calls the deleter makes; it does not construct the deleter itself.
 */
@RunWith(AndroidJUnit4::class)
class SongDeletionHistoryRetentionInstrumentedTest {

    private lateinit var db: StereoSineDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), StereoSineDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun song(id: Long) = SongEntity(
        id = id, title = "S$id", artist = "A", album = "B", albumId = 0L, duration = 1L,
        uri = "content://media/$id", dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    private suspend fun cleanUpDatabaseSteps(songId: Long, uri: String) {
        db.songDao().deleteSong(songId)
        db.playlistDao().removeAllEntriesForSong(songId)
        db.lyricsOverrideDao().deleteForSong(songId, uri)
        db.importBaselineDao().deleteBySongId(songId)
    }

    @Test fun cleanup_and_a_repeated_recovery_cleanup_keep_history_and_leave_other_songs_alone() = runBlocking {
        db.songDao().upsertAll(listOf(song(1), song(2)))
        db.trackStatsDao().insertIfAbsent(TrackStatsEntity(songId = 1, contentUri = "content://media/1", playCount = 7))
        db.trackStatsDao().insertIfAbsent(TrackStatsEntity(songId = 2, contentUri = "content://media/2", playCount = 3))
        db.trackListenEventDao().insertAll(
            listOf(
                TrackListenEventEntity(songId = 1, eventType = "PLAY", occurredAt = 10, listenedMs = 5, durationMs = 9, source = "wavdrop_playback"),
                TrackListenEventEntity(songId = 1, eventType = "SKIP", occurredAt = 20, listenedMs = 0, durationMs = 9, source = "wavdrop_playback"),
                TrackListenEventEntity(songId = 2, eventType = "PLAY", occurredAt = 30, listenedMs = 5, durationMs = 9, source = "wavdrop_playback"),
            ),
        )

        cleanUpDatabaseSteps(1, "content://media/1")
        cleanUpDatabaseSteps(1, "content://media/1")      // recovery re-run: idempotent, no error, no further effect

        val songIds = db.songDao().getAllSongsSnapshot().map { it.id }
        assertEquals("only the cleaned song is removed", listOf(2L), songIds)
        assertEquals("play statistics are kept", 7, db.trackStatsDao().getStatsBySongId(1)?.playCount)
        assertNotNull(db.trackStatsDao().getStatsBySongId(2))
        assertEquals("listening events are kept", listOf(1L, 1L, 2L), db.trackListenEventDao().getAllSnapshot().map { it.songId }.sorted())
        assertNull(db.songDao().getAllSongsSnapshot().firstOrNull { it.id == 1L })
    }
}
