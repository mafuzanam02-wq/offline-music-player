package com.launchpoint.stereosine.data.search

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dedicated store for search history: a separate DataStore file, so it is never mixed into the app settings store that the
 * StereoSine backup exports (search history is not backed up, not part of listening history and not uploaded anywhere).
 */
private val Context.searchHistoryDataStore by preferencesDataStore(name = "stereosine_search_history")

/**
 * SEARCH-01: locally persisted recent searches. Only COMMITTED searches are recorded by callers (keyboard Search, a tapped
 * result, a tapped recent entry) - never keystrokes. Query text is never logged.
 *
 * Write durability: the fire-and-forget operations ([commit], [forget], [clear]) run in a scope owned by this singleton, NOT in
 * the caller's scope. A result tap navigates away immediately and the search ViewModel's scope is cancelled when the destination
 * is popped; a write launched there could be cancelled before it reaches disk. The owned scope holds no Activity, ViewModel or
 * Context reference (only the DataStore), so nothing leaks. The calls return at once (the main thread is never blocked), and
 * every write still goes through [DataStore.edit], which is atomic and serialized, so rapid duplicates cannot corrupt the list.
 *
 * Expiry semantics (90 days): entries are filtered when [entries] is evaluated, i.e. each time DataStore emits (every write) and
 * each time the flow is newly collected (opening search). A screen that stays continuously open does NOT re-evaluate expiry on
 * its own - there is deliberately no timer or polling. An entry that crosses the 90-day line while search is open disappears on
 * the next write or the next time search is opened; expired entries are also physically removed by the next write.
 */
@Singleton
class SearchHistoryRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val nowMillis: () -> Long,
    private val writeScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Inject constructor(@ApplicationContext context: Context) :
        this(context.applicationContext.searchHistoryDataStore, System::currentTimeMillis)

    /** Newest first. See the class comment for exactly when expired entries are filtered out. */
    val entries: Flow<List<SearchHistoryEntry>> = dataStore.data.map { prefs ->
        SearchHistoryRules.prune(SearchHistoryRules.decode(prefs[KEY]), nowMillis())
    }

    /** Records a committed search without blocking and without depending on the caller's lifecycle. */
    fun commit(query: String): Job = launchWrite { record(query) }

    /** Removes one entry without blocking and without depending on the caller's lifecycle. */
    fun forget(query: String): Job = launchWrite { remove(query) }

    /** Clears everything without blocking and without depending on the caller's lifecycle. */
    fun clear(): Job = launchWrite { clearAll() }

    suspend fun record(query: String) {
        if (SearchHistoryRules.normalize(query) == null) return
        dataStore.edit { prefs ->
            prefs[KEY] = SearchHistoryRules.encode(
                SearchHistoryRules.record(SearchHistoryRules.decode(prefs[KEY]), query, nowMillis()),
            )
        }
    }

    suspend fun remove(query: String) {
        dataStore.edit { prefs ->
            prefs[KEY] = SearchHistoryRules.encode(
                SearchHistoryRules.prune(SearchHistoryRules.remove(SearchHistoryRules.decode(prefs[KEY]), query), nowMillis()),
            )
        }
    }

    suspend fun clearAll() {
        dataStore.edit { prefs -> prefs.remove(KEY) }
    }

    /** A disk failure while saving a convenience list must never crash the app; nothing about the query is logged. */
    private fun launchWrite(block: suspend () -> Unit): Job = writeScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // best effort
        }
    }

    private companion object {
        val KEY = stringPreferencesKey("recent_searches_v1")
    }
}
