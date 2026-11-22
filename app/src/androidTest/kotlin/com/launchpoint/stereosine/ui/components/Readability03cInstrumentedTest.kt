package com.launchpoint.stereosine.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import com.launchpoint.stereosine.data.model.ListeningAnalyticsEmptyReason
import com.launchpoint.stereosine.data.model.ListeningReportSummary
import com.launchpoint.stereosine.data.model.MonthYear
import com.launchpoint.stereosine.data.model.MonthlyReportSummary
import com.launchpoint.stereosine.ui.screen.monthlyreports.OverviewCards
import com.launchpoint.stereosine.ui.screen.reports.ListeningOverview
import com.launchpoint.stereosine.ui.testing.FixedViewport
import com.launchpoint.stereosine.ui.testing.assertNoMidWordBreak
import com.launchpoint.stereosine.ui.testing.assertNoOverlap
import com.launchpoint.stereosine.ui.testing.assumeHostFits
import com.launchpoint.stereosine.ui.testing.textLayout
import com.launchpoint.stereosine.ui.testing.unclippedBounds
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * UI-READABILITY-03C: overview-card labels on Listening Reports and Monthly Reports. Real Compose text layout and bounds against
 * the production composables with synthetic reports. Fixtures larger than the host window are skipped with a reason.
 */
