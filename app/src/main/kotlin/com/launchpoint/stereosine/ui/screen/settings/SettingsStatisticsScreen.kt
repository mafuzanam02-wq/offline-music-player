package com.launchpoint.stereosine.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.stereosine.ui.components.CountLabels
import com.launchpoint.stereosine.data.model.SmartCollectionType
import com.launchpoint.stereosine.ui.components.MiniPlayer
import com.launchpoint.stereosine.ui.components.PrimaryDestination
import com.launchpoint.stereosine.ui.components.PrimaryNavigationBar
import com.launchpoint.stereosine.ui.components.rememberFitMeasurer
import com.launchpoint.stereosine.ui.components.widestWordPx
import com.launchpoint.stereosine.ui.screen.settings.InsightsHubUiState.Content
import com.launchpoint.stereosine.ui.screen.statistics.StatisticsFormatters
import com.launchpoint.stereosine.ui.viewmodel.PlaybackControlsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsStatisticsScreen(
    onNavigateBack: () -> Unit,
    onStatisticsClick: () -> Unit,
    onReportsClick: () -> Unit,
    onMonthlyReportsClick: () -> Unit,
    onWrappedClick: () -> Unit,
    showBackArrow: Boolean = true,
    onSmartCollectionClick: (SmartCollectionType) -> Unit = {},
    onHomeClick: () -> Unit = {},
    onSongsClick: () -> Unit = {},
    onLibraryClick: () -> Unit = {},
    onInsightsClick: () -> Unit = {},
    onNowPlayingClick: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    playbackVm: PlaybackControlsViewModel = hiltViewModel(),
    insightsVm: InsightsViewModel = hiltViewModel(),
) {
    val nowPlaying by playbackVm.nowPlayingState.collectAsStateWithLifecycle()
    val hubState   by insightsVm.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Insights") },
                navigationIcon = {
                    if (showBackArrow) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onSearchClick) {
                        Icon(
                            imageVector        = Icons.Default.Search,
                            contentDescription = "Search",
                        )
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector        = Icons.Default.Settings,
                            contentDescription = "Settings",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            Column {
                MiniPlayer(
                    nowPlaying                 = nowPlaying,
                    onOpenNowPlaying           = onNowPlayingClick,
                    onTogglePlayPause          = playbackVm::togglePlayPause,
                    onPrevious                 = playbackVm::skipToPrevious,
                    onNext                     = playbackVm::skipToNext,
                    onToggleShuffle            = playbackVm::toggleShuffle,
                    onCycleRepeatMode          = playbackVm::cycleRepeatMode,
                    applyNavigationBarsPadding = false,
                )
                PrimaryNavigationBar(
                    selected        = PrimaryDestination.INSIGHTS,
                    onHomeClick     = onHomeClick,
                    onSongsClick    = onSongsClick,
                    onLibraryClick  = onLibraryClick,
                    onInsightsClick = onInsightsClick,
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }

            // ── Hero Summary ──────────────────────────────────────────────────
            item {
                InsightsHubSummaryRow(hubState = hubState)
            }

            if (hubState is Content) {
                val content = hubState as Content

                item { Spacer(Modifier.height(24.dp)) }

                // ── Rediscover ────────────────────────────────────────────────
                item { InsightsSectionHeader("Rediscover") }
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    RediscoverCard(
                        forgottenGemsCount  = content.forgottenGemsCount,
                        recentlyPlayedCount = content.recentlyPlayedCount,
                        neverPlayedCount    = content.neverPlayedCount,
                        onCollectionClick   = onSmartCollectionClick,
                    )
                }

                item { Spacer(Modifier.height(24.dp)) }

                // ── This Month ────────────────────────────────────────────────
                item { InsightsSectionHeader("This Month") }
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    InsightsThisMonthCard(
                        playCount     = content.thisMonthPlayCount,
                        listeningMs   = content.thisMonthListeningTimeMs,
                        topTrackTitle = content.thisMonthTopTrackTitle,
                        onViewMonthly = onMonthlyReportsClick,
                    )
                }

                item { Spacer(Modifier.height(24.dp)) }

                // ── Listening Habits ──────────────────────────────────────────
                item { InsightsSectionHeader("Listening Habits") }
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    InsightsStatGrid(
                        stats = listOf(
                            InsightsStat("Top Day", StatisticsFormatters.formatDayOfWeekShort(content.mostActiveDayOfWeek), Icons.Default.DateRange),
                            InsightsStat("Top Hour", formatHour(content.mostActiveHour), Icons.Default.Timer),
                        ),
                    )
                }

                item { Spacer(Modifier.height(24.dp)) }
            } else {
                item { Spacer(Modifier.height(16.dp)) }
            }

            // ── More Insights ─────────────────────────────────────────────────
            item { InsightsSectionHeader("More Insights") }
            item { Spacer(Modifier.height(8.dp)) }
            item {
                InsightsDestinationGrid(
                    onStatisticsClick     = onStatisticsClick,
                    onReportsClick        = onReportsClick,
                    onMonthlyReportsClick = onMonthlyReportsClick,
                    onWrappedClick        = onWrappedClick,
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

// ── Section header ────────────────────────────────────────────────────────────

@Composable
private fun InsightsSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text     = title,
        style    = MaterialTheme.typography.labelLarge,
        color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = modifier,
    )
}

