package com.launchpoint.stereosine.data.search

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.core.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Real DataStore file, controllable clock; "restart" = a brand new DataStore and repository on the same file. */
class SearchHistoryRepositoryTest {

    @get:Rule val temp = TemporaryFolder()

    private val day = 24L * 60 * 60 * 1_000
    private var now = 1_000 * day
    private lateinit var file: File
    private val scopes = mutableListOf<CoroutineScope>()
    private lateinit var lastStore: DataStore<Preferences>

    private fun open(): Pair<SearchHistoryRepository, DataStore<Preferences>> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }
        lastStore = store
        return SearchHistoryRepository(store, { now }) to store
    }

    private fun repo(): SearchHistoryRepository {
        if (!::file.isInitialized) file = temp.newFile("search-history.preferences_pb").also { it.delete() }
        return open().first
    }

    private fun restart(): SearchHistoryRepository {
        scopes.forEach { scope -> runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() } }
        scopes.clear()
        return open().first
    }

    private fun SearchHistoryRepository.list() = runBlocking { entries.first() }.map { it.query }

    @Test fun `a committed search is persisted once`() = runBlocking {
        val r = repo()
        r.record("queen")
        assertEquals(listOf("queen"), r.list())
    }

    @Test fun `blank searches are never persisted`() = runBlocking {
        val r = repo()
        r.record("")
        r.record("   ")
        assertEquals(emptyList<String>(), r.list())
    }

    @Test fun `case-insensitive duplicates and recency ordering`() = runBlocking {
        val r = repo()
        r.record("Queen"); now += 1
        r.record("abba"); now += 1
        r.record("QUEEN")
        assertEquals(listOf("QUEEN", "abba"), r.list())
    }

    @Test fun `history is capped at 30 entries`() = runBlocking {
        val r = repo()
        for (i in 1..40) { r.record("q$i"); now += 1 }
        assertEquals(30, r.list().size)
        assertEquals("q40", r.list().first())
    }

    @Test fun `entries older than 90 days are hidden on read and gone after the next write`() = runBlocking {
        val r = repo()
        r.record("old")
        now += 91 * day
        assertEquals(emptyList<String>(), r.list())
        r.record("new")
        assertEquals(listOf("new"), r.list())
    }

    @Test fun `history survives an app restart`() = runBlocking {
        val first = repo()
        first.record("one"); now += 1
        first.record("two")
        val reopened = restart()
        assertEquals(listOf("two", "one"), reopened.list())
    }

    @Test fun `an individual removal affects only that entry and survives a restart`() = runBlocking {
        val r = repo()
        r.record("one"); now += 1
        r.record("two"); now += 1
        r.record("three")
        r.remove("TWO")
        assertEquals(listOf("three", "one"), r.list())
        assertEquals(listOf("three", "one"), restart().list())
    }

    @Test fun `clear all empties the history and survives a restart`() = runBlocking {
        val r = repo()
        r.record("one"); r.record("two")
        r.clearAll()
        assertEquals(emptyList<String>(), r.list())
        assertEquals(emptyList<String>(), restart().list())
    }

    @Test fun `rapid duplicate submissions store a single entry`() = runBlocking {
        val r = repo()
        (1..40).map { i -> async(Dispatchers.IO) { r.record(if (i % 2 == 0) "Same Query" else "same  query") } }.awaitAll()
        assertEquals(1, r.list().size)
    }

    @Test fun `history lives in its own store with a single key and no listening or settings keys`() = runBlocking {
        val r = repo()
        r.record("anything")
        assertEquals(listOf("recent_searches_v1"), lastStore.data.first().asMap().keys.map { it.name })
        assertTrue(file.name.contains("search-history"))
    }

    // ---- write durability: the write must outlive the scope that started it ----

    /** A store whose writes are held until [gate] opens, so a write can be "in flight" while its initiator is cancelled. */
    private class GatedStore(private val inner: DataStore<Preferences>, val gate: CompletableDeferred<Unit>) : DataStore<Preferences> {
        override val data = inner.data
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            gate.await()
            return inner.updateData(transform)
        }
    }

    private fun gatedRepo(): Pair<SearchHistoryRepository, CompletableDeferred<Unit>> {
        file = temp.newFile("search-history-gated.preferences_pb").also { it.delete() }
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        val real = PreferenceDataStoreFactory.create(scope = ioScope) { file }
        val gate = CompletableDeferred<Unit>()
        val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return SearchHistoryRepository(GatedStore(real, gate), { now }, writeScope) to gate
    }

    @Test fun `a committed search survives cancellation of the initiating ViewModel scope`() = runBlocking {
        val (r, gate) = gatedRepo()
        val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var write: Job? = null
        viewModelScope.launch { write = r.commit("queen") }.join()   // what the ViewModel does on a result tap
        viewModelScope.cancel()                                       // the destination is popped; viewModelScope dies
        gate.complete(Unit)                                           // the write only now reaches the store
        withTimeout(5_000) { write!!.join() }
        assertEquals(listOf("queen"), r.list())
    }

    @Test fun `negative control - a write launched in the cancelled scope itself is lost`() = runBlocking {
        val (r, gate) = gatedRepo()
        val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val old = viewModelScope.launch { r.record("queen") }  // the pre-correction behaviour
        delay(200)                                              // let it start and park on the gate
        viewModelScope.cancel()
        gate.complete(Unit)
        old.join()
        assertEquals("proves the test would catch the original bug", emptyList<String>(), r.list())
    }

    @Test fun `forget and clear also survive cancellation of the caller`() = runBlocking {
        val (r, gate) = gatedRepo()
        gate.complete(Unit)
        r.record("one"); r.record("two")
        val caller = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var job: Job? = null
        caller.launch { job = r.forget("one") }.join()
        caller.cancel()
        withTimeout(5_000) { job!!.join() }
        assertEquals(listOf("two"), r.list())
        r.clear().join()
        assertEquals(emptyList<String>(), r.list())
    }

    @Test fun `commit returns immediately without waiting for the write`() = runBlocking {
        val (r, gate) = gatedRepo()
        val job = r.commit("queen")        // the gate is still closed; this must not block the caller
        assertTrue(job.isActive)
        gate.complete(Unit)
        job.join()
        assertEquals(listOf("queen"), r.list())
    }

    @Test fun `commit ignores blank searches and rapid duplicate commits store one entry`() = runBlocking {
        val r = repo()
        r.commit("   ").join()
        (1..30).map { r.commit(if (it % 2 == 0) "Same" else "same") }.forEach { it.join() }
        assertEquals(1, r.list().size)
    }

    // ---- expiry semantics: evaluated per emission / per collection, never by a timer ----

    @Test fun `expiry is evaluated on each new collection`() = runBlocking {
        val r = repo()
        r.record("old")
        assertEquals(listOf("old"), r.list())
        now += 91 * day
        assertEquals("a newly opened search no longer shows it", emptyList<String>(), r.list())
    }

    @Test fun `a screen that stays open re-evaluates expiry only on the next emission - there is no timer`() = runBlocking {
        val r = repo()
        r.record("old")
        val emissions = java.util.concurrent.CopyOnWriteArrayList<List<String>>()
        val collector = r.entries.onEach { emissions += it.map { e -> e.query } }
            .launchIn(CoroutineScope(Dispatchers.IO).also { scopes += it })
        withTimeout(5_000) { while (emissions.lastOrNull() != listOf("old")) delay(10) }
        now += 91 * day
        delay(300)
        assertEquals("nothing is emitted just because time passed", listOf(listOf("old")), emissions.toList())
        r.record("new")                                   // the next emission applies the 90-day filter
        withTimeout(5_000) { while (emissions.lastOrNull() != listOf("new")) delay(10) }
        collector.cancel()
        assertEquals(listOf("new"), emissions.last())
    }
}
