package com.launchpoint.stereosine.ui.navigation

import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController

/** A primary tab always opens its main destination, never a restored child stack. */
internal fun NavHostController.navigatePrimary(route: String) {
    // A screen's outgoing bar can still receive a tap during a transition. Its selected
    // indicator is not the authority for whether this is a reselection.
    if (currentDestination?.route == route) return

    val startId = graph.findStartDestination().id
    // Onboarding can already have been removed. The root graph is always a valid
    // fallback; an absent popUpTo target would otherwise allow the stack to grow.
    val anchorId = try {
        getBackStackEntry(startId)
        startId
    } catch (_: IllegalArgumentException) {
        graph.id
    }
    navigate(route) {
        launchSingleTop = true
        restoreState = false
        popUpTo(anchorId) { saveState = false }
    }
}
