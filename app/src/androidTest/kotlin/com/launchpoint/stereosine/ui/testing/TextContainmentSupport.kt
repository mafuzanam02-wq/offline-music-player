package com.launchpoint.stereosine.ui.testing

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue

/** Test-only helpers for UI-READABILITY-03A text-containment tests. They read the real text layout, not just semantics presence. */

/** The Text node's real layout result (needs an unmerged-tree node that is a `Text`). */
fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
    val results = mutableListOf<TextLayoutResult>()
    val action = fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
        ?: throw AssertionError("node has no text layout (use useUnmergedTree = true on the Text itself)")
    action(results)
    return results.first()
}

/** Whether a rendered line break falls strictly inside a word (a line starts without whitespace before it). */
fun TextLayoutResult.midWordBreaks(): List<String> {
    val text = layoutInput.text.text
    return (1 until lineCount).mapNotNull { line ->
        val start = getLineStart(line)
        if (start in 1 until text.length && !text[start - 1].isWhitespace() && !text[start].isWhitespace()) {
            "'${text.substring((start - 4).coerceAtLeast(0), start)}|${text.substring(start, (start + 4).coerceAtMost(text.length))}'"
        } else null
    }
}

/** Fails when the text is ellipsised/cut by its own constraints, or when its box is clipped by an ancestor. */
fun SemanticsNodeInteraction.assertTextNotTruncated(what: String): SemanticsNodeInteraction {
    val layout = textLayout()
    val text = layout.layoutInput.text.text
    val ellipsized = (0 until layout.lineCount).filter { layout.isLineEllipsized(it) }
    assertTrue("$what is ellipsised on line(s) $ellipsized: '$text'", ellipsized.isEmpty())
    assertEquals("$what shows only ${layout.getLineEnd(layout.lineCount - 1, visibleEnd = true)} of ${text.length} characters: '$text'", text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    assertNotClipped(what)
    return this
}

/** Fails when the text is broken in the middle of a word ("Playe / d / Track / s"). Single tokens longer than the line are exempt. */
fun SemanticsNodeInteraction.assertNoMidWordBreak(what: String): SemanticsNodeInteraction {
    val breaks = textLayout().midWordBreaks()
    assertTrue("$what breaks mid-word at ${breaks.joinToString()}", breaks.isEmpty())
    return this
}

fun Rect.overlapsInterior(other: Rect, tolerance: Float = 0.5f): Boolean =
    left < other.right - tolerance && other.left < right - tolerance && top < other.bottom - tolerance && other.top < bottom - tolerance

fun assertNoOverlap(aName: String, a: Rect, bName: String, b: Rect) =
    assertFalse("$aName $a overlaps $bName $b", a.overlapsInterior(b))

fun assertInside(what: String, inner: Rect, outer: Rect, tolerance: Float = 1f) =
    assertTrue("$what $inner must lie inside $outer", inner.left >= outer.left - tolerance && inner.right <= outer.right + tolerance &&
        inner.top >= outer.top - tolerance && inner.bottom <= outer.bottom + tolerance)

/** The real host window in dp (not `wm size`): what the layout can actually be given. */
fun hostWindowDp(): Pair<Float, Float> {
    val m = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
    return m.widthPixels / m.density to m.heightPixels / m.density
}

/** A fixed viewport is coerced to the window, so a fixture larger than the host measures the host, not the layout: skip it explicitly. */
fun assumeHostFits(widthDp: Int, heightDp: Int) {
    val (w, h) = hostWindowDp()
    assumeTrue("invalid fixture: ${widthDp}x$heightDp dp does not fit the ${w}x$h dp host window", w >= widthDp && h >= heightDp)
}

