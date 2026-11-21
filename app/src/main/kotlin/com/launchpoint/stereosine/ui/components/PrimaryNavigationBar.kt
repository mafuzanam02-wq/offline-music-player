package com.launchpoint.stereosine.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

enum class PrimaryDestination {
    HOME,
    SONGS,
    LIBRARY,
    INSIGHTS,
}

@Composable
fun PrimaryNavigationBar(
    selected: PrimaryDestination?,
    onHomeClick: () -> Unit,
    onSongsClick: () -> Unit,
    onLibraryClick: () -> Unit,
    onInsightsClick: () -> Unit,
) {
    // On a short window (see CompactChrome.kt) the bar gives back ~24 dp of height; every destination and its label stay.
    // The inner row of NavigationBar requests a fixed 80 dp and is clamped by the constraint set here, so the system inset
    // (gesture area) is added on top and is never cut.
    BoxWithConstraints {
        val compact = isCompactChromeHeight(maxHeight)
        val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
        val bottomInset = NavigationBarDefaults.windowInsets.asPaddingValues().calculateBottomPadding()
        NavigationBar(
            modifier = if (compact) Modifier.heightIn(max = compactNavigationBarHeight(fontScale) + bottomInset) else Modifier,
        ) {
            val navLabelStyle = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp)
            NavigationBarItem(
                selected = selected == PrimaryDestination.HOME,
                onClick = onHomeClick,
                icon = { Icon(Icons.Default.Home, contentDescription = null) },
                label = { Text("Home", maxLines = 1, overflow = TextOverflow.Ellipsis, style = navLabelStyle) },
            )
            NavigationBarItem(
                selected = selected == PrimaryDestination.SONGS,
                onClick = onSongsClick,
                icon = { Icon(Icons.Default.MusicNote, contentDescription = null) },
                label = { Text("Songs", maxLines = 1, overflow = TextOverflow.Ellipsis, style = navLabelStyle) },
            )
            NavigationBarItem(
                selected = selected == PrimaryDestination.LIBRARY,
                onClick = onLibraryClick,
                icon = { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
                label = { Text("Library", maxLines = 1, overflow = TextOverflow.Ellipsis, style = navLabelStyle) },
            )
            NavigationBarItem(
                selected = selected == PrimaryDestination.INSIGHTS,
                onClick = onInsightsClick,
                icon = { Icon(Icons.Default.Insights, contentDescription = null) },
                label = { Text("Insights", maxLines = 1, overflow = TextOverflow.Ellipsis, style = navLabelStyle) },
            )
        }
    }
}
