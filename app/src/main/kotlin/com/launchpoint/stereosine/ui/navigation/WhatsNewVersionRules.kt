package com.launchpoint.stereosine.ui.navigation

object WhatsNewVersionRules {
    fun shouldShow(
        hasCompletedOnboarding: Boolean,
        lastSeenVersionCode: Int,
        currentVersionCode: Int,
    ): Boolean =
        hasCompletedOnboarding && currentVersionCode > lastSeenVersionCode
}
