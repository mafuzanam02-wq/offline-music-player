package com.launchpoint.stereosine.ui.permission

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.launchpoint.stereosine.ui.testing.FixedViewport
import com.launchpoint.stereosine.ui.testing.assertNotClipped
import com.launchpoint.stereosine.ui.testing.unclippedBounds
import com.launchpoint.stereosine.ui.testing.visibleBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * UI-READABILITY-02A / F-04: the four permission states keep title, explanation and action reachable, never under the persistent
 * bottom navigation, and stay centred when there is spare height. A fake 80 dp bottom bar models the Scaffold, whose inner padding
 * every real call site hands to [AudioPermissionGate].
 */
@RunWith(Parameterized::class)
class AudioPermissionLayoutInstrumentedTest(
    private val state: String,
    private val widthDp: Int,
    private val heightDp: Int,
    private val fontScale: Float,
) {
    companion object {
        private val states = listOf("NotRequested", "Denied", "PermanentlyDenied", "Revoked")
        private val configs = listOf(
            Triple(320, 640, 1.0f), Triple(320, 640, 1.5f), Triple(320, 640, 2.0f),
            Triple(392, 851, 2.0f), Triple(618, 320, 1.5f), Triple(320, 480, 3.0f),
        )

        @JvmStatic @Parameterized.Parameters(name = "{0} {1}x{2} fs{3}")
        fun params() = states.flatMap { s -> configs.map { (w, h, f) -> arrayOf<Any>(s, w, h, f) } }
    }

    @get:Rule val rule = createComposeRule()

    private var actionCalls = 0

    private class Spec(val title: String, val body: String, val action: String, val iconDp: Int)

    private val spec: Spec get() = when (state) {
        "NotRequested" -> Spec(AudioPermissionCopy.FIRST_RUN_TITLE, "StereoSine needs access to audio files", "Allow access", 64)
        "Denied" -> Spec("Music access denied", "StereoSine cannot scan your local music", "Retry", 56)
        "PermanentlyDenied" -> Spec(AudioPermissionCopy.BLOCKED_TITLE, "Open Android Settings, then go to Permissions", "Open Settings", 56)
        else -> Spec(AudioPermissionCopy.REVOKED_TITLE, "StereoSine no longer has permission", "Open Settings", 56)
    }

    @Composable private fun Body() {
        val onAction: () -> Unit = { actionCalls++ }
        when (state) {
            "NotRequested" -> AllowMusicAccessContent(onRequestPermission = onAction)
            "Denied" -> AudioPermissionDeniedContent(onRetry = onAction)
            "PermanentlyDenied" -> AudioPermissionBlockedContent(onOpenSettings = onAction)
            else -> AudioPermissionRevokedContent(onOpenSettings = onAction)
        }
    }

    /**
     * `Modifier.size` is coerced to the host window, so a fixture taller than the window silently shrinks (320x480 in a 320 dp-high
     * window becomes 320x320, i.e. 240 dp above the fake bar) and then measures the host, not the layout. A shrunken fixture that still
     * passes only proves more, so every configuration runs regardless, EXCEPT the one where the 3.0x title alone (252 dp) is taller than
     * the shrunken 240 dp viewport: that combination cannot exist and is skipped with an explicit reason. Run this class under a window
     * at least 320x480 dp (e.g. the default 1080x2340 portrait) to exercise it for real.
     */
    private fun assumeHostCanHostFixture() {
        if (fontScale < 3.0f) return
        val m = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
        val hostW = m.widthPixels / m.density
        val hostH = m.heightPixels / m.density
        assumeTrue("invalid fixture: ${widthDp}x$heightDp dp does not fit the ${hostW}x$hostH dp host window", hostW >= widthDp && hostH >= heightDp)
    }

    private fun show() = rule.setContent {
        FixedViewport(widthDp, heightDp, fontScale) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth().testTag("viewport")) { Body() }
                Box(Modifier.fillMaxWidth().height(80.dp).testTag("bottomBar"))
            }
        }
    }

    @Test fun title_body_and_action_are_reachable_clear_of_the_bottom_bar_and_clickable() {
        assumeHostCanHostFixture()
        show()
        val s = spec
        val viewport = rule.onNodeWithTag("viewport").visibleBounds()
        val bar = rule.onNodeWithTag("bottomBar").visibleBounds()

        rule.onNodeWithText(s.title).performScrollTo().assertNotClipped(s.title)
        // A paragraph can itself be taller than a very short viewport; it must then be readable by scrolling, so only require it shown.
        rule.onNodeWithText(s.body, substring = true).performScrollTo().assertIsDisplayed()
        val action = rule.onNodeWithText(s.action).performScrollTo().assertIsDisplayed().assertIsEnabled().assertNotClipped(s.action)

        val b = action.visibleBounds()
        assertTrue("action inside viewport: $b vs $viewport", b.top >= viewport.top - 1f && b.bottom <= viewport.bottom + 1f)
        assertTrue("action must not overlap the bottom bar: $b vs $bar", b.bottom <= bar.top + 1f)

        action.performClick()
        assertEquals("the existing callback fires exactly once", 1, actionCalls)
    }

    @Test fun content_is_centred_when_there_is_spare_height() {
        assumeHostCanHostFixture()
        show()
        val s = spec
        val viewport = rule.onNodeWithTag("viewport").unclippedBounds()
        val density = rule.density.density
        // Un-clipped layout bounds: centring is a layout property; overflow reachability is covered by the other test.
        val title = rule.onNodeWithText(s.title).unclippedBounds()
        val button = rule.onNodeWithText(s.action).unclippedBounds()
        val contentHeight = (button.bottom - title.top) + (s.iconDp + 20) * density
        if (contentHeight + 32 * density < viewport.height) { // ample height: spare space must be split evenly
            val gapAboveIcon = title.top - (s.iconDp + 20) * density - viewport.top
            val gapBelow = viewport.bottom - button.bottom
            assertTrue("centred: above=$gapAboveIcon below=$gapBelow", kotlin.math.abs(gapAboveIcon - gapBelow) <= 4f * density)
        }
    }
}
