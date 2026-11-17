package com.launchpoint.stereosine.ui.screen.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.launchpoint.stereosine.data.search.SearchHistoryEntry

/**
 * SEARCH-01: recent committed searches, shown while the search field is empty.
 *
 * Four distinct actions, none overloaded:
 *  - tapping a row repeats that search ([onSelect]);
 *  - the row's X removes only that entry ([onRemove]) and neither runs the query nor closes search;
 *  - "Clear all" asks for confirmation before calling [onClearAll];
 *  - the search bar's own back arrow (dismiss) and clear-text X live in the top bar and never touch this list.
 */
@Composable
fun RecentSearchesPanel(
    entries: List<SearchHistoryEntry>,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmClear by remember { mutableStateOf(false) }

    if (entries.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text  = "Your recent searches will appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item(key = "recent_header") {
            Row(
                modifier              = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text  = "Recent searches",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                TextButton(
                    onClick = { confirmClear = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Clear all") }
            }
        }
        items(entries, key = { "recent_${it.query.lowercase()}" }) { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = "Search for ${entry.query}") { onSelect(entry.query) }
                    .padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector        = Icons.Default.History,
                    contentDescription = null,
                    tint               = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Text(
                    text     = entry.query,
                    style    = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 14.dp),
                )
                IconButton(
                    onClick  = { onRemove(entry.query) },
                    modifier = Modifier.semantics { contentDescription = "Remove ${entry.query} from recent searches" },
                ) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title            = { Text("Clear recent searches?") },
            text             = { Text("This removes all saved searches from this device. Your music and listening history are not affected.") },
            confirmButton    = {
                TextButton(
                    onClick = { confirmClear = false; onClearAll() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Clear all") }
            },
            dismissButton    = {
                TextButton(
                    onClick = { confirmClear = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                ) { Text("Cancel") }
            },
        )
    }
}
