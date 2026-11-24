package com.launchpoint.stereosine.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import com.launchpoint.stereosine.data.model.Song

/**
 * Song row with a "⋮" overflow menu containing the standard actions.
 *
 * Use this instead of bare [SongRow] wherever overflow actions are needed.
 * [onRemove] and [onViewFolder] are optional and only shown when non-null.
 */
@Composable
fun SongRowWithOverflow(
    song: Song,
    isCurrent: Boolean,
    isFavorite: Boolean,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onTrackDetails: () -> Unit,
    onRemove: (() -> Unit)? = null,
    onViewFolder: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onAlbumClick: ((String) -> Unit)? = null,
    onArtistClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    highlightedTitle: AnnotatedString? = null,
    highlightedArtist: AnnotatedString? = null,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        SongRow(
            song               = song,
            isCurrent          = isCurrent,
            isFavorite         = isFavorite,
            onClick            = onPlay,
            onToggleFavorite   = onToggleFavorite,
            onOpenDetails      = { expanded = true },
            showFavoriteButton = false,
            onMoreClick        = { expanded = true },
            modifier           = Modifier.fillMaxWidth(),
            highlightedTitle   = highlightedTitle,
            highlightedArtist  = highlightedArtist,
            // The menu is composed inside the three-dot button's own box (see SongRow) so the popup anchors to the button.
            overflowMenu       = {
        DropdownMenu(
            expanded         = expanded,
            onDismissRequest = { expanded = false },
        ) {
            SongActionMenuItems(
                song             = song,
                isFavorite       = isFavorite,
                onDismiss        = { expanded = false },
                onPlay           = onPlay,
                onPlayNext       = onPlayNext,
                onAddToQueue     = onAddToQueue,
                onAddToPlaylist  = onAddToPlaylist,
                onToggleFavorite = onToggleFavorite,
                onTrackDetails   = onTrackDetails,
                onArtistClick    = onArtistClick,
                onAlbumClick     = onAlbumClick,
                onShare          = onShare,
                onViewFolder     = onViewFolder,
                hasScreenActions = onRemove != null,
            ) {
                if (onRemove != null) {
                    SongMenuItem("Remove", Icons.Default.Delete, { expanded = false; onRemove() }, destructive = true)
                }
            }
        }
            },
        )
    }
}
