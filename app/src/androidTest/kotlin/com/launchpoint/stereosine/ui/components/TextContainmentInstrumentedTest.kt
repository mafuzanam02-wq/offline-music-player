package com.launchpoint.stereosine.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.launchpoint.stereosine.ui.screen.settings.AboutInfoRow
import com.launchpoint.stereosine.ui.screen.statistics.Metric
import com.launchpoint.stereosine.ui.screen.statistics.MetricGrid
import com.launchpoint.stereosine.ui.screen.statistics.OverviewCard
import com.launchpoint.stereosine.ui.screen.trackdetails.TrackPlaybackActions
import com.launchpoint.stereosine.ui.testing.FixedViewport
import com.launchpoint.stereosine.ui.testing.assertInside
import com.launchpoint.stereosine.ui.testing.assertNoMidWordBreak
import com.launchpoint.stereosine.ui.testing.assertNoOverlap
import com.launchpoint.stereosine.ui.testing.assertTextNotTruncated
import com.launchpoint.stereosine.ui.testing.assumeHostFits
import com.launchpoint.stereosine.ui.testing.hostWindowDp
import com.launchpoint.stereosine.ui.testing.textLayout
import com.launchpoint.stereosine.ui.testing.visibleBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * UI-READABILITY-03A (F-05..F-08): text containment at the supported width / font-scale matrix. Every assertion reads real bounds
 * and the real text layout (ellipsis, mid-word breaks), against the production composables with synthetic content.
 *
 * Fixtures larger than the host window are skipped with a reason (see [assumeHostFits]); run the class under the default portrait
 * window (320/360/392 dp rows) and under `wm size 1700x880` (the 618x320 landscape rows).
 */
