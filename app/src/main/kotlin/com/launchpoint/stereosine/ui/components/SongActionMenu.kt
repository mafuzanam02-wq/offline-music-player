package com.launchpoint.stereosine.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.launchpoint.stereosine.data.model.Song

/**
 * SONG-MENU-04A. The ONE authoritative per-song action menu content. Every song-row overflow menu (the standard
 * [SongRowWithOverflow], Playlist Details, Most Played) renders these items inside its own `DropdownMenu`, so the common actions,
 * their order, icons, tint and the physical "Delete from device" entry cannot drift apart again.
 *
 * Screen-specific actions (Remove, Move up/down) go in [screenActions]; they are placed after the common groups and before the
 * destructive device-deletion entry, which is always last.
 */

/** Leading glyph size: restrained next to the 16 dp row text; the menu item itself keeps Material's 48 dp touch target. */
internal val SongMenuIconSize = 20.dp

/** Test tag of a menu item's leading icon, e.g. `songMenuIcon:Delete from device`. */
internal fun songMenuIconTag(label: String): String = "songMenuIcon:$label"

/**
 * One menu entry with the shared icon treatment: 20 dp glyph, neutral tint for ordinary actions, error colour only when
 * [destructive]. Material's default item height/padding is kept (touch target and font scaling are unchanged).
 */
@Composable
internal fun SongMenuItem(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val iconTint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    DropdownMenuItem(
        text = { Text(label, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
        onClick = onClick,
        enabled = enabled,
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(SongMenuIconSize).testTag(songMenuIconTag(label)),
            )
        },
    )
}

/**
 * The common song actions, in their fixed order. Optional actions appear only when their callback is supplied (and, for artist /
 * album, when the song has a navigable key). [onDismiss] closes the host menu before the action runs.
 *
 * "Delete from device" is offered only under a [SongDeletionHost] ([LocalSongDeleteRequester]), for an eligible song, and only when
 * [allowDelete] is true (false for e.g. a drag preview). It goes through the shared confirmation + Android authorization flow;
 * it is never wired to a screen's own Remove callback.
 */
@Composable
internal fun ColumnScope.SongActionMenuItems(
    song: Song,
    isFavorite: Boolean,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTrackDetails: () -> Unit,
    onArtistClick: ((String) -> Unit)? = null,
    onAlbumClick: ((String) -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onViewFolder: (() -> Unit)? = null,
    hasScreenActions: Boolean = false,
    allowDelete: Boolean = true,
    screenActions: @Composable ColumnScope.() -> Unit = {},
) {
    val albumKey = metadataNavigationKey(song.album, "Unknown Album")
    val artistKey = metadataNavigationKey(song.artist, "Unknown Artist")
    val deleteRequester = LocalSongDeleteRequester.current

    SongMenuItem("Play", Icons.Default.PlayArrow, { onDismiss(); onPlay() })
    SongMenuItem("Play next", Icons.Default.SkipNext, { onDismiss(); onPlayNext() })
    SongMenuItem("Add to queue", Icons.AutoMirrored.Filled.QueueMusic, { onDismiss(); onAddToQueue() })
    HorizontalDivider()
    SongMenuItem("Add to playlist", Icons.AutoMirrored.Filled.PlaylistAdd, { onDismiss(); onAddToPlaylist() })
    SongMenuItem(
        label = if (isFavorite) "Remove from Favorites" else "Add to Favorites",
        icon = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
        onClick = { onDismiss(); onToggleFavorite() },
    )
    HorizontalDivider()
    if (onArtistClick != null && artistKey != null) {
        SongMenuItem("Go to artist", Icons.Default.Person, { onDismiss(); onArtistClick(artistKey) })
    }
    if (onAlbumClick != null && albumKey != null) {
        SongMenuItem("Go to album", Icons.Default.Album, { onDismiss(); onAlbumClick(albumKey) })
    }
    SongMenuItem("Track details", Icons.Default.Info, { onDismiss(); onTrackDetails() })
    if (onShare != null || onViewFolder != null || hasScreenActions) HorizontalDivider()
    if (onShare != null) SongMenuItem("Share", Icons.Default.Share, { onDismiss(); onShare() })
    if (onViewFolder != null) SongMenuItem("View folder", Icons.Default.Folder, { onDismiss(); onViewFolder() })
    screenActions()
    // Physical deletion, distinct from any screen's own "Remove": shared confirmation + Android authorization (UI-READABILITY-03D).
    if (allowDelete && deleteRequester != null && SongDeletionPolicy.isEligible(song.id)) {
        HorizontalDivider()
        SongMenuItem(
            label = "Delete from device",
            icon = Icons.Default.DeleteForever,
            onClick = { onDismiss(); deleteRequester.requestDelete(song) },
            destructive = true,
        )
    }
}
