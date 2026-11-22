package com.launchpoint.stereosine.ui.components

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.launchpoint.stereosine.data.model.Song
import com.launchpoint.stereosine.ui.testing.ForcedFontScale
import com.launchpoint.stereosine.ui.testing.assumeHostFits
import com.launchpoint.stereosine.ui.testing.hostWindowDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * UI-READABILITY-03D (F-11): the song-row overflow menu must open next to the three-dot button, not at the row's start edge (or the
 * screen's top-left when the menu is tall). Real popup / button geometry in screen coordinates from the production
 * [SongRowWithOverflow], plus the gesture and callback contract that must not change. Fixtures larger than the host window are
 * skipped with a reason.
 */
@RunWith(Parameterized::class)
class SongOverflowMenuAnchoringInstrumentedTest(
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

        private val ALL_ACTIONS = listOf(
            "Play", "Play next", "Add to queue", "Add to playlist", "Add to Favorites",
            "Go to artist", "Go to album", "Track details", "Share", "View folder", "Remove",
        )
    }

    @get:Rule val rule = createComposeRule()

    private val density get() = rule.density.density
    private val calls = mutableListOf<String>()
    private val songs = mutableStateListOf<Song>()
    private var favorite by mutableStateOf(false)

    private fun song(i: Int) = Song(
        id = i.toLong() + 1, title = "Song %02d".format(i), artist = "Artist %02d".format(i), album = "Album %02d".format(i),
        albumId = i.toLong() + 1, duration = 200_000L, uri = "content://test/$i", dateAdded = 0L, trackNumber = 1, year = 2024,
    )

    /** A scrolling list of production rows in a [widthDp] x [heightDp] viewport pinned to the bottom of the window. */
    private fun show(rows: Int = 14) {
        assumeHostFits(widthDp, heightDp)
        songs.clear(); songs.addAll((0 until rows).map(::song))
        rule.setContent {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
                ForcedFontScale(fontScale) {
                    Box(Modifier.size(widthDp.dp, heightDp.dp).testTag("viewport")) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            songs.forEach { s ->
                                key(s.id) {
                                    SongRowWithOverflow(
                                        song = s, isCurrent = false, isFavorite = favorite,
                                        onPlay = { calls += "Play" }, onPlayNext = { calls += "Play next" },
                                        onAddToQueue = { calls += "Add to queue" }, onToggleFavorite = { calls += "Favorite" },
                                        onAddToPlaylist = { calls += "Add to playlist" }, onTrackDetails = { calls += "Track details" },
                                        onRemove = { calls += "Remove" }, onViewFolder = { calls += "View folder" },
                                        onShare = { calls += "Share" }, onAlbumClick = { calls += "Go to album" },
                                        onArtistClick = { calls += "Go to artist" },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun SemanticsNodeInteraction.screenRect(): Rect {
        val c = fetchSemanticsNode().layoutInfo.coordinates
        val p = c.positionOnScreen()
        return Rect(p.x, p.y, p.x + c.size.width, p.y + c.size.height)
    }

    private fun button(i: Int) = rule.onAllNodes(hasClickAction() and hasContentDescription("More actions"))[i]
    private fun row(i: Int) = rule.onAllNodes(hasClickAction() and hasText("Song %02d".format(i)))[0]
    private fun menu() = rule.onNode(isPopup())
    private fun menuOpen() = rule.onAllNodes(isPopup()).fetchSemanticsNodes().isNotEmpty()
    private fun settle() { rule.mainClock.advanceTimeBy(1_000); rule.waitForIdle() }

    private fun windowPx(): Rect { val (w, h) = hostWindowDp(); return Rect(0f, 0f, w * density, h * density) }

    /** Rows whose whole bounds lie inside the viewport, in list order. */
    private fun visibleRows(): List<Int> {
        val vp = rule.onNodeWithTag("viewport").screenRect()
        return songs.indices.filter { i ->
            runCatching { row(i).screenRect() }.getOrNull()?.let { it.top >= vp.top - 1f && it.bottom <= vp.bottom + 1f } == true
        }
    }

    private fun closeWithBack() { Espresso.pressBack(); rule.waitForIdle() }

    private fun assertMenuAnchoredAt(buttonRect: Rect, what: String) {
        val m = menu().screenRect(); val w = windowPx()
        val tol = 2f * density
        android.util.Log.i("R03D", "$what ${widthDp}dp fs$fontScale button=${buttonRect.left / density},${buttonRect.top / density}-${buttonRect.right / density},${buttonRect.bottom / density} " +
            "menu=${m.left / density},${m.top / density}-${m.right / density},${m.bottom / density} window=${w.width / density}x${w.height / density}")
        assertTrue("$what: menu $m must stay inside the window $w", m.left >= w.left - tol && m.right <= w.right + tol && m.top >= w.top - tol && m.bottom <= w.bottom + tol)
        // Horizontal: the menu spans the button (start-aligned at it, or end-aligned after Compose's edge collision adjustment).
        val bx = (buttonRect.left + buttonRect.right) / 2f
        assertTrue("$what: menu x ${m.left / density}..${m.right / density}dp must span the button centre ${bx / density}dp", bx >= m.left - tol && bx <= m.right + tol)
        // Vertical: directly below or above the button; when the menu fits on neither side Compose clamps it to the window, and it then
        // spans the button's row (or is pushed flush against a window edge). The exact offset is Compose's to choose.
        val adjacentBelow = kotlin.math.abs(m.top - buttonRect.bottom) <= 8f * density
        val adjacentAbove = kotlin.math.abs(m.bottom - buttonRect.top) <= 8f * density
        val spansButton = m.top <= buttonRect.top + tol && m.bottom >= buttonRect.bottom - tol
        val menuH = m.bottom - m.top
        val fitsBelow = buttonRect.bottom + menuH <= w.bottom - 48f * density
        val fitsAbove = buttonRect.top - menuH >= w.top + 48f * density
        // A button in the bottom system-bar inset (outside the popup's usable area) makes Compose pin the menu to that area's edge:
        // 48 dp margin + the navigation-bar inset. Accept a menu flush with either edge within that band.
        val edgeAdjusted = (!fitsBelow && !fitsAbove) || m.top <= 56f * density || m.bottom >= w.bottom - 130f * density
        assertTrue("$what: menu ${m.top / density}..${m.bottom / density}dp is not adjacent to the button ${buttonRect.top / density}..${buttonRect.bottom / density}dp " +
            "(below=$adjacentBelow above=$adjacentAbove spans=$spansButton edge=$edgeAdjusted)", adjacentBelow || adjacentAbove || spansButton || edgeAdjusted)
    }

    // ── Anchoring ───────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun menu_opens_beside_the_overflow_button_for_first_middle_and_last_visible_rows() {
        show()
        rule.waitForIdle()
        val visible = visibleRows()
        assertTrue("fixture must show at least 3 rows, saw $visible", visible.size >= 3)
        val picks = linkedMapOf("first" to visible.first(), "middle" to visible[visible.size / 2], "last" to visible.last())
        picks.forEach { (name, i) ->
            val b = button(i).screenRect()
            button(i).performClick(); rule.waitForIdle()
            assertTrue("$name row $i: menu opens", menuOpen())
            assertMenuAnchoredAt(b, "$name row $i")
            closeWithBack()
            assertTrue("$name row $i: Back dismisses the menu", !menuOpen())
        }
        assertEquals("opening and dismissing the menu invokes nothing", emptyList<String>(), calls)
    }

    @Test fun long_press_opens_the_same_menu_beside_the_button() {
        show()
        rule.waitForIdle()
        val i = visibleRows()[visibleRows().size / 2]
        val b = button(i).screenRect()
        row(i).performTouchInput { longClick() }
        rule.waitForIdle()
        assertTrue("long press opens the menu", menuOpen())
        assertMenuAnchoredAt(b, "long press row $i")
        closeWithBack()
        assertEquals("long press must not play or favorite", emptyList<String>(), calls)
    }

    // ── Every action: reachable (scrolling the menu if needed), fires once, closes the menu first ─────────────────────────

    @Test fun every_menu_action_is_reachable_fires_once_and_closes_the_menu() {
        show()
        rule.waitForIdle()
        val i = visibleRows().last()
        ALL_ACTIONS.forEach { label ->
            calls.clear()
            button(i).performClick(); rule.waitForIdle()
            assertTrue("$label: menu is open", menuOpen())
            val item = rule.onNode(hasText(label) and hasClickAction())
            item.performScrollTo()
            val r = item.screenRect(); val w = windowPx()
            assertTrue("$label ${r} must be inside the window $w after scrolling the menu", r.left >= w.left - 1f && r.right <= w.right + 1f && r.top >= w.top - 1f && r.bottom <= w.bottom + 1f)
            item.performClick(); rule.waitForIdle()
            val expected = if (label == "Add to Favorites") "Favorite" else label
            assertEquals("$label fires exactly once", listOf(expected), calls)
            assertTrue("$label: menu is closed after the action", !menuOpen())
        }
    }

    // ── Gestures on the row itself are unchanged ────────────────────────────────────────────────────────────────────────

    @Test fun row_tap_plays_double_tap_favorites_and_button_tap_never_plays() {
        show()
        rule.waitForIdle()
        val i = visibleRows().first()
        row(i).performClick(); settle()
        assertEquals("single tap plays once", listOf("Play"), calls); assertTrue(!menuOpen())
        calls.clear()
        row(i).performTouchInput { doubleClick() }; settle()
        assertEquals("double tap favorites once and does not play", listOf("Favorite"), calls)
        calls.clear()
        button(i).performClick(); settle()
        assertTrue("button tap opens the menu", menuOpen())
        assertEquals("button tap does not play", emptyList<String>(), calls)
        closeWithBack()
    }

    // ── Dismissal and re-opening ────────────────────────────────────────────────────────────────────────────────────────

    @Test fun outside_tap_and_back_dismiss_and_the_menu_reopens() {
        show()
        rule.waitForIdle()
        val i = visibleRows().first()
        button(i).performClick(); rule.waitForIdle()
        assertTrue(menuOpen())
        val m = menu().screenRect(); val w = windowPx()
        val candidates = listOf(
            2f * density to w.height / 2f, w.width - 2f * density to w.height / 2f,
            w.width / 2f to w.height - 4f * density, w.width / 2f to 4f * density,
        )
        val (x, y) = candidates.first { (cx, cy) -> !(cx >= m.left - 2f && cx <= m.right + 2f && cy >= m.top - 2f && cy <= m.bottom + 2f) }
        val ua = InstrumentationRegistry.getInstrumentation().uiAutomation
        val t = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN to t, MotionEvent.ACTION_UP to t + 80).forEach { (action, time) ->
            val e = MotionEvent.obtain(t, time, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            ua.injectInputEvent(e, true); e.recycle()
        }
        rule.waitForIdle(); Thread.sleep(400); rule.waitForIdle()
        assertTrue("outside tap dismisses the menu", !menuOpen())
        assertEquals("outside tap fires no action", emptyList<String>(), calls)
        button(i).performClick(); rule.waitForIdle()
        assertTrue("menu reopens after dismissal", menuOpen())
        closeWithBack()
        assertTrue(!menuOpen())
    }

    // ── Recomposition / removal ─────────────────────────────────────────────────────────────────────────────────────────

    @Test fun menu_survives_recomposition_and_closes_when_its_row_is_removed() {
        show()
        rule.waitForIdle()
        val i = visibleRows().first()
        button(i).performClick(); rule.waitForIdle()
        favorite = true; rule.waitForIdle()
        assertTrue("an unrelated recomposition keeps the menu open", menuOpen())
        rule.runOnIdle { songs.removeAt(i) }
        rule.waitForIdle()
        assertTrue("removing the row removes its menu", !menuOpen())
        assertEquals(emptyList<String>(), calls)
        val next = visibleRows().first()
        button(next).performClick(); rule.waitForIdle()
        assertTrue("the next row's menu still works", menuOpen())
        closeWithBack()
    }
}
