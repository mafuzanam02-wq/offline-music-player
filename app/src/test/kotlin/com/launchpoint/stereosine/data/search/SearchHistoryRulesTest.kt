package com.launchpoint.stereosine.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHistoryRulesTest {

    private val day = 24L * 60 * 60 * 1_000
    private val now = 1_000 * day

    private fun record(entries: List<SearchHistoryEntry>, q: String, at: Long = now) = SearchHistoryRules.record(entries, q, at)
    private fun queries(entries: List<SearchHistoryEntry>) = entries.map { it.query }

    @Test fun `a committed search is stored once`() {
        val list = record(emptyList(), "queen")
        assertEquals(listOf(SearchHistoryEntry("queen", now)), list)
    }

    @Test fun `blank and empty searches are rejected`() {
        assertNull(SearchHistoryRules.normalize(""))
        assertNull(SearchHistoryRules.normalize("   \t\n "))
        val start = listOf(SearchHistoryEntry("keep", now))
        assertEquals(start, record(start, "   "))
    }

    @Test fun `whitespace is trimmed and inner runs collapse`() {
        assertEquals("pink floyd", SearchHistoryRules.normalize("  pink    floyd \n"))
        assertEquals(listOf("pink floyd"), queries(record(emptyList(), "  pink \t floyd  ")))
    }

    @Test fun `case-only duplicates are one search and the latest display casing wins`() {
        var list = record(emptyList(), "Queen", now)
        list = record(list, "queen", now + 1)
        list = record(list, "QUEEN  ", now + 2)
        assertEquals(listOf(SearchHistoryEntry("QUEEN", now + 2)), list)
    }

    @Test fun `the latest submission wins even at exactly the same millisecond`() {
        var list = record(emptyList(), "Queen", now)
        list = record(list, "QUEEN", now)
        assertEquals(listOf(SearchHistoryEntry("QUEEN", now)), list)
        // the other direction: submission order, not casing or timestamps, decides
        list = record(record(emptyList(), "QUEEN", now), "Queen", now)
        assertEquals(listOf(SearchHistoryEntry("Queen", now)), list)
    }

    @Test fun `distinct queries at an identical timestamp are all kept in a deterministic order`() {
        val a = record(record(record(emptyList(), "banana", now), "Apple", now), "cherry", now)
        val b = record(record(record(emptyList(), "cherry", now), "banana", now), "Apple", now)
        assertEquals(listOf("Apple", "banana", "cherry"), queries(a))
        assertEquals("independent of insertion order", queries(a), queries(b))
        assertEquals(3, a.size)
    }

    @Test fun `a same-millisecond duplicate does not disturb the entry limit`() {
        var list = emptyList<SearchHistoryEntry>()
        for (i in 1..30) list = record(list, "q$i", now + i)
        list = record(list, "Q30", now + 30)
        assertEquals(30, list.size)
        assertEquals("Q30", list.first().query)
    }

    @Test fun `resubmitting refreshes recency and moves the entry to the top`() {
        var list = record(emptyList(), "a", now)
        list = record(list, "b", now + 1)
        list = record(list, "c", now + 2)
        assertEquals(listOf("c", "b", "a"), queries(list))
        list = record(list, "A", now + 3)
        assertEquals(listOf("A", "c", "b"), queries(list))
    }

    @Test fun `at most 30 entries are kept and the oldest are dropped`() {
        var list = emptyList<SearchHistoryEntry>()
        for (i in 1..45) list = record(list, "q$i", now + i)
        assertEquals(30, list.size)
        assertEquals("q45", list.first().query)
        assertEquals("q16", list.last().query)
        assertTrue(list.none { it.query == "q15" })
    }

    @Test fun `entries expire 90 days after their most recent use`() {
        val old = SearchHistoryEntry("old", now - 90 * day)          // exactly 90 days: expired
        val edge = SearchHistoryEntry("edge", now - 90 * day + 1)    // 1 ms inside the window: kept
        assertEquals(listOf("edge"), queries(SearchHistoryRules.prune(listOf(old, edge), now)))
    }

    @Test fun `using an old entry again renews its 90 days`() {
        val old = SearchHistoryEntry("song", now - 89 * day)
        val renewed = record(listOf(old), "song", now)
        assertEquals(listOf(SearchHistoryEntry("song", now)), SearchHistoryRules.prune(renewed, now + 89 * day))
    }

    @Test fun `recording prunes expired entries`() {
        val list = record(listOf(SearchHistoryEntry("ancient", now - 200 * day)), "fresh", now)
        assertEquals(listOf("fresh"), queries(list))
    }

    @Test fun `pruning is deterministic for equal timestamps`() {
        val tie = listOf(SearchHistoryEntry("b", now), SearchHistoryEntry("a", now), SearchHistoryEntry("C", now))
        assertEquals(queries(SearchHistoryRules.prune(tie, now)), queries(SearchHistoryRules.prune(tie.reversed(), now)))
        assertEquals(listOf("a", "b", "C"), queries(SearchHistoryRules.prune(tie, now)))
    }

    @Test fun `removal matches case-insensitively and leaves the others`() {
        val list = record(record(record(emptyList(), "one", now), "two", now + 1), "three", now + 2)
        assertEquals(listOf("three", "one"), queries(SearchHistoryRules.remove(list, " TWO ")))
        assertEquals(list, SearchHistoryRules.remove(list, "missing"))
    }

    @Test fun `encode and decode round trip`() {
        val list = listOf(SearchHistoryEntry("naïve \"quoted\" café", now), SearchHistoryEntry("b", now - 5))
        assertEquals(list, SearchHistoryRules.decode(SearchHistoryRules.encode(list)))
    }

    @Test fun `corrupt or foreign stored content decodes to an empty or partial list without crashing`() {
        assertEquals(emptyList<SearchHistoryEntry>(), SearchHistoryRules.decode(null))
        assertEquals(emptyList<SearchHistoryEntry>(), SearchHistoryRules.decode("not json"))
        assertEquals(emptyList<SearchHistoryEntry>(), SearchHistoryRules.decode("""{"a":1}"""))
        assertEquals(listOf("ok"), queries(SearchHistoryRules.decode("""[{"q":"ok","t":5},{"q":" ","t":1},{"t":3},7]""")))
    }
}
