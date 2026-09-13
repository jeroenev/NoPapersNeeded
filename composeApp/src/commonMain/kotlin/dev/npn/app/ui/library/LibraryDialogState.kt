@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.library

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.npn.app.ui.common.TextInputDialog
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.SavedView
import dev.npn.core.app.TagInfo
import dev.npn.core.model.ReviewMode
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.library_add
import no_papers_needed.composeapp.generated.resources.library_add_tag_to_documents
import no_papers_needed.composeapp.generated.resources.library_remove
import no_papers_needed.composeapp.generated.resources.library_remove_tag_from_documents
import no_papers_needed.composeapp.generated.resources.library_rename
import no_papers_needed.composeapp.generated.resources.library_rename_saved_view
import no_papers_needed.composeapp.generated.resources.library_save_current_view
import no_papers_needed.composeapp.generated.resources.library_view_name
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

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

    /** Tag names shown by the add picker; loaded lazily when it opens. */
    var allTagNames by mutableStateOf<List<String>>(emptyList())

    /** Tag names shown by the remove picker: only those carried by the current selection. */
    var removableTagNames by mutableStateOf<List<String>>(emptyList())

    fun openFilter() {
        showFilter = true
    }

    fun dismissFilter() {
        showFilter = false
    }

    fun openSaveView() {
        showSaveView = true
    }

    fun dismissSaveView() {
        showSaveView = false
    }

    fun openAddTag() {
        showAddTag = true
    }

    fun dismissAddTag() {
        showAddTag = false
    }

    fun openRemoveTag() {
        showRemoveTag = true
    }

    fun dismissRemoveTag() {
        showRemoveTag = false
    }

    fun openRename(view: SavedView) {
        renameView = view
    }

    fun dismissRename() {
        renameView = null
    }
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
    fields: FieldFilterOptions,
    reviewMode: ReviewMode,
    onApplyFilter: (LibraryFilter) -> Unit,
    onSaveView: (String) -> Unit,
    onRenameView: (SavedView, String) -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    renderFilter: Boolean = true,
) {
    if (state.showFilter && renderFilter) {
        FilterSheet(
            filter = filter,
            tags = tags,
            fields = fields,
            reviewMode = reviewMode,
            onApply = {
                onApplyFilter(it)
                state.dismissFilter()
            },
            onDismiss = state::dismissFilter,
        )
    }

    if (state.showSaveView) {
        TextInputDialog(
            title = stringResource(Res.string.library_save_current_view),
            label = stringResource(Res.string.library_view_name),
            onConfirm = {
                onSaveView(it)
                state.dismissSaveView()
            },
            onDismiss = state::dismissSaveView,
        )
    }

    state.renameView?.let { view ->
        TextInputDialog(
            title = stringResource(Res.string.library_rename_saved_view),
            label = stringResource(Res.string.library_view_name),
            confirmLabel = stringResource(Res.string.library_rename),
            onConfirm = {
                onRenameView(view, it)
                state.dismissRename()
            },
            onDismiss = state::dismissRename,
        )
    }

    if (state.showAddTag) {
        TagPickerDialog(
            title =
                pluralStringResource(
                    Res.plurals.library_add_tag_to_documents,
                    selectedCount,
                    selectedCount,
                ),
            allTagNames = state.allTagNames,
            confirmLabel = stringResource(Res.string.library_add),
            onConfirm = {
                onAddTag(it)
                state.dismissAddTag()
            },
            onDismiss = state::dismissAddTag,
        )
    }

    if (state.showRemoveTag) {
        TagPickerDialog(
            title =
                pluralStringResource(
                    Res.plurals.library_remove_tag_from_documents,
                    selectedCount,
                    selectedCount,
                ),
            allTagNames = state.removableTagNames,
            confirmLabel = stringResource(Res.string.library_remove),
            onConfirm = {
                onRemoveTag(it)
                state.dismissRemoveTag()
            },
            onDismiss = state::dismissRemoveTag,
        )
    }
}
