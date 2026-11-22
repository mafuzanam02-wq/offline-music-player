package com.launchpoint.stereosine.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.launchpoint.stereosine.data.model.Song
import com.launchpoint.stereosine.ui.screen.settings.InsightsDestinationGrid
import com.launchpoint.stereosine.ui.screen.settings.InsightsHubSummaryRow
import com.launchpoint.stereosine.ui.screen.settings.InsightsHubUiState
import com.launchpoint.stereosine.ui.testing.FixedViewport
import com.launchpoint.stereosine.ui.testing.assertInside
import com.launchpoint.stereosine.ui.testing.assertNoMidWordBreak
import com.launchpoint.stereosine.ui.testing.assertNoOverlap
import com.launchpoint.stereosine.ui.testing.assumeHostFits
import com.launchpoint.stereosine.ui.testing.textLayout
import com.launchpoint.stereosine.ui.testing.visibleBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * UI-READABILITY-03B (F-09 song-title ellipsis, F-15 Insights hub mid-word breaks). Real bounds and real text layout against the
 * production composables with synthetic content. Fixtures larger than the host window are skipped with a reason.
 */
@RunWith(Parameterized::class)
class Readability03bInstrumentedTest(
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
        rule.setContent { FixedViewport(widthDp, heightDp, fontScale) { content() } }
    }

    private val density get() = rule.density.density

    private fun song(id: Long, title: String, artist: String = "Test Artist", album: String = "Test Album") = Song(
        id = id, title = title, artist = artist, album = album, albumId = id, duration = 200_000L,
        uri = "content://test/$id", dateAdded = 0L, trackNumber = 1, year = 2024,
    )

    private val ordinary = listOf("Midnight Drive", "Normal Song One", "Blue Hour Waltz", "Seven Nation Army")
    private val long = "A Very Long Track Title That Keeps Going: Part Two (Extended Live Orchestral Version, Remastered 2024) — Bonus Edition"
    private val unicode = "夜に駆ける – Café Crème Brûlée ♪ (Édition spéciale)"

    private fun rows(songs: List<Song>, onPlay: () -> Unit = {}, onMore: () -> Unit = {}) = @Composable {
        CompositionLocalProvider(LocalShowAlbumInSongRows provides true) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                songs.forEach { s ->
                    SongRow(
                        song = s, isCurrent = false, isFavorite = false, onClick = onPlay, onToggleFavorite = {},
                        onOpenDetails = {}, modifier = Modifier.fillMaxWidth(), showFavoriteButton = false, onMoreClick = onMore,
                    )
                }
            }
        }
    }

    // ── F-09 ────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun f09_ordinary_titles_are_not_truncated_and_never_break_mid_word() {
        show(rows(ordinary.mapIndexed { i, title -> song(i + 1L, title) }))
        ordinary.forEach { title ->
            val t = rule.onNode(hasText(title) and androidx.compose.ui.test.hasNoClickAction(), useUnmergedTree = true)
            val layout = t.textLayout()
            val ellipsized = (0 until layout.lineCount).filter { layout.isLineEllipsized(it) }
            android.util.Log.i("R03B", "f09 ${widthDp}dp fs$fontScale '$title': lines=${layout.lineCount} ellipsized=$ellipsized width=${t.visibleBounds().width / density}dp")
            assertTrue("ordinary title '$title' is ellipsised on line(s) $ellipsized at ${widthDp}dp fs$fontScale", ellipsized.isEmpty())
            t.assertNoMidWordBreak("title '$title'")
            assertTrue("title '$title' uses at most 2 lines (was ${layout.lineCount})", layout.lineCount <= 2)
        }
    }

    @Test fun f09_long_and_unicode_titles_are_bounded_to_two_lines_and_the_full_text_stays_in_semantics() {
        show(rows(listOf(song(10, long), song(11, unicode))))
        for (title in listOf(long, unicode)) {
            val t = rule.onNode(hasText(title) and androidx.compose.ui.test.hasNoClickAction(), useUnmergedTree = true)
            val layout = t.textLayout()
            assertTrue("title lines ${layout.lineCount} must be bounded to 2", layout.lineCount <= 2)
            // Full title is exposed through the accessibility text even when the last visible line is ellipsised.
            val texts = t.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
            assertEquals("full title in semantics", title, texts)
            // Font scale is never reduced: the layout runs at exactly the requested scale.
            assertEquals(fontScale, layout.layoutInput.density.fontScale, 0.001f)
        }
    }

    @Test fun f09_row_geometry_artwork_title_artist_album_and_more_button_do_not_collide_and_targets_stay_large() {
        val plays = mutableListOf<String>()
        var more = 0
        show(rows(listOf(song(20, long, artist = "The Long Named Artist Collective", album = "Album Name Of Considerable Length")),
            onPlay = { plays += "play" }, onMore = { more++ }))
        val rowNode = rule.onNode(hasClickAction() and hasText(long), useUnmergedTree = false)
        val rowBounds = rowNode.visibleBounds()
        val title = rule.onNode(hasText(long) and androidx.compose.ui.test.hasNoClickAction(), useUnmergedTree = true)
        val artist = rule.onNode(hasText("The Long Named Artist Collective"), useUnmergedTree = true)
        val album = rule.onNode(hasText("Album Name Of Considerable Length"), useUnmergedTree = true)
        val moreBtn = rule.onNode(hasClickAction() and hasContentDescription("More actions"))
        val window = Rect(0f, 0f, widthDp * density, heightDp * density)
        val tb = title.visibleBounds(); val ab = artist.visibleBounds(); val lb = album.visibleBounds(); val mb = moreBtn.visibleBounds(); val mt = moreBtn.fetchSemanticsNode().touchBoundsInRoot
        android.util.Log.i("R03B", "f09 geometry ${widthDp}dp fs$fontScale rowH=${rowBounds.height / density}dp titleW=${tb.width / density}dp moreBtn=${mb.width / density}x${mb.height / density}dp")
        assertInside("row", rowBounds, window)
        assertInside("title", tb, rowBounds); assertInside("artist", ab, rowBounds); assertInside("album", lb, rowBounds); assertInside("more", mb, rowBounds)
        assertNoOverlap("title", tb, "artist", ab); assertNoOverlap("artist", ab, "album", lb)
        assertNoOverlap("title", tb, "more", mb); assertNoOverlap("artist", ab, "more", mb); assertNoOverlap("album", lb, "more", mb)
        assertTrue("row target ${rowBounds.height / density}dp must be >= 48dp", rowBounds.height >= 48f * density - 1f)
        assertTrue("more button touch target ${mt.width / density}x${mt.height / density}dp must be >= 48dp", mt.width >= 48f * density - 1f && mt.height >= 48f * density - 1f)
        // A bounded row: 2 title lines + artist + album at 2.0x, plus padding. Never unlimited growth.
        assertTrue("row height ${rowBounds.height / density}dp exceeds the bounded maximum", rowBounds.height <= 260f * density)
        assertTrue("title must not be taller than 2 lines", title.textLayout().lineCount <= 2)
        // combinedClickable waits out the double-tap window before delivering a single click.
        rowNode.performClick(); rule.mainClock.advanceTimeBy(1_000); rule.waitForIdle(); assertEquals(listOf("play"), plays)
        moreBtn.performClick(); assertEquals(1, more)
    }

    // ── F-15 ────────────────────────────────────────────────────────────────────────────────────────────────────────────

    private val content = InsightsHubUiState.Content(
        totalPlayCount = 1234, totalListeningTimeMs = (1L * 86_400_000L) + (13L * 3_600_000L),
        currentStreakDays = 12, forgottenGemsCount = 3, recentlyPlayedCount = 4, neverPlayedCount = 5,
        thisMonthPlayCount = 10, thisMonthListeningTimeMs = 3_600_000L, thisMonthTopTrackTitle = "x",
        mostActiveDayOfWeek = null, mostActiveHour = null,
    )

    @Test fun f15_summary_cards_keep_every_word_whole_and_values_are_not_ellipsised() {
        show { Column(Modifier.verticalScroll(rememberScrollState())) { InsightsHubSummaryRow(hubState = content) } }
        for (label in listOf("Total Plays", "Listening", "Streak")) {
            val t = rule.onNode(hasText(label), useUnmergedTree = true)
            t.assertNoMidWordBreak("summary label '$label'")
        }
        for (value in listOf("1234", "1d 13h", "12 days")) {
            val t = rule.onNode(hasText(value), useUnmergedTree = true)
            val layout = t.textLayout()
            assertTrue("value '$value' is ellipsised", (0 until layout.lineCount).none { layout.isLineEllipsized(it) })
            t.assertNoMidWordBreak("summary value '$value'")
        }
        val bounds = listOf("Total Plays", "Listening", "Streak").map { rule.onNode(hasText(it), useUnmergedTree = true).visibleBounds() }
        val window = Rect(0f, 0f, widthDp * density, Float.MAX_VALUE)
        bounds.forEach { assertTrue("label $it lies within the width", it.left >= 0f && it.right <= window.right) }
        for (i in bounds.indices) for (j in i + 1 until bounds.size) assertNoOverlap("label$i", bounds[i], "label$j", bounds[j])
    }

    @Test fun f15_destination_cards_keep_titles_whole_and_every_callback_is_wired() {
        val clicks = mutableListOf<String>()
        show {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                InsightsDestinationGrid(
                    onStatisticsClick = { clicks += "Statistics" }, onReportsClick = { clicks += "Reports" },
                    onMonthlyReportsClick = { clicks += "Monthly" }, onWrappedClick = { clicks += "Wrapped" },
                )
            }
        }
        val titles = listOf("Statistics", "Reports", "Monthly", "Wrapped")
        titles.forEach { rule.onNode(hasText(it) and androidx.compose.ui.test.hasNoClickAction(), useUnmergedTree = true).assertNoMidWordBreak("destination '$it'") }
        listOf("Totals, streaks, and listening history", "Ranked songs, artists, and albums", "Month-by-month listening", "Monthly and yearly recaps").forEach {
            val t = rule.onNode(hasText(it), useUnmergedTree = true)
            t.assertNoMidWordBreak("subtitle '$it'")
            assertTrue("subtitle '$it' is ellipsised", (0 until t.textLayout().lineCount).none { l -> t.textLayout().isLineEllipsized(l) })
        }
        val cards = titles.map { rule.onNode(hasClickAction() and hasText(it)) }
        val bounds = cards.map { it.visibleBounds() }
        for (i in bounds.indices) for (j in i + 1 until bounds.size) assertNoOverlap(titles[i], bounds[i], titles[j], bounds[j])
        bounds.forEachIndexed { i, b -> assertTrue("${titles[i]} card ${b.width / density}dp must lie in the ${widthDp}dp window", b.left >= 0f && b.right <= widthDp * density + 1f) }
        cards.forEachIndexed { i, c -> c.performClick(); assertEquals(titles[i], clicks.last()) }
        assertEquals(titles, clicks)
    }
}
