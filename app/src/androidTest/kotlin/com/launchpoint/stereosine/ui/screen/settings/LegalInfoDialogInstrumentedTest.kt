package com.launchpoint.stereosine.ui.screen.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * BRAND-09A: the Terms dialog keeps every paragraph readable offline and adds only a supplementary, explicit
 * "View Terms Online" action. Opening the dialog must not trigger the online action.
 */
class LegalInfoDialogInstrumentedTest {

    @get:Rule val rule = createComposeRule()

    private fun show(onDismiss: () -> Unit = {}, onOnline: () -> Unit = {}) = rule.setContent {
        LegalInfoDialog(
            title       = "Terms of Use",
            paragraphs  = StereoSineAbout.TERMS_OF_USE,
            onDismiss   = onDismiss,
            onlineLabel = StereoSineAbout.TERMS_ONLINE_LABEL,
            onOnline    = onOnline,
        )
    }

    @Test fun opening_the_dialog_does_not_launch_the_online_action_and_shows_the_offline_terms() {
        var online = 0
        show(onOnline = { online++ })
        rule.onNodeWithText("Terms of Use").assertIsDisplayed()
        rule.onNodeWithText(StereoSineAbout.TERMS_OF_USE.first()).assertIsDisplayed()
        assertEquals("opening the dialog launches nothing", 0, online)
    }

    @Test fun view_terms_online_is_one_enabled_button_with_a_usable_touch_target() {
        show()
        rule.onNodeWithText("View Terms Online").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(40.dp)
    }

    @Test fun tapping_view_terms_online_invokes_the_online_action_once_and_keeps_the_dialog_open() {
        var online = 0
        var dismissed = 0
        show(onDismiss = { dismissed++ }, onOnline = { online++ })
        rule.onNodeWithText("View Terms Online").performClick()
        assertEquals(1, online)
        assertEquals(0, dismissed)
        rule.onNodeWithText("Close").assertIsDisplayed()
    }

    @Test fun close_still_dismisses_without_launching_anything() {
        var online = 0
        var dismissed = 0
        show(onDismiss = { dismissed++ }, onOnline = { online++ })
        rule.onNodeWithText("Close").performClick()
        assertEquals(1, dismissed)
        assertEquals(0, online)
    }

    @Test fun dialogs_without_an_online_label_show_no_online_button() {
        rule.setContent { LegalInfoDialog(title = "Disclaimer", paragraphs = StereoSineAbout.DISCLAIMER, onDismiss = {}) }
        rule.onNodeWithText("Close").assertIsDisplayed()
        rule.onNodeWithText("View Terms Online").assertDoesNotExist()
    }
}
