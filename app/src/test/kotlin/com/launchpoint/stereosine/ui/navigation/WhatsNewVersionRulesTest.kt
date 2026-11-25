package com.launchpoint.stereosine.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewVersionRulesTest {

    @Test
    fun `fresh install shows whats new after onboarding is complete`() {
        assertTrue(
            WhatsNewVersionRules.shouldShow(
                hasCompletedOnboarding = true,
                lastSeenVersionCode = 0,
                currentVersionCode = 2,
            ),
        )
    }

    @Test
    fun `whats new does not show before onboarding is complete`() {
        assertFalse(
            WhatsNewVersionRules.shouldShow(
                hasCompletedOnboarding = false,
                lastSeenVersionCode = 0,
                currentVersionCode = 2,
            ),
        )
    }

    @Test
    fun `whats new does not repeat after dismissal for same version code`() {
        assertFalse(
            WhatsNewVersionRules.shouldShow(
                hasCompletedOnboarding = true,
                lastSeenVersionCode = 2,
                currentVersionCode = 2,
            ),
        )
    }

    @Test
    fun `whats new shows again when version code increases`() {
        assertTrue(
            WhatsNewVersionRules.shouldShow(
                hasCompletedOnboarding = true,
                lastSeenVersionCode = 2,
                currentVersionCode = 3,
            ),
        )
    }

    // RELEASE-11A: Beta 11 is versionCode 11.
    @Test
    fun `update from Beta 10 shows the Beta 11 whats new once`() {
        assertTrue(WhatsNewVersionRules.shouldShow(hasCompletedOnboarding = true, lastSeenVersionCode = 10, currentVersionCode = 11))
        // After the dialog is dismissed the stored version equals the current one: no repeat.
        assertFalse(WhatsNewVersionRules.shouldShow(hasCompletedOnboarding = true, lastSeenVersionCode = 11, currentVersionCode = 11))
    }

    @Test
    fun `fresh Beta 11 install shows one whats new only after onboarding completes`() {
        assertFalse(WhatsNewVersionRules.shouldShow(hasCompletedOnboarding = false, lastSeenVersionCode = 0, currentVersionCode = 11))
        assertTrue(WhatsNewVersionRules.shouldShow(hasCompletedOnboarding = true, lastSeenVersionCode = 0, currentVersionCode = 11))
        assertFalse(WhatsNewVersionRules.shouldShow(hasCompletedOnboarding = true, lastSeenVersionCode = 11, currentVersionCode = 11))
    }

    @Test
    fun `whats new never shows when the stored version is newer than the app`() {
        assertFalse(WhatsNewVersionRules.shouldShow(hasCompletedOnboarding = true, lastSeenVersionCode = 12, currentVersionCode = 11))
    }

    @Test
    fun `version code comparison is numeric`() {
        assertTrue(
            WhatsNewVersionRules.shouldShow(
                hasCompletedOnboarding = true,
                lastSeenVersionCode = 2,
                currentVersionCode = 10,
            ),
        )
    }
}
