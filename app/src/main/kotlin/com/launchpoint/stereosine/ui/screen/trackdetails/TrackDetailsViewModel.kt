package com.launchpoint.stereosine.ui.screen.trackdetails

import android.content.Context
import android.content.IntentSender
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.stereosine.data.local.dao.ImportBaselineDao
import com.launchpoint.stereosine.data.local.dao.LyricsOverrideDao
import com.launchpoint.stereosine.data.model.PlaylistSong
import com.launchpoint.stereosine.data.model.PlaylistSummary
import com.launchpoint.stereosine.data.model.Song
import com.launchpoint.stereosine.data.model.TrackStats
import com.launchpoint.stereosine.data.repository.AddToPlaylistResult
import com.launchpoint.stereosine.data.repository.PlaylistRepository
import com.launchpoint.stereosine.data.repository.PlaylistOperationResult
import com.launchpoint.stereosine.data.repository.SongRepository
import com.launchpoint.stereosine.data.repository.StatsRepository
import com.launchpoint.stereosine.playback.PlayerController
import com.launchpoint.stereosine.ui.components.MediaStoreSongDeleter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface TrackDetailsUiState {
    data object Loading  : TrackDetailsUiState
    data object NotFound : TrackDetailsUiState
    data class  Ready(
        val song: Song,
        val stats: TrackStats?,
        val isFavorite: Boolean,
    ) : TrackDetailsUiState
}

@HiltViewModel
class TrackDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val songRepository: SongRepository,
    private val statsRepository: StatsRepository,
    private val playlistRepository: PlaylistRepository,
    private val playerController: PlayerController,
    private val lyricsOverrideDao: LyricsOverrideDao,
    private val importBaselineDao: ImportBaselineDao,
) : ViewModel() {

    private val songId: Long = checkNotNull(savedStateHandle["songId"])

    val uiState: StateFlow<TrackDetailsUiState> = combine(
        songRepository.observeSongById(songId),
        statsRepository.observeStats(songId),
        statsRepository.favoriteSongIds(),
    ) { song, stats, favorites ->
        if (song == null) TrackDetailsUiState.NotFound
        else TrackDetailsUiState.Ready(
            song       = song,
            stats      = stats,
            isFavorite = songId in favorites,
        )
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = TrackDetailsUiState.Loading,
    )

    val playlists: StateFlow<List<PlaylistSummary>> = playlistRepository.observePlaylists()
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val allPlaylistSongs: StateFlow<List<PlaylistSong>> = playlistRepository.observeAllPlaylistSongs()
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun toggleFavorite() {
        val state = uiState.value as? TrackDetailsUiState.Ready ?: return
        viewModelScope.launch {
            statsRepository.toggleFavorite(state.song.id, state.song.uri)
        }
    }

    fun addToPlaylist(playlistId: Long, onResult: (AddToPlaylistResult) -> Unit = {}) {
        val song = (uiState.value as? TrackDetailsUiState.Ready)?.song ?: return
        viewModelScope.launch {
            val result = playlistRepository.addSongToPlaylist(song.id, playlistId)
            onResult(result)
        }
    }

    fun createPlaylistAndAdd(name: String, onResult: (PlaylistOperationResult) -> Unit = {}) {
        val song = (uiState.value as? TrackDetailsUiState.Ready)?.song ?: return
        viewModelScope.launch {
            val result = playlistRepository.createPlaylist(name)
            if (result is PlaylistOperationResult.Success) {
                playlistRepository.addSongToPlaylist(song.id, result.playlistId)
            }
            onResult(result)
        }
    }

    fun playNow() {
        val song = (uiState.value as? TrackDetailsUiState.Ready)?.song ?: return
        playerController.playSearchResultPreservingQueue(song)
    }

    fun playNext() {
        val song = (uiState.value as? TrackDetailsUiState.Ready)?.song ?: return
        playerController.playNext(song)
    }

    fun addToQueue() {
        val song = (uiState.value as? TrackDetailsUiState.Ready)?.song ?: return
        playerController.addToQueue(song)
    }

    // UI-READABILITY-03D: the request and the cleanup live in ONE implementation, shared with the song-row overflow menu.
    private val songDeleter = MediaStoreSongDeleter(
        context, songRepository, playlistRepository, playerController, lyricsOverrideDao, importBaselineDao,
    )

    fun deleteFromDevice(
        song: Song,
        onDeleteRequested: (IntentSender) -> Unit,
        onFailed: () -> Unit,
    ) {
        val sender = songDeleter.createDeleteRequest(song.uri)
        if (sender == null) { onFailed(); return }
        runCatching { onDeleteRequested(sender) }.onFailure { onFailed() }
    }

    fun onDeleteApproved(song: Song, onComplete: () -> Unit) {
        viewModelScope.launch {
            // Per-step failures were always swallowed here and the screen still closed; keep that (the shared cleanup now rethrows).
            runCatching { songDeleter.cleanUp(song.id, song.uri) }
            onComplete()
        }
    }
}
