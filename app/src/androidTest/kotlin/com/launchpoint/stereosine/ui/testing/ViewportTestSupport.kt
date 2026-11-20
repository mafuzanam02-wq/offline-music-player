package com.launchpoint.stereosine.ui.testing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue

/**
 * Test-only helpers for layout accessibility tests (UI-READABILITY-02A). Scope: force a font scale and/or a fixed viewport around a
 * composable, and measure a node against what is actually visible rather than its un-clipped layout size. Not a screenshot framework.
 *
 * Note: a `Dialog` is its own window, so [FixedViewport] cannot narrow it; dialog width/height are exercised by changing the
 * device window (`wm size`) in the manual device matrix, while [ForcedFontScale] still applies inside the dialog.
 */
@Composable
fun ForcedFontScale(fontScale: Float, content: @Composable () -> Unit) {
    val base = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) { content() }
}

/** Lays [content] out in an exactly [widthDp] x [heightDp] region with [fontScale], like a small phone window. */
@Composable
fun FixedViewport(widthDp: Int, heightDp: Int, fontScale: Float, content: @Composable () -> Unit) {
    ForcedFontScale(fontScale) {
        Box(Modifier.size(widthDp.dp, heightDp.dp)) { content() }
    }
}

/** The node's bounds clipped by every ancestor (scroll viewports, windows): what the user can actually see. */
fun SemanticsNodeInteraction.visibleBounds(): Rect = fetchSemanticsNode().layoutInfo.coordinates.boundsInWindow()

/** The node's full un-clipped size in px. */
fun SemanticsNodeInteraction.fullSize(): Pair<Float, Float> {
    val size = fetchSemanticsNode().layoutInfo.coordinates.size
    return size.width.toFloat() to size.height.toFloat()
}

/** Fails when any part of the node is clipped away (by a scroll viewport, the window or another ancestor). */
fun SemanticsNodeInteraction.assertNotClipped(what: String): SemanticsNodeInteraction {
    val visible = visibleBounds()
    val (w, h) = fullSize()
    assertTrue("$what is clipped: visible ${visible.width}x${visible.height} of ${w}x$h", visible.width >= w - 1f && visible.height >= h - 1f)
    return this
}

/** The node's layout bounds in root coordinates WITHOUT ancestor clipping (SemanticsNode.boundsInRoot is clipped). */
fun SemanticsNodeInteraction.unclippedBounds(): Rect {
    val c = fetchSemanticsNode().layoutInfo.coordinates
    val p = c.positionInRoot()
    return Rect(p.x, p.y, p.x + c.size.width, p.y + c.size.height)
}

/**
 * Sets the device-wide font scale (via the shell `settings` command through UiAutomation) before the activity launches and
 * restores the previous value afterwards. Needed for `Dialog`/`AlertDialog` content: a dialog is its own window whose view
 * re-provides `LocalDensity`, so [ForcedFontScale] around the caller never reaches it. Order it OUTSIDE the compose rule with
 * `RuleChain.outerRule(SystemFontScaleRule(x)).around(composeRule)`. Intended for disposable emulators only.
 */
private const val SETTLE_MS = 500L

private fun awaitFontScale(scale: Float) {
    val deadline = System.currentTimeMillis() + 15_000L
    while (kotlin.math.abs(android.content.res.Resources.getSystem().configuration.fontScale - scale) > 0.01f) {
        if (System.currentTimeMillis() > deadline) {
            throw AssertionError("system font scale did not become $scale (is ${android.content.res.Resources.getSystem().configuration.fontScale})")
        }
        Thread.sleep(100)
    }
    Thread.sleep(SETTLE_MS)
}

class SystemFontScaleRule(private val scale: Float) : org.junit.rules.TestRule {
    override fun apply(base: org.junit.runners.model.Statement, description: org.junit.runner.Description) =
        object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                val ui = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
                fun sh(cmd: String): String =
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(cmd)).bufferedReader().readText().trim()
                val previous = sh("settings get system font_scale")
                sh("settings put system font_scale $scale")
                awaitFontScale(scale) // the configuration change is delivered asynchronously; never test at the wrong scale
                try {
                    base.evaluate()
                } finally {
                    val restore = if (previous == "null" || previous.isEmpty()) "1.0" else previous
                    sh("settings put system font_scale $restore")
                    awaitFontScale(restore.toFloat()) // settle before the next test changes it again
                }
            }
        }
}

/**
 * Like [assertNotClipped], but a node that is taller than its scroll viewport cannot be shown at once: it then counts as reachable
 * when it occupies at least 80% of the [viewport] (scrolling reveals the rest). Anything smaller than the viewport must be fully visible.
 */
fun SemanticsNodeInteraction.assertReachable(what: String, viewport: Rect): SemanticsNodeInteraction {
    val visible = visibleBounds()
    val (w, h) = fullSize()
    val fits = visible.height >= h - 1f
    val fillsViewport = h > viewport.height && visible.height >= viewport.height * 0.8f
    assertTrue(
        "$what is not reachable: visible ${visible.width}x${visible.height} of ${w}x$h, viewport ${viewport.width}x${viewport.height}",
        visible.width >= w - 1f && (fits || fillsViewport),
    )
    return this
}

/** Runs a shell command through UiAutomation (disposable emulators / dev devices only) and returns its output. */
fun shellOutput(cmd: String): String {
    val ui = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
    return android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(cmd)).bufferedReader().readText()
}

/**
 * Top edge (screen px) of the software keyboard from the window manager's real IME inset, or null while no keyboard is shown.
 * Read from the system, not from Compose, because the question is whether the keyboard covers a control.
 */
fun imeTopOnScreenPx(): Int? {
    val line = shellOutput("dumpsys window").lineSequence()
        .firstOrNull { "type=ime" in it && "InsetsSource" in it && "visible=true" in it } ?: return null
    val m = Regex("""frame=\[(\d+),(\d+)]\[(\d+),(\d+)]""").find(line) ?: return null
    val top = m.groupValues[2].toInt()
    val bottom = m.groupValues[4].toInt()
    return if (bottom > top) top else null
}

/** Waits up to [timeoutMs] for a keyboard whose top edge leaves real screen height (not just the navigation strip). */
fun awaitKeyboardTopPx(minHeightPx: Int, timeoutMs: Long = 15_000L): Int? {
    val deadline = System.currentTimeMillis() + timeoutMs
    val screenH = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.heightPixels
    while (System.currentTimeMillis() < deadline) {
        val top = imeTopOnScreenPx()
        if (top != null && screenH - top >= minHeightPx) return top
        Thread.sleep(250)
    }
    return null
}

/** Waits until no software keyboard is shown, so a keyboard left by one test cannot cover controls in the next. */
fun awaitKeyboardHidden(timeoutMs: Long = 8_000L) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (imeTopOnScreenPx() == null) return
        Thread.sleep(250)
    }
}

/** Waits until the keyboard's top edge (or its absence) has been stable for ~1 s, so a resize animation cannot move a target mid-click. */
fun awaitKeyboardSettled(timeoutMs: Long = 8_000L) {
    val deadline = System.currentTimeMillis() + timeoutMs
    var last = imeTopOnScreenPx()
    var stableSince = System.currentTimeMillis()
    while (System.currentTimeMillis() < deadline) {
        Thread.sleep(250)
        val now = imeTopOnScreenPx()
        if (now != last) { last = now; stableSince = System.currentTimeMillis() }
        else if (System.currentTimeMillis() - stableSince >= 1_000L) return
    }
}
