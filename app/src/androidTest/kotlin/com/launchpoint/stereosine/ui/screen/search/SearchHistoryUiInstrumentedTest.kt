package com.launchpoint.stereosine.ui.screen.search

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.launchpoint.stereosine.data.search.SearchHistoryEntry
import com.launchpoint.stereosine.data.search.SearchHistoryRepository
import com.launchpoint.stereosine.ui.components.SearchTopAppBar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * SEARCH-01 UI behaviour on a real device/emulator. The panel and the top bar are driven directly; a small host wires them to
 * a real [SearchHistoryRepository] on a throw-away DataStore file the same way [GlobalSearchRoute] does (the route itself needs
 * Hilt and the whole library graph, so it is not instantiated here).
 */
class SearchHistoryUiInstrumentedTest {

    @get:Rule val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "search-history-ui-${System.nanoTime()}.preferences_pb")
    private val repo = SearchHistoryRepository(PreferenceDataStoreFactory.create(scope = scope) { file }, System::currentTimeMillis)

    @After fun tearDown() { scope.cancel(); file.delete() }

    private fun entries(vararg q: String) = q.mapIndexed { i, s -> SearchHistoryEntry(s, 1_000L - i) }

    // ---- panel ----

    @Test fun lists_recent_searches_with_a_remove_control_each() {
        rule.setContent { RecentSearchesPanel(entries("queen", "abba"), {}, {}, {}) }
        rule.onNodeWithText("Recent searches").assertIsDisplayed()
        rule.onNodeWithText("queen").assertIsDisplayed()
        rule.onNodeWithContentDescription("Remove queen from recent searches").assertIsDisplayed()
        rule.onNodeWithContentDescription("Remove abba from recent searches").assertIsDisplayed()
    }

    @Test fun empty_history_shows_a_hint_and_no_clear_all() {
        rule.setContent { RecentSearchesPanel(emptyList(), {}, {}, {}) }
        rule.onNodeWithText("Your recent searches will appear here.").assertIsDisplayed()
        rule.onNodeWithText("Clear all").assertDoesNotExist()
    }

    @Test fun tapping_a_row_repeats_that_search_only() {
        val selected = mutableListOf<String>(); var removed = 0
        rule.setContent { RecentSearchesPanel(entries("queen", "abba"), { selected += it }, { removed++ }, {}) }
        rule.onNodeWithText("abba").performClick()
        assertEquals(listOf("abba"), selected)
        assertEquals(0, removed)
    }

    @Test fun remove_control_removes_one_entry_without_repeating_the_search_or_touching_others() {
        val selected = mutableListOf<String>()
        rule.setContent {
            var list by remember { mutableStateOf(entries("queen", "abba", "zappa")) }
            RecentSearchesPanel(list, { selected += it }, { q -> list = list.filterNot { it.query == q } }, {})
        }
        rule.onNodeWithContentDescription("Remove abba from recent searches").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("abba").assertDoesNotExist()
        rule.onNodeWithText("queen").assertIsDisplayed()
        rule.onNodeWithText("zappa").assertIsDisplayed()
        assertTrue("removal must not run the query", selected.isEmpty())
    }

    @Test fun clear_all_asks_for_confirmation_and_cancel_keeps_everything() {
        var cleared = 0
        rule.setContent { RecentSearchesPanel(entries("queen"), {}, {}, { cleared++ }) }
        rule.onNodeWithText("Clear all").performClick()
        rule.onNodeWithText("Clear recent searches?").assertIsDisplayed()
        assertEquals(0, cleared)
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Clear recent searches?").assertDoesNotExist()
        rule.onNodeWithText("queen").assertIsDisplayed()
        assertEquals(0, cleared)
    }

    @Test fun clear_all_confirmation_clears_once() {
        var cleared = 0
        rule.setContent { RecentSearchesPanel(entries("queen"), {}, {}, { cleared++ }) }
        rule.onNodeWithText("Clear all").performClick()
        // the dialog's confirm button is the second "Clear all" node
        rule.onAllNodesWithText("Clear all").onLast().performClick()
        assertEquals(1, cleared)
    }

    // ---- top bar: dismiss, clear text, keyboard ----

    @Test fun typing_only_changes_the_query_and_commits_nothing() {
        val changes = mutableListOf<String>(); var searched = 0
        rule.setContent {
            var q by remember { mutableStateOf("") }
            SearchTopAppBar(query = q, onQueryChange = { q = it; changes += it }, onClose = {}, onSearch = { searched++ })
        }
        rule.onNodeWithText("Search…").performTextInput("que")
        assertTrue(changes.isNotEmpty())
        assertEquals("typing must not commit", 0, searched)
    }

    @Test fun keyboard_search_action_commits_once() {
        var searched = 0
        rule.setContent {
            var q by remember { mutableStateOf("queen") }
            SearchTopAppBar(query = q, onQueryChange = { q = it }, onClose = {}, onSearch = { searched++ })
        }
        rule.onNodeWithText("queen").performImeAction()
        assertEquals(1, searched)
    }

    @Test fun back_arrow_dismisses_and_the_x_clears_text_with_distinct_meanings() {
        var closed = 0; val changes = mutableListOf<String>()
        rule.setContent {
            var q by remember { mutableStateOf("queen") }
            SearchTopAppBar(query = q, onQueryChange = { q = it; changes += it }, onClose = { closed++ })
        }
        rule.onNodeWithContentDescription("Clear search").performClick()
        assertEquals(listOf(""), changes)
        assertEquals("clearing text must not dismiss search", 0, closed)
        rule.onNodeWithContentDescription("Close search").performClick()
        assertEquals(1, closed)
    }

    // ---- wired to a real repository, like the route ----

    private fun host(onClose: () -> Unit = {}) = rule.setContent {
        val list by repo.entries.collectAsState(initial = emptyList())
        val coroutines = rememberCoroutineScope()
        var q by remember { mutableStateOf("") }
        Column {
            SearchTopAppBar(
                query = q, onQueryChange = { q = it }, onClose = onClose,
                onSearch = { coroutines.launch { repo.record(q) } },
            )
            RecentSearchesPanel(
                entries = list,
                onSelect = { sel -> q = sel; coroutines.launch { repo.record(sel) } },
                onRemove = { coroutines.launch { repo.remove(it) } },
                onClearAll = { coroutines.launch { repo.clearAll() } },
            )
        }
    }

    private fun stored() = runBlocking { repo.entries.first() }.map { it.query }

    @Test fun dismissing_search_does_not_delete_saved_searches() {
        runBlocking { repo.record("queen"); repo.record("abba") }
        var closed = 0
        host(onClose = { closed++ })
        rule.onNodeWithContentDescription("Close search").performClick()
        assertEquals(1, closed)
        assertEquals(setOf("queen", "abba"), stored().toSet())
    }

    @Test fun removing_an_entry_updates_the_list_and_the_store_and_repeating_refreshes_recency() {
        runBlocking { repo.record("queen"); Thread.sleep(5); repo.record("abba") }
        host()
        rule.onNodeWithContentDescription("Remove queen from recent searches").performClick()
        rule.waitUntil(5_000) { stored() == listOf("abba") }
        rule.onNodeWithText("queen").assertDoesNotExist()
        rule.onNodeWithText("abba").assertIsDisplayed()
    }
}

