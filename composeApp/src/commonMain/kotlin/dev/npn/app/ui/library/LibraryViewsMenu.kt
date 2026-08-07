package dev.npn.app.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.npn.core.app.SavedView
import dev.npn.core.model.ReviewMode
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_delete
import no_papers_needed.composeapp.generated.resources.library_ai_assistant
import no_papers_needed.composeapp.generated.resources.library_ai_edit
import no_papers_needed.composeapp.generated.resources.library_all_documents
import no_papers_needed.composeapp.generated.resources.library_more_actions
import no_papers_needed.composeapp.generated.resources.library_pin
import no_papers_needed.composeapp.generated.resources.library_pinned
import no_papers_needed.composeapp.generated.resources.library_rename
import no_papers_needed.composeapp.generated.resources.library_review_queue
import no_papers_needed.composeapp.generated.resources.library_save_current_view_ellipsis
import no_papers_needed.composeapp.generated.resources.library_saved_views
import no_papers_needed.composeapp.generated.resources.library_unpin
import no_papers_needed.composeapp.generated.resources.library_untagged_documents
import no_papers_needed.composeapp.generated.resources.library_view_actions
import no_papers_needed.composeapp.generated.resources.library_views
import org.jetbrains.compose.resources.stringResource

/**
 * Top-bar entry point for view presets, replacing the old navigation drawer: all documents,
 * the review queue (with a badge while it needs clearing), and saved views with their manage
 * actions. Lives in the library top bar so it is equally reachable with rail and bottom-bar
 * navigation.
 */
@Composable
internal fun LibraryViewsMenuButton(
    reviewMode: ReviewMode,
    reviewBadge: Int,
    savedViews: List<SavedView>,
    onAllDocuments: () -> Unit,
    onReviewQueue: () -> Unit,
    onSaveCurrentView: () -> Unit,
    onSavedView: (SavedView) -> Unit,
    onRenameSavedView: (SavedView) -> Unit,
    onDeleteSavedView: (SavedView) -> Unit,
    onTogglePinSavedView: (SavedView) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val inbox = reviewMode == ReviewMode.INBOX_WORKFLOW
    Box {
        IconButton(onClick = { open = true }) {
            // A backlog of untagged documents is normal, so the attention badge only signals an
            // inbox-workflow review queue that needs clearing (same rule as the old drawer).
            BadgedBox(badge = { if (inbox && reviewBadge > 0) Badge { Text(reviewBadge.toString()) } }) {
                Icon(Icons.Outlined.Bookmarks, contentDescription = stringResource(Res.string.library_views))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun dismissThen(action: () -> Unit) {
                open = false
                action()
            }
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.library_all_documents)) },
                leadingIcon = { Icon(Icons.Filled.Home, contentDescription = null) },
                onClick = { dismissThen(onAllDocuments) },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (inbox) Res.string.library_review_queue else Res.string.library_untagged_documents,
                        ),
                    )
                },
                leadingIcon = { Icon(Icons.Filled.Inbox, contentDescription = null) },
                trailingIcon = { if (inbox && reviewBadge > 0) Badge { Text(reviewBadge.toString()) } },
                onClick = { dismissThen(onReviewQueue) },
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(
                stringResource(Res.string.library_saved_views),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            // Pinned views first, then the rest.
            savedViews.sortedByDescending { it.pinned }.forEach { view ->
                SavedViewMenuItem(
                    view = view,
                    onOpen = { dismissThen { onSavedView(view) } },
                    onRename = { dismissThen { onRenameSavedView(view) } },
                    onDelete = { dismissThen { onDeleteSavedView(view) } },
                    onTogglePin = { onTogglePinSavedView(view) },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.library_save_current_view_ellipsis)) },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = { dismissThen(onSaveCurrentView) },
            )
        }
    }
}

@Composable
private fun SavedViewMenuItem(
    view: SavedView,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
) {
    var actions by remember { mutableStateOf(false) }
    DropdownMenuItem(
        text = { Text(view.name) },
        leadingIcon =
            if (view.pinned) {
                { Icon(Icons.Filled.PushPin, contentDescription = stringResource(Res.string.library_pinned)) }
            } else {
                null
            },
        trailingIcon = {
            Box {
                IconButton(onClick = { actions = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(Res.string.library_view_actions))
                }
                DropdownMenu(expanded = actions, onDismissRequest = { actions = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (view.pinned) Res.string.library_unpin else Res.string.library_pin,
                                ),
                            )
                        },
                        onClick = {
                            actions = false
                            onTogglePin()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.library_rename)) },
                        onClick = {
                            actions = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.common_delete)) },
                        onClick = {
                            actions = false
                            onDelete()
                        },
                    )
                }
            }
        },
        onClick = onOpen,
    )
}

/** Overflow menu hosting the AI entry points on compact layouts (the rail carries them when wide). */
@Composable
internal fun LibraryAiOverflowMenu(
    onAiChat: () -> Unit,
    onAiEdit: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(Res.string.library_more_actions))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.library_ai_assistant)) },
                leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                onClick = {
                    open = false
                    onAiChat()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.library_ai_edit)) },
                leadingIcon = { Icon(Icons.Outlined.AutoFixHigh, contentDescription = null) },
                onClick = {
                    open = false
                    onAiEdit()
                },
            )
        }
    }
}