@RunWith(Parameterized::class)
class TextContainmentInstrumentedTest(
    private val widthDp: Int,
    private val heightDp: Int,
    private val fontScale: Float,
) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}x{1} fs{2}")
        fun configs() = listOf(
            Triple(320, 640, 1.0f), Triple(320, 640, 1.5f), Triple(320, 640, 2.0f),
            Triple(360, 780, 2.0f),
            Triple(392, 851, 1.0f), Triple(392, 851, 2.0f),
            Triple(618, 320, 1.0f), Triple(618, 320, 2.0f),
        ).map { (w, h, f) -> arrayOf<Any>(w, h, f) }
    }

    @get:Rule val rule = createComposeRule()

    private fun show(content: @Composable () -> Unit) {
        assumeHostFits(widthDp, heightDp)
        val (hostW, hostH) = hostWindowDp()
        val res = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources
        fun inset(name: String) = res.getIdentifier(name, "dimen", "android").let { if (it == 0) 0f else res.getDimension(it) / res.displayMetrics.density }
        android.util.Log.i(
            "TextContainment",
            "fixture=${widthDp}x$heightDp fs=$fontScale host=${hostW}x${hostH}dp density=${res.displayMetrics.density} " +
                "statusBar=${inset("status_bar_height")}dp navBar=${inset("navigation_bar_height")}dp",
        )
        rule.setContent { FixedViewport(widthDp, heightDp, fontScale) { content() } }
    }

    private val density get() = rule.density.density
    private val window get() = Rect(0f, 0f, widthDp * density, heightDp * density)

    // ── F-05 ────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun f05_every_navigation_label_is_fully_visible_and_each_destination_is_a_usable_target() {
        val clicks = mutableListOf<String>()
        show {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                PrimaryNavigationBar(
                    selected = PrimaryDestination.INSIGHTS,
                    onHomeClick = { clicks += "Home" }, onSongsClick = { clicks += "Songs" },
                    onLibraryClick = { clicks += "Library" }, onInsightsClick = { clicks += "Insights" },
                )
            }
        }
        val labels = listOf("Home", "Songs", "Library", "Insights")
        assertEquals("exactly the four primary destinations are clickable", 4, rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
        val items = labels.map { rule.onNode(hasClickAction() and hasText(it)) }
        items.forEachIndexed { i, item ->
            val b = item.visibleBounds()
            assertInside("${labels[i]} item", b, window)
            assertTrue("${labels[i]} target ${b.width / density}x${b.height / density}dp must be at least 48 dp tall", b.height >= 48f * density - 1f)
            assertTrue("${labels[i]} target ${b.width / density}dp must be at least 48 dp wide", b.width >= 48f * density - 1f)
            val label = rule.onNode(hasText(labels[i]) and hasNoClickAction(), useUnmergedTree = true)
            android.util.Log.i("TextContainment", "f05 ${widthDp}dp fs$fontScale ${labels[i]}: slot=${b.width / density}dp label=${label.visibleBounds().width / density}dp natural=${label.textLayout().multiParagraph.maxIntrinsicWidth / density}dp")
            label.assertTextNotTruncated("nav label ${labels[i]}")
            assertEquals("${labels[i]} label stays on one line", 1, label.textLayout().lineCount)
            // The requested system scale is the source of truth: the label is laid out at exactly the fixture's font scale.
            assertEquals("${labels[i]} label font scale", fontScale, label.textLayout().layoutInput.density.fontScale, 0.001f)
            assertEquals("${labels[i]} label size = labelMedium 12sp at the requested scale", 12f * fontScale * density,
                label.textLayout().layoutInput.style.fontSize.value * fontScale * density, 0.5f)
            assertInside("${labels[i]} label inside its own item", label.visibleBounds(), b)
        }
        for (i in 0 until items.size - 1) assertNoOverlap(labels[i], items[i].visibleBounds(), labels[i + 1], items[i + 1].visibleBounds())
        items[3].assertIsSelected()
        items.forEachIndexed { i, item -> item.performClick(); assertEquals(labels[i], clicks.last()) }
        assertEquals(labels, clicks)
    }

    // ── F-06 ────────────────────────────────────────────────────────────────────────────────────────────────────────────

    private val rows = listOf(
        "Version name" to "1.0.0-debug",
        "Package" to "com.launchpoint.stereosine.debug",
        "Long package" to "com.launchpoint.stereosine.debug.internal.experimental.diagnostics.fixture.overlay.variant",
        "Database" to "stereosine.db",
        "Startup destination" to "Home",
    )

    @Test fun f06_diagnostic_label_is_whole_and_never_overlaps_its_value() {
        show { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { rows.forEach { (l, v) -> AboutInfoRow(label = l, value = v) } } }
        rows.forEach { (l, v) ->
            val label = rule.onNode(hasText(l), useUnmergedTree = true).performScrollTo().assertTextNotTruncated("label $l").assertNoMidWordBreak("label $l")
            val value = rule.onNode(hasText(v), useUnmergedTree = true).performScrollTo().assertTextNotTruncated("value of $l")
            if (!l.contains(' ')) assertEquals("single-word label '$l' stays on one line", 1, label.textLayout().lineCount)
            assertNoOverlap("label $l", label.visibleBounds(), "value of $l", value.visibleBounds())
            assertTrue("value of $l must not leave the viewport", value.visibleBounds().let { it.left >= 0f && it.right <= window.right + 1f })
            assertTrue("label $l must not leave the viewport", label.visibleBounds().let { it.left >= 0f && it.right <= window.right + 1f })
        }
    }

    // ── F-07 ────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun f07_playback_chips_show_their_full_labels_stay_clickable_and_keep_reading_order() {
        val calls = mutableListOf<String>()
        show {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                TrackPlaybackActions(onPlayNow = { calls += "now" }, onPlayNext = { calls += "next" }, onAddToQueue = { calls += "queue" })
            }
        }
        val names = listOf("Play now", "Play next", "Add to queue")
        val chips = names.map { rule.onNode(hasClickAction() and hasText(it)).performScrollTo() }
        names.forEachIndexed { i, n ->
            val chip = chips[i].visibleBounds()
            assertInside("$n chip", chip, window)
            val label = rule.onNode(hasText(n) and hasNoClickAction(), useUnmergedTree = true).assertTextNotTruncated("chip label $n").assertNoMidWordBreak("chip label $n")
            assertInside("$n label inside its chip", label.visibleBounds(), chip)
        }
        for (i in names.indices) for (j in i + 1 until names.size) assertNoOverlap(names[i], chips[i].visibleBounds(), names[j], chips[j].visibleBounds())
        val order = names.indices.sortedWith(compareBy({ chips[it].visibleBounds().top.toInt() / 4 }, { chips[it].visibleBounds().left }))
        assertEquals("visual order must equal logical order", names.indices.toList(), order)
        chips.forEach { it.performClick() }
        assertEquals(listOf("now", "next", "queue"), calls)
    }

    // ── F-08 ────────────────────────────────────────────────────────────────────────────────────────────────────────────

    private val statPairs = listOf(
        ("Songs" to "1,284") to ("Played Tracks" to "1,047"),
        ("Total Plays" to "18,402") to ("Total Skips" to "2,311"),
        ("Favorites" to "128") to ("Skip Ratio" to "8.4%"),
        ("Streak" to "12 days") to ("Active Day" to "Wed"),
    )

    @Test fun f08_metric_titles_break_only_between_words_and_values_are_never_truncated() {
        show {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetricGrid(
                    listOf(
                        Metric("Songs", "1,284", Icons.Default.History), Metric("Played Tracks", "1,047", Icons.Default.SkipNext),
                        Metric("Total Plays", "18,402", Icons.Default.History), Metric("Total Skips", "2,311", Icons.Default.SkipNext),
                    ),
                    modifier = Modifier.testTag("grid-overview"),
                )
                OverviewCard("Listening Time", "126 h 40 min", Icons.Default.History, Modifier.testTag("card-Listening Time"))
                MetricGrid(
                    listOf(
                        Metric("Favorites", "128", Icons.Default.History), Metric("Skip Ratio", "8.4%", Icons.Default.SkipNext),
                        Metric("Streak", "12 days", Icons.Default.History), Metric("Active Day", "Wed", Icons.Default.SkipNext),
                    ),
                    modifier = Modifier.testTag("grid-insights"),
                )
            }
        }
        val all = statPairs.flatMap { listOf(it.first, it.second) } + ("Listening Time" to "126 h 40 min")
        val widths = mutableMapOf<String, Float>()
        all.forEach { (l, v) ->
            val label = rule.onNode(hasText(l), useUnmergedTree = true).performScrollTo().assertTextNotTruncated("label $l").assertNoMidWordBreak("label $l")
            val value = rule.onNode(hasText(v), useUnmergedTree = true).assertTextNotTruncated("value $v").assertNoMidWordBreak("value $v")
            // Requested scale preserved for BOTH label and value (no automatic shrinking).
            assertEquals("label $l font scale", fontScale, label.textLayout().layoutInput.density.fontScale, 0.001f)
            assertEquals("label $l keeps labelMedium 12sp", 12f, label.textLayout().layoutInput.style.fontSize.value, 0.01f)
            assertEquals("value $v keeps titleLarge 22sp", 22f, value.textLayout().layoutInput.style.fontSize.value, 0.01f)
            assertEquals("value $v font scale", fontScale, value.textLayout().layoutInput.density.fontScale, 0.001f)
            assertNoOverlap("label $l", label.visibleBounds(), "value $v", value.visibleBounds())
            assertInside("label $l inside viewport", label.visibleBounds(), window)
            assertInside("value $v inside viewport", value.visibleBounds(), window)
        }
        listOf("grid-overview", "grid-insights").forEach { tag ->
            val grid = rule.onNodeWithTag(tag).performScrollTo().visibleBounds()
            assertInside("$tag", grid, window)
            widths[tag] = grid.width
        }
        // Reflow: the two-column grid is only allowed when every card has room for its longest words; at 2.0x on a narrow window the
        // grid must collapse to one column (each card text fully visible is asserted above). Log the chosen layout for the matrix.
        val twoColumn = Math.abs(
            rule.onNode(hasText("Songs"), useUnmergedTree = true).visibleBounds().top -
                rule.onNode(hasText("Played Tracks"), useUnmergedTree = true).visibleBounds().top,
        ) < 2f * density
        android.util.Log.i(
            "TextContainment",
            "f08 layout at ${widthDp}dp fs$fontScale = ${if (twoColumn) "two-column" else "one-column"} " +
                "songsTop=${rule.onNode(hasText("Songs"), useUnmergedTree = true).visibleBounds().top} " +
                "playedTop=${rule.onNode(hasText("Played Tracks"), useUnmergedTree = true).visibleBounds().top} " +
                "gridWidth=${widths["grid-overview"]?.div(density)}dp",
        )
        if (fontScale <= 1f && widthDp >= 392) assertTrue("392+ dp at 1.0x keeps the two-column grid", twoColumn)
    }

    @Test fun f08_grid_reflows_to_one_full_width_column_when_two_columns_cannot_hold_the_values() {
        // A seven-digit value does not fit half a 320-360 dp window at 2.0x: the grid must give each card the full width instead of
        // breaking, truncating or shrinking the value.
        show {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                MetricGrid(
                    listOf(
                        Metric("Total Plays", "1,234,567", Icons.Default.History), Metric("Played Tracks", "1,047", Icons.Default.SkipNext),
                        Metric("Total Skips", "2,311", Icons.Default.SkipNext), Metric("Songs", "1,284", Icons.Default.History),
                    ),
                )
            }
        }
        val plays = rule.onNode(hasText("1,234,567"), useUnmergedTree = true).performScrollTo().assertTextNotTruncated("value 1,234,567").assertNoMidWordBreak("value 1,234,567")
        val played = rule.onNode(hasText("Played Tracks"), useUnmergedTree = true).assertTextNotTruncated("label Played Tracks").assertNoMidWordBreak("label Played Tracks")
        assertEquals(fontScale, plays.textLayout().layoutInput.density.fontScale, 0.001f)
        assertEquals(22f, plays.textLayout().layoutInput.style.fontSize.value, 0.01f)
        val sameRow = Math.abs(plays.visibleBounds().top - played.visibleBounds().top) < 2f * density
        android.util.Log.i("TextContainment", "f08b reflow at ${widthDp}dp fs$fontScale = ${if (sameRow) "two-column" else "one-column"}")
        if (widthDp <= 360 && fontScale >= 2f) assertTrue("seven-digit value at 2.0x on ${widthDp}dp must reflow to one column", !sameRow)
        assertInside("value inside viewport", plays.visibleBounds(), window)
    }
}