// ── Summary row ───────────────────────────────────────────────────────────────

@Composable
internal fun InsightsHubSummaryRow(
    hubState: InsightsHubUiState,
    modifier: Modifier = Modifier,
) {
    InsightsStatGrid(
        stats = listOf(
            InsightsStat(
                label = "Total Plays",
                value = when (hubState) {
                    is Content                 -> formatPlays(hubState.totalPlayCount)
                    InsightsHubUiState.Empty   -> "0"
                    InsightsHubUiState.Loading -> "—"
                },
                icon  = Icons.Default.MusicNote,
            ),
            InsightsStat(
                label = "Listening",
                value = when (hubState) {
                    is Content                 -> StatisticsFormatters.formatDurationSummary(hubState.totalListeningTimeMs)
                    InsightsHubUiState.Empty   -> "0m"
                    InsightsHubUiState.Loading -> "—"
                },
                icon  = Icons.Default.Timer,
            ),
            InsightsStat(
                label = "Streak",
                value = when (hubState) {
                    is Content                 -> StatisticsFormatters.formatStreakDays(hubState.currentStreakDays)
                    InsightsHubUiState.Empty   -> "—"
                    InsightsHubUiState.Loading -> "—"
                },
                icon  = Icons.Default.Star,
            ),
        ),
        modifier = modifier,
    )
}

private class InsightsStat(val label: String, val value: String, val icon: ImageVector)

/** One text of a card with the style it is drawn in: what the grid measures to choose a column count. */
private class FitText(val text: String, val style: TextStyle)

/**
 * Lays cards out in the most columns (up to [maxColumns]) in which EVERY card still fits its widest word, at the user's font scale,
 * on one line; otherwise fewer columns, down to one full-width card per row. F-15: three equal ~96 dp columns at 2.0x split
 * "Listening" and "Statistics" mid-word. The choice is made from measured widths ([horizontalChrome] is the card padding); text
 * is never shrunk, ellipsised or re-sized, and card heights are natural.
 */
