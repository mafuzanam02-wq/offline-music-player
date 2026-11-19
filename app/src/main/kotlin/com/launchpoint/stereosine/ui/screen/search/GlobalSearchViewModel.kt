package com.launchpoint.stereosine.ui.screen.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.stereosine.data.model.PlaylistSummary
import com.launchpoint.stereosine.data.model.Song
import com.launchpoint.stereosine.data.repository.PlaylistRepository
import com.launchpoint.stereosine.data.repository.SmartCollectionRepository
import com.launchpoint.stereosine.data.repository.SongRepository
import com.launchpoint.stereosine.data.repository.StatsRepository
import com.launchpoint.stereosine.data.search.LibrarySearchIndex
import com.launchpoint.stereosine.data.search.SearchHistoryEntry
import com.launchpoint.stereosine.data.search.SearchHistoryRepository
import com.launchpoint.stereosine.playback.QueueActionResult
import com.launchpoint.stereosine.data.settings.AppSettingsRepository
import com.launchpoint.stereosine.data.settings.SearchTapBehavior
import com.launchpoint.stereosine.playback.NowPlayingState
import com.launchpoint.stereosine.playback.PlaybackStartupTrace
import com.launchpoint.stereosine.playback.PlayerController
import com.launchpoint.stereosine.ui.components.GroupedSearchResults
import com.launchpoint.stereosine.ui.components.groupedSearchResults
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GlobalSearchViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    songRepository: SongRepository,
    private val playerController: PlayerController,
    private val statsRepository: StatsRepository,
    private val appSettingsRepository: AppSettingsRepository,
    private val smartCollectionRepository: SmartCollectionRepository,
    private val playlistRepository: PlaylistRepository,
    private val searchHistoryRepository: SearchHistoryRepository,
) : ViewModel() {

    val searchQuery: StateFlow<String> = savedStateHandle.getStateFlow("searchQuery", "")

    fun setSearchQuery(query: String) { savedStateHandle["searchQuery"] = query }

    /** SEARCH-01: recent committed searches, newest first. Separate from listening history and playback statistics. */
    val recentSearches: StateFlow<List<SearchHistoryEntry>> = searchHistoryRepository.entries
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /** Records a COMMITTED search (keyboard Search, a tapped result, a tapped recent entry). Typing alone never calls this. */
    fun commitSearch(query: String) {
        // The search destination can be popped before a pending history write finishes.
        // The repository performs the write in its own singleton-owned scope (non-blocking, atomic).
        searchHistoryRepository.commit(query)
    }

    fun removeRecentSearch(query: String) {
        searchHistoryRepository.forget(query)
    }

    fun clearRecentSearches() {
        searchHistoryRepository.clear()
    }

    private val allSongs: StateFlow<List<Song>> = songRepository.songs
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val playlists: StateFlow<List<PlaylistSummary>> = playlistRepository.observePlaylists()
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    // Phase 9: the normalized search projection is rebuilt only when the library, playlists, or
    // smart collections actually change — NOT on every keystroke. mapLatest cancels a stale rebuild
    // if the library changes again mid-build (e.g. during a rescan).
    private val searchIndex =
        combine(
            allSongs,
            smartCollectionRepository.observeSmartCollections(),
            playlists,
        ) { songs, collections, lists -> Triple(songs, lists, collections) }
            .mapLatest { (songs, lists, collections) ->
                LibrarySearchIndex.from(songs = songs, playlists = lists, smartCollections = collections)
            }
            .flowOn(Dispatchers.Default)

    // Per-keystroke work is now only cheap substring matching against the precomputed index.
    // mapLatest supersedes an in-flight search when a newer query (or index) arrives, so stale
    // filter jobs do not stack. Debounce (200 ms) and result semantics/ordering are unchanged.
    val filteredResults: StateFlow<SearchResultsForQuery?> =
        combine(searchIndex, searchQuery.debounce(200), ::Pair)
            .mapLatest { (index, query) ->
                SearchResultsForQuery(query, groupedSearchResults(index = index, query = query))
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val nowPlayingState: StateFlow<NowPlayingState> = playerController.nowPlayingState

    val favoriteSongIds: StateFlow<Set<Long>> = statsRepository.favoriteSongIds()
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptySet(),
        )

    fun playSearchResult(song: Song) {
        PlaybackStartupTrace.hintEntry("global_search_tap") // starts the tap-to-request clock before the settings read below
        viewModelScope.launch {
            when (appSettingsRepository.searchTapBehavior.first()) {
                SearchTapBehavior.REPLACE_QUEUE -> {
                    val queue = allSongs.value.ifEmpty { listOf(song) }
                    PlaybackStartupTrace.hintEntry("global_search_replace_queue")
                    playerController.playFromQueue(queue = queue, startSong = song)
                }
                SearchTapBehavior.PRESERVE_QUEUE -> {
                    PlaybackStartupTrace.hintEntry("global_search_preserve_queue")
                    playerController.playSearchResultPreservingQueue(song)
                }
            }
        }
    }

    fun playNext(song: Song): QueueActionResult = playerController.playNext(song)

    fun addToQueue(song: Song): QueueActionResult = playerController.addToQueue(song)

    fun toggleFavorite(song: Song) {
        viewModelScope.launch { statsRepository.toggleFavorite(song.id, song.uri) }
    }
}

data class SearchResultsForQuery(val query: String, val results: GroupedSearchResults)
