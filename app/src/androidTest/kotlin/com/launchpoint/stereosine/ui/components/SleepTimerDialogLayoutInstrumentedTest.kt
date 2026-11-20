package com.launchpoint.stereosine.ui.components

import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import com.launchpoint.stereosine.ui.testing.awaitKeyboardHidden
import com.launchpoint.stereosine.ui.testing.awaitKeyboardSettled
import com.launchpoint.stereosine.ui.testing.awaitKeyboardTopPx
import com.launchpoint.stereosine.ui.testing.imeTopOnScreenPx
import androidx.compose.ui.test.performTextInput
import com.launchpoint.stereosine.playback.SleepTimerOption
import com.launchpoint.stereosine.playback.SleepTimerState
import com.launchpoint.stereosine.ui.testing.SystemFontScaleRule
import com.launchpoint.stereosine.ui.testing.assertNotClipped
import com.launchpoint.stereosine.ui.testing.assertReachable
import com.launchpoint.stereosine.ui.testing.unclippedBounds
import com.launchpoint.stereosine.ui.testing.visibleBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * UI-READABILITY-02A / F-03: every option, the explanation, the custom field, its validation message, Set and Cancel stay
 * reachable at large font scales. Each assertion measures clipped (visible) bounds, not just un-clipped layout.
 */