@Composable
private fun InsightsCardGrid(
    count: Int,
    gap: Dp,
    horizontalChrome: Dp,
    maxColumns: Int,
    fitTexts: @Composable (Int) -> List<FitText>,
    modifier: Modifier = Modifier,
    card: @Composable (Int, Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val measurer = rememberFitMeasurer()
        val density = LocalDensity.current
        var widest = 0f
        for (i in 0 until count) for (t in fitTexts(i)) widest = maxOf(widest, measurer.widestWordPx(t.text, t.style))
        val needPx = widest + with(density) { horizontalChrome.toPx() }
        val columns = (minOf(maxColumns, count) downTo 1).firstOrNull { n ->
            needPx <= with(density) { maxWidth.toPx() - gap.toPx() * (n - 1) } / n
        } ?: 1
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            (0 until count).chunked(columns).forEach { indices ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    indices.forEach { card(it, Modifier.weight(1f)) }
                    repeat(columns - indices.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun InsightsStatGrid(stats: List<InsightsStat>, modifier: Modifier = Modifier) {
    val valueStyle = MaterialTheme.typography.titleMedium
    val labelStyle = MaterialTheme.typography.labelSmall
    InsightsCardGrid(
        count = stats.size, gap = 8.dp, horizontalChrome = 24.dp, maxColumns = 3, modifier = modifier,
        fitTexts = { listOf(FitText(stats[it].value, valueStyle), FitText(stats[it].label, labelStyle)) },
    ) { i, m -> InsightsStatCard(stats[i].label, stats[i].value, stats[i].icon, m) }
}

@Composable
private fun InsightsStatCard(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.primary,
                modifier           = Modifier.size(20.dp),
            )
            Text(
                text     = value,
                style    = MaterialTheme.typography.titleMedium,
                color    = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text  = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

// ── Rediscover ────────────────────────────────────────────────────────────────

@Composable
private fun RediscoverCard(
    forgottenGemsCount: Int,
    recentlyPlayedCount: Int,
    neverPlayedCount: Int,
    onCollectionClick: (SmartCollectionType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            InsightsCollectionRow(
                label    = "Forgotten Gems",
                subtitle = "Songs you used to love",
                icon     = Icons.Default.History,
                count    = forgottenGemsCount,
                onClick  = { onCollectionClick(SmartCollectionType.FORGOTTEN_GEMS) },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            InsightsCollectionRow(
                label    = "Recently Played",
                subtitle = "Your latest listening activity",
                icon     = Icons.Default.Schedule,
                count    = recentlyPlayedCount,
                onClick  = { onCollectionClick(SmartCollectionType.RECENTLY_PLAYED) },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            InsightsCollectionRow(
                label    = "Never Played",
                subtitle = "Tracks waiting to be discovered",
                icon     = Icons.Default.MusicNote,
                count    = neverPlayedCount,
                onClick  = { onCollectionClick(SmartCollectionType.NEVER_PLAYED) },
            )
        }
    }
}

@Composable
private fun InsightsCollectionRow(
    label: String,
    subtitle: String,
    icon: ImageVector,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            tint               = MaterialTheme.colorScheme.primary,
            modifier           = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text  = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Text(
            text  = CountLabels.songs(count),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
        Icon(
            imageVector        = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint               = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier           = Modifier.size(20.dp),
        )
    }
}

// ── This Month ────────────────────────────────────────────────────────────────

@Composable
private fun InsightsThisMonthCard(
    playCount: Int?,
    listeningMs: Long?,
    topTrackTitle: String?,
    onViewMonthly: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (playCount != null && listeningMs != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text  = formatPlays(playCount),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text  = "Plays",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text  = StatisticsFormatters.formatDurationSummary(listeningMs),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text  = "Listening",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }
                if (topTrackTitle != null) {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector        = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint               = MaterialTheme.colorScheme.primary,
                            modifier           = Modifier.size(16.dp),
                        )
                        Column {
                            Text(
                                text     = topTrackTitle,
                                style    = MaterialTheme.typography.bodyMedium,
                                color    = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text  = "Top track this month",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
                HorizontalDivider()
                TextButton(
                    onClick  = onViewMonthly,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("View monthly report")
                }
            } else {
                Text(
                    text  = "Your listening activity for this month will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

// ── Destination cards ─────────────────────────────────────────────────────────

@Composable
internal fun InsightsDestinationGrid(
    onStatisticsClick: () -> Unit,
    onReportsClick: () -> Unit,
    onMonthlyReportsClick: () -> Unit,
    onWrappedClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val destinations = listOf(
        InsightsDestination("Statistics", "Totals, streaks, and listening history", Icons.Default.Insights, onStatisticsClick),
        InsightsDestination("Reports", "Ranked songs, artists, and albums", Icons.Default.History, onReportsClick),
        InsightsDestination("Monthly", "Month-by-month listening", Icons.Default.DateRange, onMonthlyReportsClick),
        InsightsDestination("Wrapped", "Monthly and yearly recaps", Icons.Default.AutoAwesome, onWrappedClick),
    )
    val titleStyle = MaterialTheme.typography.titleMedium
    val subtitleStyle = MaterialTheme.typography.bodySmall
    InsightsCardGrid(
        count = destinations.size, gap = 12.dp, horizontalChrome = 32.dp, maxColumns = 2, modifier = modifier,
        fitTexts = { listOf(FitText(destinations[it].title, titleStyle), FitText(destinations[it].subtitle, subtitleStyle)) },
    ) { i, m -> destinations[i].let { InsightsDestinationCard(it.title, it.subtitle, it.icon, it.onClick, m) } }
}

private class InsightsDestination(val title: String, val subtitle: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
private fun InsightsDestinationCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .semantics { role = Role.Button }
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.primary,
                modifier           = Modifier.size(28.dp),
            )
            Text(
                text  = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text  = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
        }
    }
}

// ── Local formatters ──────────────────────────────────────────────────────────

private fun formatPlays(count: Int): String = count.toString()

private fun formatHour(hour: Int?): String = when {
    hour == null -> "—"
    hour == 0    -> "12 AM"
    hour < 12    -> "$hour AM"
    hour == 12   -> "12 PM"
    else         -> "${hour - 12} PM"
}
