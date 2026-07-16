@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.library

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.SavedView
import dev.npn.core.app.TagInfo

/**
 * Holds the transient dialog state for [LibraryScreen] (which sheet/dialog is open and the tag-name
 * snapshot the tag pickers show). Keeping it in one holder stops the screen composable from carrying
 * six loose `mutableStateOf` flags and makes the open/dismiss transitions explicit.
 */
class LibraryDialogState {
    var showFilter by mutableStateOf(false)
        private set
    var showSaveView by mutableStateOf(false)
        private set
    var showAddTag by mutableStateOf(false)
        private set
    var showRemoveTag by mutableStateOf(false)
        private set
    var renameView by mutableStateOf<SavedView?>(null)
        private set

    /** Tag names shown by the add/remove pickers; loaded lazily when a picker opens. */
    var allTagNames by mutableStateOf<List<String>>(emptyList())

    fun openFilter() { showFilter = true }
    fun dismissFilter() { showFilter = false }

    fun openSaveView() { showSaveView = true }
    fun dismissSaveView() { showSaveView = false }

    fun openAddTag() { showAddTag = true }
    fun dismissAddTag() { showAddTag = false }

    fun openRemoveTag() { showRemoveTag = true }
    fun dismissRemoveTag() { showRemoveTag = false }

    fun openRename(view: SavedView) { renameView = view }
    fun dismissRename() { renameView = null }
}

@Composable
fun rememberLibraryDialogState(): LibraryDialogState = remember { LibraryDialogState() }

/**
 * Renders whichever library dialog is currently open, driven entirely by [state]. All mutations flow
 * back through the callbacks; this composable owns no VM reference.
 */
@Composable
fun LibraryDialogHost(
    state: LibraryDialogState,
    filter: LibraryFilter,
    selectedCount: Int,
    tags: List<TagInfo>,
    correspondents: List<String>,
    docTypes: List<String>,
    onApplyFilter: (LibraryFilter) -> Unit,
    onClearFilter: () -> Unit,
    onSaveView: (String) -> Unit,
    onRenameView: (SavedView, String) -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
) {
    if (state.showFilter) {
        FilterSheet(
            filter = filter,
            tags = tags,
            correspondents = correspondents,
            docTypes = docTypes,
            onApply = {
                onApplyFilter(it)
                state.dismissFilter()
            },
            onClearAll = onClearFilter,
            onDismiss = state::dismissFilter,
        )
    }

    if (state.showSaveView) {
        NameDialog(
            title = "Save current view",
            label = "View name",
            onConfirm = {
                onSaveView(it)
                state.dismissSaveView()
            },
            onDismiss = state::dismissSaveView,
        )
    }

    state.renameView?.let { view ->
        NameDialog(
            title = "Rename saved view",
            label = "View name",
            confirmLabel = "Rename",
            onConfirm = {
                onRenameView(view, it)
                state.dismissRename()
            },
            onDismiss = state::dismissRename,
        )
    }

    if (state.showAddTag) {
        TagPickerDialog(
            title = "Add tag to $selectedCount document(s)",
            allTagNames = state.allTagNames,
            confirmLabel = "Add",
            onConfirm = {
                onAddTag(it)
                state.dismissAddTag()
            },
            onDismiss = state::dismissAddTag,
        )
    }

    if (state.showRemoveTag) {
        TagPickerDialog(
            title = "Remove tag from $selectedCount document(s)",
            allTagNames = state.allTagNames,
            confirmLabel = "Remove",
            onConfirm = {
                onRemoveTag(it)
                state.dismissRemoveTag()
            },
            onDismiss = state::dismissRemoveTag,
        )
    }
}
