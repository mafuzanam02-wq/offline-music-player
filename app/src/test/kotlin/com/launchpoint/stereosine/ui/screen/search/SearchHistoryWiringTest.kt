package com.launchpoint.stereosine.ui.screen.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SEARCH-01 wiring guards for the Hilt-bound route (which cannot be instantiated in a JVM test). The executable behaviour is
 * covered by SearchHistoryRulesTest / SearchHistoryRepositoryTest (JVM) and SearchHistoryUiInstrumentedTest (emulator).
 */
class SearchHistoryWiringTest {

    private val dir = "src/main/kotlin/com/launchpoint/stereosine"
    private val route = File("$dir/ui/screen/search/GlobalSearchScreen.kt").readText().replace("\r\n", "\n")
    private val bar = File("$dir/ui/components/SearchTopAppBar.kt").readText().replace("\r\n", "\n")
    private val vm = File("$dir/ui/screen/search/GlobalSearchViewModel.kt").readText().replace("\r\n", "\n")

    @Test fun `typing only updates the query and never commits history`() {
        val typing = route.lineSequence().single { it.trimStart().startsWith("onQueryChange =") }
        assertTrue(typing.contains("viewModel::setSearchQuery"))
        assertFalse("typing must not write history: $typing", typing.contains("commitSearch"))
        assertFalse("setSearchQuery must not write history", vm.substringAfter("fun setSearchQuery").substringBefore("\n").contains("record"))
    }

    @Test fun `search dismissal stays plain navigation and does not touch history`() {
        assertTrue(route.contains("BackHandler(onBack = onNavigateBack)"))
        assertTrue(route.contains("onClose       = onNavigateBack,"))
        assertFalse(route.contains("onNavigateBack = {") || route.contains("onClose       = { viewModel"))
    }

    @Test fun `the keyboard Search action commits the current query and hides the keyboard`() {
        assertTrue(route.contains("onSearch      = { viewModel.commitSearch(localQuery); keyboard?.hide() }"))
        assertTrue(bar.contains("KeyboardActions(onSearch = { onSearch?.invoke() })"))
        assertTrue("the bar keeps its Search IME action", bar.contains("ImeAction.Search"))
    }

    @Test fun `the bar keeps its back arrow dismiss and X clear-text meanings`() {
        assertTrue(bar.contains("""contentDescription = "Close search""""))
        assertTrue(bar.contains("""contentDescription = "Clear search""""))
        assertTrue(bar.contains("onClick = { onQueryChange(\"\") }"))
        assertTrue(bar.contains("IconButton(onClick = onClose)"))
    }

    @Test fun `recent searches are shown only while the field is blank`() {
        assertTrue(route.contains("if (localQuery.isBlank()) {"))
        assertEquals(1, Regex("""RecentSearchesPanel\(""").findAll(route).count())
    }

    @Test fun `results taps commit the query and existing result callbacks still run`() {
        for (call in listOf("onAlbumClick(key)", "onArtistClick(key)", "onFolderClick(key)", "onPlaylistClick(id)", "onSmartCollectionClick(type)", "viewModel.playSearchResult(song)")) {
            assertTrue("missing: $call", route.contains(call))
        }
        assertTrue(route.contains("viewModel.commitSearch(localQuery); viewModel.playSearchResult(song)"))
    }

    @Test fun `every music-library search launcher opens the same single-top destination`() {
        val graph = File("$dir/ui/navigation/StereoSineNavGraph.kt").readText()
        for (screen in listOf("Home", "Library", "Songs", "Albums", "Artists", "Folders", "Playlists", "PlaylistDetails", "SettingsStatistics")) {
            val call = graph.substringAfter("${screen}Screen(", "").substringBefore("\n            )")
            assertTrue(screen, Regex("on(?:Global)?SearchClick\\s*=\\s*openSearch").containsMatchIn(call))
        }
        assertEquals(1, Regex("""composable\(Screen.GlobalSearch.route\)""").findAll(graph).count())
        assertTrue(graph.contains("navigate(Screen.GlobalSearch.route) { launchSingleTop = true }"))
    }

    @Test fun `destination state restores query and excludes results for earlier input`() {
        assertTrue(vm.contains("""savedStateHandle.getStateFlow("searchQuery", "")"""))
        assertTrue(vm.contains("""savedStateHandle["searchQuery"] = query"""))
        assertTrue(vm.contains("searchQuery.debounce(200)"))
        assertTrue(vm.contains(".mapLatest"))
        assertTrue(route.contains("filteredResults?.takeIf { it.query == localQuery }"))
        assertTrue(route.contains("viewModel.searchQuery.collectAsStateWithLifecycle()"))
    }

    @Test fun `history writes are not launched in the ViewModel scope`() {
        val calls = Regex("""searchHistoryRepository\.(commit|forget|clear)\(""").findAll(vm).count()
        assertEquals("commit, forget and clear are each wired once", 3, calls)
        assertFalse(Regex("""viewModelScope\.launch\s*\{\s*searchHistoryRepository""").containsMatchIn(vm))
        assertFalse(vm.contains("GlobalScope"))
        assertFalse(File("src/main/kotlin/com/launchpoint/stereosine/data/search/SearchHistoryRepository.kt").readText().contains("GlobalScope"))
    }

    @Test fun `search history is isolated from the app settings store and never logged`() {
        val repo = File("$dir/data/search/SearchHistoryRepository.kt").readText()
        assertTrue(repo.contains("""preferencesDataStore(name = "stereosine_search_history")"""))
        assertFalse(repo.contains("Log."))
        val backup = File("$dir/data/backup").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("SearchHistory") }.map { it.name }.toList()
        assertTrue("backup code must not touch search history: $backup", backup.isEmpty())
        assertFalse(File("src/main/AndroidManifest.xml").readText().contains("android.permission.INTERNET"))
    }
}
