package com.launchpoint.stereosine.ui.theme

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import com.launchpoint.stereosine.data.settings.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * UI-05E. Real window system-bar state on a real Activity: the status-bar icon style (and navigation-bar icon style, i.e.
 * its contrast treatment) must follow the app's Light/Dark/System choice, update live without recreating the Activity,
 * and not go stale when returning to System. Reads WindowInsetsController, not pixels.
 *
 * The expected value for SYSTEM and the "bare enableEdgeToEdge" control come from the device's actual night mode, so the
 * same class is run with the emulator in both night modes (cmd uimode night yes / no).
 */
class SystemBarAppearanceInstrumentedTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var mode by mutableStateOf(ThemeMode.LIGHT)
    private lateinit var activity: ComponentActivity

    @Before fun setUp() {
        mode = ThemeMode.LIGHT
        rule.setContent { ApplySystemBarStyle(resolveUseDarkTheme(mode, isSystemInDarkTheme())) }
        rule.activityRule.scenario.onActivity { activity = it }
        rule.waitForIdle()
    }

    private fun systemIsDark(): Boolean =
        (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** True when the status-bar ICONS are drawn dark (for a light app surface). */
    private fun darkStatusIcons(): Boolean {
        var v = false
        rule.runOnUiThread { v = WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars }
        return v
    }

    private fun darkNavigationIcons(): Boolean {
        var v = false
        rule.runOnUiThread { v = WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightNavigationBars }
        return v
    }

    private fun choose(m: ThemeMode) { rule.runOnUiThread { mode = m }; rule.waitForIdle() }

    @Test fun lightThemeUsesDarkIconsOnBothBars_whateverTheSystemIs() {
        choose(ThemeMode.LIGHT)
        assertEquals("status icons are dark on a light app", true, darkStatusIcons())
        assertEquals("navigation icons are dark on a light app", true, darkNavigationIcons())
    }

    @Test fun darkThemeUsesLightIconsOnBothBars_whateverTheSystemIs() {
        choose(ThemeMode.DARK)
        assertEquals(false, darkStatusIcons())
        assertEquals(false, darkNavigationIcons())
    }

    @Test fun systemThemeFollowsTheDeviceAppearance() {
        choose(ThemeMode.SYSTEM)
        assertEquals("system dark=${systemIsDark()}", !systemIsDark(), darkStatusIcons())
        assertEquals(!systemIsDark(), darkNavigationIcons())
    }

    @Test fun switchingThemesLiveNeverRecreatesTheActivityAndAlwaysMatches() {
        val before = activity
        val sequence = listOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM)
        for (m in sequence) {
            choose(m)
            val expectDarkIcons = !resolveUseDarkTheme(m, systemIsDark())
            assertEquals("status icons after switching to $m", expectDarkIcons, darkStatusIcons())
            assertEquals("navigation icons after switching to $m", expectDarkIcons, darkNavigationIcons())
        }
        rule.activityRule.scenario.onActivity { assertSame("same Activity instance, no recreation", before, it); assertFalse(it.isFinishing) }
    }

    @Test fun returningToSystemClearsAnyEarlierOverride() {
        for (override in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            choose(override)
            choose(ThemeMode.SYSTEM)
            assertEquals("SYSTEM after $override", !systemIsDark(), darkStatusIcons())
            assertEquals("SYSTEM after $override (navigation)", !systemIsDark(), darkNavigationIcons())
        }
    }

    @Test fun appearanceSurvivesLeavingAndReturningToTheForeground() {
        choose(ThemeMode.LIGHT)
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
        assertEquals(true, darkStatusIcons())
        choose(ThemeMode.DARK)
        assertEquals(false, darkStatusIcons())
    }
}
