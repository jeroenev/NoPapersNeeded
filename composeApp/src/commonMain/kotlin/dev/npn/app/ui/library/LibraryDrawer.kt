package dev.npn.app.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.npn.core.app.SavedView

@Composable
fun LibraryDrawerContent(
    reviewBadge: Int,
    savedViews: List<SavedView>,
    onAllDocuments: () -> Unit,
    onReviewQueue: () -> Unit,
    onTrash: () -> Unit,
    onTags: () -> Unit,
    onKanban: () -> Unit,
    onSettings: () -> Unit,
    onSavedView: (SavedView) -> Unit,
    onSaveCurrentView: () -> Unit,
    onRenameSavedView: (SavedView) -> Unit,
    onDeleteSavedView: (SavedView) -> Unit,
    onTogglePinSavedView: (SavedView) -> Unit,
) {
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Text(
            "No papers needed",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )

        NavigationDrawerItem(
            label = { Text("All documents") },
            selected = false,
            icon = { Icon(Icons.Filled.Home, contentDescription = null) },
            onClick = onAllDocuments,
        )
        NavigationDrawerItem(
            label = { Text("Review queue") },
            selected = false,
            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
            badge = { if (reviewBadge > 0) Badge { Text(reviewBadge.toString()) } },
            onClick = onReviewQueue,
        )
        NavigationDrawerItem(
            label = { Text("Trash") },
            selected = false,
            icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            onClick = onTrash,
        )
        NavigationDrawerItem(
            label = { Text("Tags") },
            selected = false,
            icon = { Icon(Icons.Filled.Star, contentDescription = null) },
            onClick = onTags,
        )
        NavigationDrawerItem(
            label = { Text("Kanban") },
            selected = false,
            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
            onClick = onKanban,
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(
            "Saved views",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        // Pinned views first, then the rest.
        savedViews.sortedByDescending { it.pinned }.forEach { view ->
            SavedViewRow(
                view = view,
                onOpen = { onSavedView(view) },
                onRename = { onRenameSavedView(view) },
                onDelete = { onDeleteSavedView(view) },
                onTogglePin = { onTogglePinSavedView(view) },
            )
        }
        NavigationDrawerItem(
            label = { Text("Save current view...") },
            selected = false,
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            onClick = onSaveCurrentView,
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        NavigationDrawerItem(
            label = { Text("Settings") },
            selected = false,
            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            onClick = onSettings,
        )
    }
}

@Composable
private fun SavedViewRow(
    view: SavedView,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    NavigationDrawerItem(
        label = { Text(view.name) },
        selected = false,
        icon = if (view.pinned) {
            { Icon(Icons.Filled.Star, contentDescription = "Pinned") }
        } else null,
        badge = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "View actions")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (view.pinned) "Unpin" else "Pin") },
                        onClick = { menu = false; onTogglePin() },
                    )
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        },
        onClick = onOpen,
    )
}
