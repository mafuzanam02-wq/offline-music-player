package com.launchpoint.stereosine.data.search

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** One remembered committed search. [query] is the display text (trimmed, inner whitespace collapsed, original casing). */
data class SearchHistoryEntry(val query: String, val lastUsedMillis: Long)

/**
 * SEARCH-01: pure retention rules for the recent-searches list (no Android, no clock, fully unit-testable).
 *
 * - up to [MAX_ENTRIES] distinct searches, newest first;
 * - an entry expires [RETENTION_MILLIS] (90 days) after its most recent use;
 * - blank searches are ignored; text is trimmed and inner whitespace runs collapse to one space;
 * - two searches that differ only by case are the same search (the latest display casing wins).
 */
object SearchHistoryRules {
    const val MAX_ENTRIES = 30
    const val RETENTION_DAYS = 90
    const val RETENTION_MILLIS = RETENTION_DAYS * 24L * 60 * 60 * 1_000

    /** The display form of [raw], or null when it is blank. */
    fun normalize(raw: String): String? =
        raw.trim().replace(WHITESPACE, " ").takeIf { it.isNotEmpty() }

    /** Case-insensitive identity of an already-normalized query. */
    fun key(normalized: String): String = normalized.lowercase(Locale.ROOT)

    /** Drops expired entries, collapses duplicates (most recent wins), orders newest first, caps at [MAX_ENTRIES]. Deterministic. */
    fun prune(entries: List<SearchHistoryEntry>, nowMillis: Long): List<SearchHistoryEntry> =
        entries
            .filter { nowMillis - it.lastUsedMillis < RETENTION_MILLIS }
            .sortedWith(compareByDescending<SearchHistoryEntry> { it.lastUsedMillis }.thenBy { key(it.query) }.thenBy { it.query })
            .distinctBy { key(it.query) }
            .take(MAX_ENTRIES)

    /** The list after committing [raw] at [nowMillis]; unchanged (but pruned) when [raw] is blank. */
    fun record(entries: List<SearchHistoryEntry>, raw: String, nowMillis: Long): List<SearchHistoryEntry> {
        val query = normalize(raw) ?: return prune(entries, nowMillis)
        // Drop any same-search entry first, so the NEWEST submission always wins - even at the same millisecond - and the
        // latest display casing is kept. (Never rely on timestamp ordering to pick between duplicates.)
        return prune(remove(entries, query) + SearchHistoryEntry(query, nowMillis), nowMillis)
    }

    fun remove(entries: List<SearchHistoryEntry>, raw: String): List<SearchHistoryEntry> {
        val target = normalize(raw)?.let(::key) ?: return entries
        return entries.filterNot { key(it.query) == target }
    }

    fun encode(entries: List<SearchHistoryEntry>): String =
        JSONArray().also { array ->
            entries.forEach { array.put(JSONObject().put("q", it.query).put("t", it.lastUsedMillis)) }
        }.toString()

    /** Tolerant decode: corrupt or unknown content yields an empty list rather than a crash. */
    fun decode(json: String?): List<SearchHistoryEntry> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val q = normalize(o.optString("q")) ?: continue
                    if (!o.has("t")) continue
                    add(SearchHistoryEntry(q, o.optLong("t")))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private val WHITESPACE = Regex("""\s+""")
}
