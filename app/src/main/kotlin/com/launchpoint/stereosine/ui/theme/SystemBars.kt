package com.launchpoint.stereosine.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import com.launchpoint.stereosine.data.settings.ThemeMode

/**
 * UI-05E. The one decision "is the app drawing its dark colour scheme", shared by [StereoSineTheme] and the system bars.
 * [ThemeMode.SYSTEM] follows the Android appearance; [ThemeMode.LIGHT] and [ThemeMode.DARK] override it.
 */
internal fun resolveUseDarkTheme(themeMode: ThemeMode, systemInDarkTheme: Boolean): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> systemInDarkTheme
    ThemeMode.LIGHT  -> false
    ThemeMode.DARK   -> true
}

// Same scrim values as the platform defaults used by a bare enableEdgeToEdge(), so navigation-bar contrast is unchanged.
private val NavigationBarLightScrim = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
private val NavigationBarDarkScrim = Color.argb(0x80, 0x1B, 0x1B, 0x1B)

/** Status bar: transparent (content draws behind it); icons are dark on a light app theme and light on a dark one. */
internal fun statusBarStyleFor(useDarkTheme: Boolean): SystemBarStyle =
    SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { useDarkTheme }

/** Navigation bar: keeps the existing contrast scrim, now chosen by the app theme instead of only the system one. */
internal fun navigationBarStyleFor(useDarkTheme: Boolean): SystemBarStyle =
    SystemBarStyle.auto(NavigationBarLightScrim, NavigationBarDarkScrim) { useDarkTheme }

/**
 * Re-applies edge-to-edge whenever the resolved app theme changes, without recreating the Activity.
 * A bare enableEdgeToEdge() in onCreate reads only the system dark mode, so an app-level Light theme on a dark phone
 * (or the reverse) left white icons on a light header.
 */
@Composable
internal fun ApplySystemBarStyle(useDarkTheme: Boolean) {
    val activity = LocalContext.current.findComponentActivity() ?: return
    DisposableEffect(activity, useDarkTheme) {
        activity.enableEdgeToEdge(
            statusBarStyle = statusBarStyleFor(useDarkTheme),
            navigationBarStyle = navigationBarStyleFor(useDarkTheme),
        )
        onDispose { }
    }
}

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}
