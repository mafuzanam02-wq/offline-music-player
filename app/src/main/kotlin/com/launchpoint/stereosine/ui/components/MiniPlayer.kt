package com.launchpoint.stereosine.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import com.launchpoint.stereosine.data.artwork.ArtworkResolver
import com.launchpoint.stereosine.data.model.Song
import com.launchpoint.stereosine.playback.NowPlayingState
import com.launchpoint.stereosine.playback.RepeatMode

@Composable
fun MiniPlayer(
    nowPlaying: NowPlayingState,
    onOpenNowPlaying: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeatMode: () -> Unit,
    modifier: Modifier = Modifier,
    applyNavigationBarsPadding: Boolean = true,
) {
    if (nowPlaying.song == null) return
    val song = nowPlaying.song

    // The height this bar is actually given decides the presentation (see CompactChrome.kt): on a short window the stacked
    // title row + transport row (~120 dp) would leave no room for the screen's content, so everything moves into one row.
    // Every control stays; only the arrangement changes.
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val compact = isCompactChromeHeight(maxHeight) && maxWidth >= COMPACT_MINI_PLAYER_MIN_WIDTH
        Surface(
            modifier       = Modifier.fillMaxWidth(),
            color          = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 8.dp,
        ) {
            val columnModifier = if (applyNavigationBarsPadding) {
                Modifier.fillMaxWidth().navigationBarsPadding()
            } else {
                Modifier.fillMaxWidth()
            }
            Column(
                modifier = columnModifier
                    .padding(horizontal = 12.dp, vertical = if (compact) 4.dp else 10.dp),
            ) {
                MiniPlayerProgress(bottomPadding = if (compact) 2.dp else 6.dp)
                if (compact) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        MiniPlayerIdentity(song, onOpenNowPlaying, compact = true, modifier = Modifier.weight(1f))
                        MiniPlayerControls(
                            nowPlaying, onTogglePlayPause, onPrevious, onNext, onToggleShuffle, onCycleRepeatMode,
                            modifier = Modifier,
                            arrangement = Arrangement.End,
                        )
                    }
                } else {
                    MiniPlayerIdentity(song, onOpenNowPlaying, compact = false, modifier = Modifier.fillMaxWidth())
                    MiniPlayerControls(
                        nowPlaying, onTogglePlayPause, onPrevious, onNext, onToggleShuffle, onCycleRepeatMode,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        arrangement = Arrangement.SpaceEvenly,
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniPlayerIdentity(
    song: Song,
    onOpenNowPlaying: () -> Unit,
    compact: Boolean,
    modifier: Modifier,
) {
    val artwork = if (compact) 40.dp else 44.dp
    // Compact chrome at a large font: two lines of 2x text would take back the height compact chrome exists to give the list.
    val singleLine = compact && androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.3f
    Row(
        modifier          = modifier.clickable(onClick = onOpenNowPlaying),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(
            artworkUri         = ArtworkResolver.albumArtworkUri(song.albumId),
            contentDescription = "Album artwork for ${song.album}",
            placeholderIcon    = Icons.Default.MusicNote,
            artworkSize = artwork,
            modifier           = Modifier.size(artwork),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            if (singleLine) {
                Text(
                    text     = "${song.displayTitle} · ${song.displayArtist}",
                    style    = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text     = song.displayTitle,
                    style    = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text     = song.displayArtist,
                    style    = MaterialTheme.typography.bodyMedium,
                    color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MiniPlayerControls(
    nowPlaying: NowPlayingState,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeatMode: () -> Unit,
    modifier: Modifier,
    arrangement: Arrangement.Horizontal,
) {
    Row(
        modifier              = modifier,
        horizontalArrangement = arrangement,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggleShuffle) {
            Icon(
                imageVector        = Icons.Default.Shuffle,
                contentDescription = if (nowPlaying.shuffleEnabled) "Turn shuffle off" else "Turn shuffle on",
                tint               = if (nowPlaying.shuffleEnabled) MaterialTheme.colorScheme.primary
                                     else MaterialTheme.colorScheme.onSurface,
            )
        }
        IconButton(onClick = onPrevious) {
            Icon(
                imageVector        = Icons.Default.SkipPrevious,
                contentDescription = "Previous",
                tint               = MaterialTheme.colorScheme.onSurface,
            )
        }
        IconButton(onClick = onTogglePlayPause) {
            Icon(
                imageVector        = if (nowPlaying.presentedAsPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (nowPlaying.presentedAsPlaying) "Pause" else "Play",
                tint               = MaterialTheme.colorScheme.onSurface,
                modifier           = Modifier.size(32.dp),
            )
        }
        IconButton(onClick = onNext) {
            Icon(
                imageVector        = Icons.Default.SkipNext,
                contentDescription = "Next",
                tint               = MaterialTheme.colorScheme.onSurface,
            )
        }
        IconButton(onClick = onCycleRepeatMode) {
            Icon(
                imageVector        = if (nowPlaying.repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne
                                     else Icons.Default.Repeat,
                contentDescription = when (nowPlaying.repeatMode) {
                    RepeatMode.OFF -> "Turn repeat all on"
                    RepeatMode.ALL -> "Turn repeat one on"
                    RepeatMode.ONE -> "Turn repeat off"
                },
                tint               = if (nowPlaying.repeatMode == RepeatMode.OFF)
                    MaterialTheme.colorScheme.onSurface
                else
                    MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun MiniPlayerProgress(bottomPadding: Dp) {
    val progress = rememberProgressStateWithTickInterval(
        player = LocalProgressPlayer.current,
        tickIntervalMs = 0L,
    )
    if (progress.durationMs > 0L) {
        LinearProgressIndicator(
            progress = {
                playbackProgressPresentation(
                    progress.currentPositionMs,
                    progress.durationMs,
                ).fraction
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .padding(bottom = bottomPadding),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
        )
    }
}
