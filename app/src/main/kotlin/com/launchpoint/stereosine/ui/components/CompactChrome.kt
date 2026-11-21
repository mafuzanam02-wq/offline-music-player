package com.launchpoint.stereosine.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Persistent chrome (top bar + MiniPlayer + bottom navigation) costs roughly 300 dp at full size. On a window shorter than this
 * (a phone in landscape, a short split-screen pane) that leaves a screen with no content area, so the chrome switches to a
 * compact presentation. Decided from the height the layout is actually given, never from a device model or raw pixel size.
 */
internal val COMPACT_CHROME_MAX_HEIGHT: Dp = 480.dp

/** Narrowest window on which the single-row MiniPlayer keeps a readable title next to its five transport buttons. */
internal val COMPACT_MINI_PLAYER_MIN_WIDTH: Dp = 400.dp

/** Bottom navigation height (excluding system insets) in compact chrome; the standard bar is 80 dp. */
internal val COMPACT_NAVIGATION_BAR_HEIGHT: Dp = 56.dp

/** An unbounded height (e.g. inside a scrolling parent) never counts as compact. */
internal fun isCompactChromeHeight(availableHeight: Dp): Boolean =
    availableHeight != Dp.Infinity && availableHeight < COMPACT_CHROME_MAX_HEIGHT

/** The compact bar grows with the label so a large font never clips the selected indicator; it never exceeds the standard 80 dp. */
internal fun compactNavigationBarHeight(fontScale: Float): Dp =
    (COMPACT_NAVIGATION_BAR_HEIGHT + 14.dp * (fontScale - 1f).coerceIn(0f, 1f)).coerceAtMost(80.dp)
