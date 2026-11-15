package com.launchpoint.stereosine.ui.screen.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * BRAND-09A/09B: the Privacy Policy and Terms dialogs keep every paragraph readable offline and add only a
 * supplementary, explicit "view online" action. Opening a dialog must not launch anything; Disclaimer has no online action.
 */
class LegalInfoDialogInstrumentedTest {

    @get:Rule val rule = createComposeRule()

    private class RecordingContext(base: Context, private val failWith: RuntimeException? = null) : ContextWrapper(base) {
        val started = mutableListOf<Intent>()
        override fun startActivity(intent: Intent) {
            started += intent
            failWith?.let { throw it }
        }
    }

    private fun recorder(failWith: RuntimeException? = null) =
        RecordingContext(ApplicationProvider.getApplicationContext(), failWith)

    private fun showTerms(onDismiss: () -> Unit = {}, onOnline: () -> Unit = {}) = rule.setContent {
        LegalInfoDialog(
            title = "Terms of Use", paragraphs = StereoSineAbout.TERMS_OF_USE, onDismiss = onDismiss,
            onlineLabel = StereoSineAbout.TERMS_ONLINE_LABEL, onOnline = onOnline,
        )
    }

    private fun showPrivacy(onDismiss: () -> Unit = {}, onOnline: () -> Unit = {}) = rule.setContent {
        LegalInfoDialog(
            title = "Privacy Policy", paragraphs = StereoSineAbout.PRIVACY_POLICY, onDismiss = onDismiss,
            onlineLabel = StereoSineAbout.PRIVACY_ONLINE_LABEL, onOnline = onOnline,
        )
    }

    // ---- Terms (BRAND-09A, regression) ----

    @Test fun terms_opening_does_not_launch_and_shows_offline_text() {
        var online = 0
        showTerms(onOnline = { online++ })
        rule.onNodeWithText("Terms of Use").assertIsDisplayed()
        rule.onNodeWithText(StereoSineAbout.TERMS_OF_USE.first()).assertIsDisplayed()
        assertEquals(0, online)
    }

    @Test fun terms_online_button_is_enabled_with_usable_touch_target() {
        showTerms()
        rule.onNodeWithText("View Terms Online").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(40.dp)
    }

    @Test fun terms_online_tap_opens_the_terms_url_once_and_keeps_dialog_open() {
        val ctx = recorder()
        var dismissed = 0
        showTerms(onDismiss = { dismissed++ }, onOnline = { ctx.openExternalLink(StereoSineAbout.TERMS_URL) })
        assertTrue(ctx.started.isEmpty())
        rule.onNodeWithText("View Terms Online").performClick()
        assertEquals(1, ctx.started.size)
        assertEquals(Intent.ACTION_VIEW, ctx.started.single().action)
        assertEquals("https://launchpointdigital.co.za/stereosine/terms", ctx.started.single().data.toString())
        assertEquals(0, dismissed)
        rule.onNodeWithText("Close").assertIsDisplayed()
    }

    @Test fun terms_close_dismisses_without_launching() {
        var online = 0
        var dismissed = 0
        showTerms(onDismiss = { dismissed++ }, onOnline = { online++ })
        rule.onNodeWithText("Close").performClick()
        assertEquals(1, dismissed)
        assertEquals(0, online)
    }

    // ---- Privacy Policy (BRAND-09B) ----

    @Test fun privacy_opening_does_not_launch_and_shows_offline_text() {
        val ctx = recorder()
        showPrivacy(onOnline = { ctx.openExternalLink(StereoSineAbout.PRIVACY_POLICY_URL) })
        rule.onNodeWithText("Privacy Policy").assertIsDisplayed()
        rule.onNodeWithText(StereoSineAbout.PRIVACY_POLICY.first()).assertIsDisplayed()
        assertTrue("opening the dialog launches nothing", ctx.started.isEmpty())
    }

    @Test fun privacy_online_button_is_enabled_with_usable_touch_target() {
        showPrivacy()
        rule.onNodeWithText("View Privacy Policy Online").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(40.dp)
    }

    @Test fun privacy_online_tap_opens_the_privacy_url_once_and_keeps_dialog_open() {
        val ctx = recorder()
        var dismissed = 0
        showPrivacy(onDismiss = { dismissed++ }, onOnline = { ctx.openExternalLink(StereoSineAbout.PRIVACY_POLICY_URL) })
        rule.onNodeWithText("View Privacy Policy Online").performClick()
        assertEquals(1, ctx.started.size)
        assertEquals(Intent.ACTION_VIEW, ctx.started.single().action)
        assertEquals("https://launchpointdigital.co.za/stereosine/privacy", ctx.started.single().data.toString())
        assertEquals(0, dismissed)
        rule.onNodeWithText("Close").assertIsDisplayed()
    }

    @Test fun privacy_tap_with_no_browser_or_refused_launch_does_not_crash_and_dialog_stays() {
        val missing = recorder(ActivityNotFoundException("no browser"))
        var result: Boolean? = null
        showPrivacy(onOnline = { result = missing.openExternalLink(StereoSineAbout.PRIVACY_POLICY_URL) })
        rule.onNodeWithText("View Privacy Policy Online").performClick()
        assertEquals(false, result)
        rule.onNodeWithText("Close").assertIsDisplayed()
        assertFalse(recorder(SecurityException("blocked")).openExternalLink(StereoSineAbout.PRIVACY_POLICY_URL))
    }

    @Test fun privacy_close_dismisses_without_launching() {
        var online = 0
        var dismissed = 0
        showPrivacy(onDismiss = { dismissed++ }, onOnline = { online++ })
        rule.onNodeWithText("Close").performClick()
        assertEquals(1, dismissed)
        assertEquals(0, online)
    }

    // ---- Disclaimer (unaffected) ----

    @Test fun disclaimer_shows_no_online_button_and_close_dismisses() {
        var dismissed = 0
        rule.setContent { LegalInfoDialog(title = "Disclaimer", paragraphs = StereoSineAbout.DISCLAIMER, onDismiss = { dismissed++ }) }
        rule.onNodeWithText("View Terms Online").assertDoesNotExist()
        rule.onNodeWithText("View Privacy Policy Online").assertDoesNotExist()
        rule.onNodeWithText("Close").performClick()
        assertEquals(1, dismissed)
    }
}