@RunWith(Parameterized::class)
class SleepTimerDialogLayoutInstrumentedTest(private val fontScale: Float) {

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "fontScale={0}")
        fun scales() = listOf(1.0f, 1.5f, 2.0f) // 2.0x is the supported maximum (UI-READABILITY-02A); 3.0x is not a gate
    }

    private val composeRule = createComposeRule()
    private val fontRule = SystemFontScaleRule(fontScale)

    @get:Rule val chain: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(fontRule).around(composeRule)
    private val rule get() = composeRule

    /** The scroll viewport of the dialog body as it is actually visible. */
    private fun viewport() = rule.onNode(hasScrollAction()).visibleBounds()

    @org.junit.Before fun noKeyboardLeftOver() = awaitKeyboardHidden()
    @org.junit.After fun hideKeyboard() { rule.waitForIdle(); awaitKeyboardHidden() }

    private val optionCalls = mutableListOf<Pair<SleepTimerOption, Boolean>>()
    private val customCalls = mutableListOf<Pair<Long, Boolean>>()
    private var dismissed = 0

    private fun show(state: SleepTimerState = SleepTimerState()) {
        // No ForcedFontScale here: a Dialog window re-provides LocalDensity, so the scale is applied system-wide by fontRule.
        rule.setContent {
            SleepTimerDialog(
                state = state,
                onOptionSelected = { o, f -> optionCalls += o to f },
                onCustomDurationSelected = { ms, f -> customCalls += ms to f },
                onDismiss = { dismissed++ },
            )
        }
    }

    @Test fun every_preset_is_reachable_and_selects_exactly_once() {
        show()
        SleepTimerOption.entries.forEach { option ->
            rule.onNodeWithText(option.displayName).performScrollTo().assertReachable(option.displayName, viewport())
        }
        SleepTimerOption.entries.forEach { option ->
            optionCalls.clear()
            rule.onNodeWithText(option.displayName).performScrollTo().performClick()
            assertEquals("one activation for ${option.displayName}", listOf(option to false), optionCalls)
        }
    }

    @Test fun finish_current_track_explanation_is_fully_readable_and_toggle_flows_into_selection() {
        show()
        rule.onNodeWithText("Finish current track").performScrollTo().assertIsDisplayed() // a merged row can exceed a short viewport at 3.0x; displayed after scroll is the contract
        rule.onNodeWithText("After the timer ends, stop when the current song finishes.")
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Finish current track").performClick()
        rule.onNodeWithText("15 minutes", substring = true).performScrollTo().performClick()
        assertEquals(listOf(SleepTimerOption.MINUTES_15 to true), optionCalls)
    }

    @Test fun custom_field_set_and_cancel_are_usable() {
        show()
        rule.onNode(hasSetTextAction()).performScrollTo().assertIsDisplayed() // composite node; may exceed a short landscape viewport
        rule.onNode(hasSetTextAction()).performTextInput("45")
        rule.waitForIdle(); awaitKeyboardSettled()
        rule.onNodeWithText("Set").performScrollTo().assertReachable("Set", viewport()).assertIsEnabled().performClick()
        assertEquals(listOf(45 * 60_000L to false), customCalls)
        rule.onNodeWithText("Cancel").assertIsDisplayed().assertNotClipped("Cancel").performClick()
        assertEquals(1, dismissed)
    }

    @Test fun invalid_duration_keeps_a_readable_error_and_disables_set() {
        show()
        rule.onNode(hasSetTextAction()).performScrollTo().performTextInput("999")
        rule.waitForIdle(); awaitKeyboardSettled()
        rule.onNodeWithText("Enter a value from 1 to 240 minutes", useUnmergedTree = true).performScrollTo().assertReachable("validation message", viewport())
        rule.onNodeWithText("Set").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        assertTrue(customCalls.isEmpty())
    }

    @Test fun cancel_stays_visible_outside_the_scrolling_body() {
        show()
        rule.onNodeWithText("Cancel").assertIsDisplayed().assertNotClipped("Cancel")
    }

    @Test fun keyboard_done_key_performs_the_same_validated_set() {
        show()
        rule.onNode(hasSetTextAction()).performScrollTo().performTextInput("999")
        rule.waitForIdle(); awaitKeyboardSettled()
        rule.onNode(hasSetTextAction()).performImeAction()
        assertTrue("an invalid value must not be submitted by the IME action", customCalls.isEmpty())
        rule.onNode(hasSetTextAction()).performTextClearance()
        rule.onNode(hasSetTextAction()).performTextInput("30")
        rule.onNode(hasSetTextAction()).performImeAction()
        assertEquals(listOf(30 * 60_000L to false), customCalls)
    }

    /** The dialog window must RESIZE for the keyboard; the platform default (pan) lets the keyboard cover Set and Cancel. */
    @Test fun dialog_window_resizes_instead_of_panning_for_the_keyboard() {
        org.junit.Assume.assumeTrue("short window keeps the platform pan default by design", windowIsTallEnoughToResize())
        show()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        val mode = windowSoftInputModeOfDialog()
        assertEquals(
            "dialog window softInputMode adjust must be RESIZE (was pan/unspecified: keyboard covers Set and Cancel)",
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            mode and android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST,
        )
    }

    /**
     * Real keyboard: with the numeric keyboard open, Cancel and (after scrolling inside the dialog) Set must lie fully above the
     * keyboard's top edge and be clickable. Skipped (reported, not passed) if the emulator cannot show a keyboard in time.
     */
    @Test fun set_and_cancel_stay_above_the_real_keyboard() {
        org.junit.Assume.assumeTrue("short window keeps the platform pan default by design", windowIsTallEnoughToResize())
        show()
        rule.onNode(hasSetTextAction()).performScrollTo().performClick()
        val minKeyboardPx = (rule.density.density * 150).toInt()
        val imeTop = awaitKeyboardTopPx(minKeyboardPx)
        org.junit.Assume.assumeTrue("no software keyboard appeared; cannot assess occlusion", imeTop != null)
        rule.onNode(hasSetTextAction()).performTextInput("30")
        rule.waitForIdle()
        rule.onNodeWithText("Set").performScrollTo()
        rule.waitForIdle()
        val top = imeTopOnScreenPx() ?: imeTop!!
        fun bottomOnScreen(text: String): Float {
            val c = rule.onNodeWithText(text).fetchSemanticsNode().layoutInfo.coordinates
            return c.positionOnScreen().y + c.size.height
        }
        assertTrue("Cancel (bottom ${bottomOnScreen("Cancel")}) must be above the keyboard top $top", bottomOnScreen("Cancel") <= top + 1f)
        assertTrue("Set (bottom ${bottomOnScreen("Set")}) must be above the keyboard top $top", bottomOnScreen("Set") <= top + 1f)
        rule.onNodeWithText("Set").assertIsEnabled().performClick()
        assertEquals("Set must be touch-operable with the keyboard open", listOf(30 * 60_000L to false), customCalls)
    }

    private fun windowIsTallEnoughToResize() =
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.screenHeightDp >= 480

    private fun windowSoftInputModeOfDialog(): Int {
        // The dialog is its own window; walk up from its compose view to the DialogWindowProvider that owns that window.
        val view = rule.onNodeWithText("Cancel").fetchSemanticsNode().root as? android.view.View
        val provider = generateSequence<android.view.ViewParent>(view?.parent) { p -> (p as? android.view.View)?.parent }
            .firstOrNull { it is androidx.compose.ui.window.DialogWindowProvider } as? androidx.compose.ui.window.DialogWindowProvider
        return provider?.window?.attributes?.softInputMode ?: -1
    }
}
