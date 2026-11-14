package com.launchpoint.stereosine.data.lyrics

import com.launchpoint.stereosine.data.local.dao.LyricsOverrideDao
import com.launchpoint.stereosine.data.local.entity.LyricsOverrideEntity
import com.launchpoint.stereosine.data.model.Song
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class LyricsOverrideRepository @Inject constructor(
    private val dao: LyricsOverrideDao,
) {
    fun observeOverride(songId: Long): Flow<LyricsOverrideEntity?> =
        dao.observeBySongId(songId)

    suspend fun getOverride(song: Song): LyricsOverrideEntity? =
        dao.getForSong(song.id, song.uri)

    suspend fun saveOverride(song: Song, lyrics: String) {
        val cleaned = LyricsTextCleaner.clean(lyrics) ?: return
        dao.upsert(
            LyricsOverrideEntity(
                songId = song.id,
                contentUri = song.uri,
                lyrics = cleaned,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun clearOverride(song: Song) {
        dao.deleteForSong(song.id, song.uri)
    }
}
