package com.launchpoint.stereosine.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
        val density = LocalDensity.current
        val fontScale = density.fontScale
        val bottomInset = NavigationBarDefaults.windowInsets.asPaddingValues().calculateBottomPadding()
        val labelStyle = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp)

        // The user's font scale is never reduced. Instead the width is shared by what each label needs: four equal slots while the
        // widest label fits one (the usual case, identical to the Material bar), otherwise each slot is as wide as its own label
        // (F-05: "Insights" was ellipsised in an 80 dp slot at 2.0x). Measured with the real density, so it follows the system scale.
        val measurer = rememberFitMeasurer()
        val labelWidthsPx = remember(measurer, labelStyle, density.density, fontScale) {
            NAV_LABELS.map { measurer.measure(it, labelStyle, softWrap = false, maxLines = 1).size.width.toFloat() }
        }

        NavigationBar(
            modifier = if (compact) Modifier.heightIn(max = compactNavigationBarHeight(fontScale) + bottomInset) else Modifier,
        ) {
            NavigationSlots(
                labelWidthsPx = labelWidthsPx,
                paddingCandidatesPx = NAV_LABEL_PADDING_CANDIDATES.map { with(density) { it.toPx() } },
                minSlotPx = with(density) { NAV_MIN_ITEM_WIDTH.toPx() },
                itemHeightPx = with(density) { NAV_BAR_HEIGHT.roundToPx() },
                modifier = Modifier.weight(1f),
            ) {
                PrimaryNavigationItem(selected == PrimaryDestination.HOME, onHomeClick, Icons.Default.Home, NAV_LABELS[0], labelStyle)
                PrimaryNavigationItem(selected == PrimaryDestination.SONGS, onSongsClick, Icons.Default.MusicNote, NAV_LABELS[1], labelStyle)
                PrimaryNavigationItem(selected == PrimaryDestination.LIBRARY, onLibraryClick, Icons.Default.LibraryMusic, NAV_LABELS[2], labelStyle)
                PrimaryNavigationItem(selected == PrimaryDestination.INSIGHTS, onInsightsClick, Icons.Default.Insights, NAV_LABELS[3], labelStyle)
            }
        }
    }
}

/**
 * Lays the four items out in the width the bar REALLY has (window insets included), giving each slot at least its label's width:
 * four equal slots while the widest label fits one (identical to the Material bar), otherwise slots proportional to each label's
 * need. The user's font scale is never reduced (F-05: "Insights" was ellipsised in an equal 74-92 dp slot at 2.0x).
 */
@Composable
private fun NavigationSlots(
    labelWidthsPx: List<Float>,
    paddingCandidatesPx: List<Float>,
    minSlotPx: Float,
    itemHeightPx: Int,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    androidx.compose.ui.layout.Layout(content = content, modifier = modifier.selectableGroup()) { measurables, constraints ->
        val width = constraints.maxWidth
        // The Material bar is 80 dp and the compact bar clamps it lower through maxHeight; never take an unbounded/large max.
        val height = minOf(constraints.maxHeight, itemHeightPx)
        val exact = navigationSlotWidths(labelWidthsPx, width.toFloat(), paddingCandidatesPx, minSlotPx)
        val slots = exact.map { it.toInt() }.toMutableList()
        slots[slots.lastIndex] += width - slots.sum() // rounding remainder goes to the last slot so the bar is exactly filled
        val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(slots[i], height)) }
        layout(width, height) {
            var x = 0
            placeables.forEach { it.placeRelative(x, 0); x += it.width }
        }
    }
}

/**
 * One bar item, laid out like Material's `NavigationBarItem` (indicator pill behind the icon, label underneath, same colours and
 * `Tab` selection semantics) but sized by [NavigationSlots], since `NavigationBarItem` always takes an equal share. The label is a single line at
 * the real font scale; the icon carries no description, so the label is the accessible name.
 */
@Composable
private fun PrimaryNavigationItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    labelStyle: TextStyle,
) {
    val colors = NavigationBarItemDefaults.colors()
    val iconColor = if (selected) colors.selectedIconColor else colors.unselectedIconColor
    val textColor = if (selected) colors.selectedTextColor else colors.unselectedTextColor
    Box(
        modifier = Modifier
            .fillMaxSize()
            .selectable(selected = selected, onClick = onClick, role = Role.Tab),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(
                modifier = Modifier
                    .widthIn(max = NAV_INDICATOR_WIDTH)
                    .fillMaxWidth()
                    .height(NAV_INDICATOR_HEIGHT)
                    .clip(CircleShape)
                    .background(if (selected) colors.selectedIndicatorColor else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = iconColor)
            }
            Text(
                text = label,
                style = labelStyle,
                color = textColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis, // last resort only: slots are sized from the measured label width
                textAlign = TextAlign.Center,
            )
        }
    }
}

private val NAV_LABELS = listOf("Home", "Songs", "Library", "Insights")

/** Preferred side padding around a label, tried largest first; a tighter one is used only when the bar is too narrow for more. */
private val NAV_LABEL_PADDING_CANDIDATES = listOf(4.dp, 2.dp, 0.dp)
private val NAV_MIN_ITEM_WIDTH = 48.dp
private val NAV_BAR_HEIGHT = 80.dp // NavigationBar's own height; the compact bar clamps it lower
private val NAV_INDICATOR_WIDTH = 64.dp
private val NAV_INDICATOR_HEIGHT = 32.dp

/**
 * Slot widths (same unit as the inputs) for the bar items. Equal while the widest label, with the largest padding, fits an equal
 * share; otherwise the largest padding for which every label's slot fits the bar, scaled up proportionally to fill it. When even
 * the smallest padding does not fit, slots shrink proportionally (the label's ellipsis is then the last resort). Pure, so it is
 * unit-tested.
 */
internal fun navigationSlotWidths(labelWidths: List<Float>, total: Float, paddingCandidates: List<Float>, minSlot: Float): List<Float> {
    if (labelWidths.isEmpty()) return emptyList()
    val equal = total / labelWidths.size
    fun needs(pad: Float) = labelWidths.map { maxOf(it + 2 * pad, minSlot) }
    if (needs(paddingCandidates.first()).all { it <= equal }) return labelWidths.map { equal }
    val chosen = paddingCandidates.firstOrNull { needs(it).sum() <= total } ?: paddingCandidates.last()
    val n = needs(chosen)
    val scale = total / n.sum()
    return n.map { it * scale }
}