@RunWith(Parameterized::class)
class Readability03cInstrumentedTest(
    private val widthDp: Int,
    private val heightDp: Int,
    private val fontScale: Float,
) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}x{1} fs{2}")
        fun configs() = listOf(
            Triple(320, 640, 1.0f), Triple(320, 640, 1.5f), Triple(320, 640, 2.0f),
            Triple(392, 851, 1.0f), Triple(392, 851, 2.0f),
            Triple(618, 320, 1.0f), Triple(618, 320, 2.0f),
        ).map { (w, h, f) -> arrayOf<Any>(w, h, f) }
    }

    @get:Rule val rule = createComposeRule()

    private fun show(content: @Composable () -> Unit) {
        assumeHostFits(widthDp, heightDp)
        rule.setContent { FixedViewport(widthDp, heightDp, fontScale) { Column(Modifier.verticalScroll(rememberScrollState())) { content() } } }
    }

    private val density get() = rule.density.density

    /** A label/value pair in the order it is laid out: two per row, left then right. */
    private class Cell(val label: String, val value: String)

    // ── Listening Reports ───────────────────────────────────────────────────────────────────────────────────────────────

    private fun listening(timeMs: Long, plays: Int, skips: Int, tracks: Int, artists: Int, albums: Int) = ListeningReportSummary(
        totalListeningTimeMs = timeMs, totalPlayCount = plays, totalSkipCount = skips, tracksPlayed = tracks,
        artistsPlayed = artists, albumsPlayed = albums, topSongs = emptyList(), topArtists = emptyList(), topAlbums = emptyList(),
        mostPlayedTrack = null, mostPlayedArtist = null, mostPlayedAlbum = null, mostSkippedTrack = null,
        recentlyPlayedSongs = emptyList(), recentlyActiveArtists = emptyList(),
    )

    private val listeningTypical = listening(12_300_000L, 111, 22, 333, 44, 55)
    private val listeningTypicalCells = listOf(
        Cell("Listening Time", "3h 25m"), Cell("Total Plays", "111"),
        Cell("Total Skips", "22"), Cell("Tracks Played", "333"),
        Cell("Artists Played", "44"), Cell("Albums Played", "55"),
    )

    // 123 days 23 hours, and five/six digit counts: the longest values the screen can realistically format.
    private val listeningLong = listening((123L * 24 + 23) * 3_600_000L, 123456, 98765, 45678, 23456, 34567)
    private val listeningLongCells = listOf(
        Cell("Listening Time", "123d 23h"), Cell("Total Plays", "123456"),
        Cell("Total Skips", "98765"), Cell("Tracks Played", "45678"),
        Cell("Artists Played", "23456"), Cell("Albums Played", "34567"),
    )

    // ── Monthly Reports ─────────────────────────────────────────────────────────────────────────────────────────────────

    private fun monthly(plays: Int, skips: Int, timeMs: Long, songs: Int, artists: Int, albums: Int, days: Int, busiestPlays: Int, avg: Double) =
        MonthlyReportSummary(
            month = MonthYear(2026, 11), dataAccuracy = MonthlyReportSummary.DataAccuracy.EVENT_BACKED, totalPlayCount = plays,
            totalSkipCount = skips, totalListeningTimeMs = timeMs, activeSongCount = songs, activeArtistCount = artists,
            activeAlbumCount = albums, listeningDaysCount = days, busiestDay = LocalDate.of(2026, 11, 3), busiestDayPlayCount = busiestPlays,
            averagePlaysPerActiveDay = avg, emptyStateReason = ListeningAnalyticsEmptyReason.HAS_ACTIVITY, topSongs = emptyList(),
            topArtists = emptyList(), topAlbums = emptyList(), mostSkippedTrack = null, recentlyPlayedInMonth = emptyList(),
        )

    private val monthlyTypical = monthly(111, 22, 12_300_000L, 333, 44, 55, 17, 66, 3.7)
    private val monthlyTypicalCells = listOf(
        Cell("Plays", "111"), Cell("Skips", "22"),
        Cell("Listening Time", "3h 25m"), Cell("Tracks", "333"),
        Cell("Artists", "44"), Cell("Albums", "55"),
        Cell("Listening Days", "17"), Cell("Busiest Day", "Nov 3"),
        Cell("Busiest Plays", "66"), Cell("Avg / Day", "3.7"),
    )

    private val monthlyLong = monthly(123456, 98765, (123L * 24 + 23) * 3_600_000L, 45678, 23456, 34567, 28, 12345, 4412.5)
    private val monthlyLongCells = listOf(
        Cell("Plays", "123456"), Cell("Skips", "98765"),
        Cell("Listening Time", "123d 23h"), Cell("Tracks", "45678"),
        Cell("Artists", "23456"), Cell("Albums", "34567"),
        Cell("Listening Days", "28"), Cell("Busiest Day", "Nov 3"),
        Cell("Busiest Plays", "12345"), Cell("Avg / Day", "4412.5"),
    )

    // ── Shared assertions ───────────────────────────────────────────────────────────────────────────────────────────────

    private fun node(text: String): SemanticsNodeInteraction = rule.onNode(hasText(text), useUnmergedTree = true)

    private fun SemanticsNodeInteraction.assertWholeText(what: String, maxLines: Int) {
        val layout = textLayout()
        val text = layout.layoutInput.text.text
        val ellipsized = (0 until layout.lineCount).filter { layout.isLineEllipsized(it) }
        assertTrue("$what '$text' is ellipsised on line(s) $ellipsized at ${widthDp}dp fs$fontScale", ellipsized.isEmpty())
        assertEquals("$what shows all of '$text'", text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        assertTrue("$what '$text' uses ${layout.lineCount} lines (max $maxLines)", layout.lineCount <= maxLines)
        assertNoMidWordBreak(what)
        // Accessibility text is the complete string.
        val semantic = fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        assertEquals("$what semantics", text, semantic)
        // The requested system scale is kept: nothing is laid out at another scale.
        assertEquals("$what font scale", fontScale, layout.layoutInput.density.fontScale, 0.001f)
    }

    private fun assertCells(cells: List<Cell>, what: String, labelMaxLines: Int = 2, valueMaxLines: Int = 2) {
        val width = widthDp * density
        val boxes = mutableListOf<Pair<String, Rect>>()
        cells.forEach { c ->
            val label = node(c.label); val value = node(c.value)
            label.assertWholeText("$what label", labelMaxLines)
            value.assertWholeText("$what value", valueMaxLines)
            val lb = label.unclippedBounds(); val vb = value.unclippedBounds()
            android.util.Log.i("R03C", "$what ${widthDp}dp fs$fontScale '${c.label}'=${label.textLayout().lineCount}L ${lb.width / density}dp '${c.value}'=${value.textLayout().lineCount}L ${vb.width / density}dp")
            assertTrue("$what '${c.label}' $lb must lie inside the ${widthDp}dp width", lb.left >= 0f && lb.right <= width + 1f)
            assertTrue("$what '${c.value}' $vb must lie inside the ${widthDp}dp width", vb.left >= 0f && vb.right <= width + 1f)
            assertTrue("$what '${c.value}' sits above '${c.label}'", vb.bottom <= lb.top + 1f)
            boxes += "label ${c.label}" to lb; boxes += "value ${c.value}" to vb
        }
        for (i in boxes.indices) for (j in i + 1 until boxes.size) assertNoOverlap(boxes[i].first, boxes[i].second, boxes[j].first, boxes[j].second)
        // Order is preserved: rows run top to bottom and, within a row, left cell before right cell.
        cells.chunked(2).forEachIndexed { row, pair ->
            val tops = pair.map { node(it.value).unclippedBounds().top }
            if (pair.size == 2) {
                val l = node(pair[0].label).unclippedBounds(); val r = node(pair[1].label).unclippedBounds()
                assertTrue("$what row $row: '${pair[0].label}' is left of '${pair[1].label}' ($l / $r)", l.right <= r.left + 1f || tops[0] < tops[1] - 1f)
            }
            if (row > 0) assertTrue("$what row $row is below the previous row",
                tops.min() >= node(cells[(row - 1) * 2].value).unclippedBounds().top - 1f)
        }
        // Bounded height: the whole block is far shorter than an unbounded one-word-per-line layout.
        val top = boxes.minOf { it.second.top }; val bottom = boxes.maxOf { it.second.bottom }
        val blockDp = (bottom - top) / density
        assertTrue("$what block ${blockDp}dp exceeds the bounded maximum", blockDp <= cells.size / 2 * 150f)
    }

    // ── Tests ───────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun listening_reports_overview_labels_and_values_are_whole() {
        show { ListeningOverview(report = listeningTypical) }
        assertCells(listeningTypicalCells, "Reports")
    }

    @Test fun listening_reports_overview_survives_long_values() {
        show { ListeningOverview(report = listeningLong) }
        assertCells(listeningLongCells, "Reports long")
    }

    @Test fun monthly_reports_overview_labels_and_values_are_whole() {
        show { OverviewCards(report = monthlyTypical) }
        assertCells(monthlyTypicalCells, "Monthly")
    }

    @Test fun monthly_reports_overview_survives_long_values() {
        show { OverviewCards(report = monthlyLong) }
        assertCells(monthlyLongCells, "Monthly long")
    }
}
