package com.launchpoint.stereosine.ui.theme

import com.launchpoint.stereosine.data.settings.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI-05E. The status-bar icon decision is a pure function of (app theme mode, system dark mode). The same function drives
 * [StereoSineTheme], so the bars can never disagree with the colours on screen. Real window behaviour is covered by
 * SystemBarAppearanceInstrumentedTest.
 */
class SystemBarsDecisionTest {

    @Test fun lightModeIsLightEvenWhenThePhoneIsDark() {
        assertFalse(resolveUseDarkTheme(ThemeMode.LIGHT, systemInDarkTheme = true))
        assertFalse(resolveUseDarkTheme(ThemeMode.LIGHT, systemInDarkTheme = false))
    }

    @Test fun darkModeIsDarkEvenWhenThePhoneIsLight() {
        assertTrue(resolveUseDarkTheme(ThemeMode.DARK, systemInDarkTheme = true))
        assertTrue(resolveUseDarkTheme(ThemeMode.DARK, systemInDarkTheme = false))
    }

    @Test fun systemModeFollowsTheAndroidAppearance() {
        assertTrue(resolveUseDarkTheme(ThemeMode.SYSTEM, systemInDarkTheme = true))
        assertFalse(resolveUseDarkTheme(ThemeMode.SYSTEM, systemInDarkTheme = false))
    }

    @Test fun everyModeAndSystemCombinationIsCoveredExactlyOnce() {
        // value = (dark on a light phone, dark on a dark phone)
        val expected = mapOf(
            ThemeMode.SYSTEM to (false to true),
            ThemeMode.LIGHT to (false to false),
            ThemeMode.DARK to (true to true),
        )
        assertEquals(ThemeMode.entries.toSet(), expected.keys)
        expected.forEach { (mode, pair) ->
            assertEquals("$mode on a light phone", pair.first, resolveUseDarkTheme(mode, systemInDarkTheme = false))
            assertEquals("$mode on a dark phone", pair.second, resolveUseDarkTheme(mode, systemInDarkTheme = true))
        }
    }

    @Test fun switchingBackToSystemLeavesNoStaleOverride() {
        // The decision holds no state: LIGHT or DARK followed by SYSTEM resolves from the system value alone.
        for (system in listOf(false, true)) {
            val sequence = listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM)
            val resolved = sequence.map { resolveUseDarkTheme(it, system) }
            assertEquals("SYSTEM after overrides, system dark=$system", system, resolved.last())
        }
    }
}
