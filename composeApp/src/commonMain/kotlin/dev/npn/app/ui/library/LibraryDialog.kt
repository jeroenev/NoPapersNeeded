package dev.npn.app.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import dev.npn.app.ui.common.ConfirmDialog
import dev.npn.app.ui.common.TagPickDialog
import dev.npn.app.ui.common.TextInputDialog
import dev.npn.app.ui.common.jsonSaver
import dev.npn.core.app.SavedView
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.library_add
import no_papers_needed.composeapp.generated.resources.library_add_tag_to_documents
import no_papers_needed.composeapp.generated.resources.library_move_to_trash_question
import no_papers_needed.composeapp.generated.resources.library_remove
import no_papers_needed.composeapp.generated.resources.library_remove_tag_from_documents
import no_papers_needed.composeapp.generated.resources.library_rename
import no_papers_needed.composeapp.generated.resources.library_rename_saved_view
import no_papers_needed.composeapp.generated.resources.library_save_current_view
import no_papers_needed.composeapp.generated.resources.library_trash
import no_papers_needed.composeapp.generated.resources.library_trash_documents
import no_papers_needed.composeapp.generated.resources.library_view_name
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The one dialog, sheet or side panel the library screen has open over its list, if any. One value
 * rather than a flag per dialog, so two can never be open at once, and saveable, so whatever is
 * open — the filter editor with its unapplied draft included — comes back after the screen is
 * recreated.
 */
@Serializable
sealed interface LibraryDialog {
    /** Whether it acts on the selection, so has nothing to act on without one. */
    val actsOnSelection: Boolean get() = false

    /** The filter editor: a side panel on wide layouts, a bottom sheet on compact ones. */
    @Serializable
    data object Filter : LibraryDialog

    @Serializable
    data object SaveView : LibraryDialog

    @Serializable
    data class RenameView(
        val view: SavedView,
    ) : LibraryDialog

    /** Add a tag to the selection, picked from the library's whole vocabulary. */
    @Serializable
    data object AddTag : LibraryDialog {
        override val actsOnSelection get() = true
    }

    /** Remove a tag from the selection, picked from the tags the selection carries. */
    @Serializable
    data object RemoveTag : LibraryDialog {
        override val actsOnSelection get() = true
    }

    /** Confirm moving the selection to the trash. */
    @Serializable
    data object ConfirmTrash : LibraryDialog {
        override val actsOnSelection get() = true
    }
}

/** Saves the open dialog as JSON; nothing open saves nothing. */
internal val LibraryDialogSaver: Saver<LibraryDialog?, String> = jsonSaver(LibraryDialog.serializer().nullable)

/** The library screen's open dialog, kept across recreation; see [LibraryDialog]. */
@Composable
internal fun rememberLibraryDialog(): MutableState<LibraryDialog?> =
    rememberSaveable(stateSaver = LibraryDialogSaver) { mutableStateOf(null) }

/**
 * Renders the open [dialog], except [LibraryDialog.Filter]: the filter editor is laid out beside
 * the list on wide layouts, so the screen renders it. Every confirm runs its action and closes
 * the dialog through [onDismiss], once. A dialog that acts on the selection closes when there is
 * none: the selection is the viewmodel's, so one restored after the process died finds it gone,
 * and must not offer to act on "0 documents" (or open later over a selection made since).
 */
@Composable
internal fun LibraryDialogHost(
    dialog: LibraryDialog?,
    selectedCount: Int,
    /** What the open tag picker offers: any of the library's tags to add, the selection's own to remove. */
    tagOptions: List<String>,
    onSaveView: (String) -> Unit,
    onRenameView: (SavedView, String) -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onTrash: () -> Unit,
    onDismiss: () -> Unit,
) {
    val orphaned = dialog?.actsOnSelection == true && selectedCount == 0
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(orphaned) { if (orphaned) currentOnDismiss() }
    val confirmed = { action: () -> Unit ->
        action()
        onDismiss()
    }
    when (val open = dialog.takeUnless { orphaned }) {
        null, LibraryDialog.Filter -> {}

        LibraryDialog.SaveView -> {
            TextInputDialog(
                title = stringResource(Res.string.library_save_current_view),
                label = stringResource(Res.string.library_view_name),
                onConfirm = { name -> confirmed { onSaveView(name) } },
                onDismiss = onDismiss,
            )
        }

        is LibraryDialog.RenameView -> {
            TextInputDialog(
                title = stringResource(Res.string.library_rename_saved_view),
                label = stringResource(Res.string.library_view_name),
                initial = open.view.name,
                confirmLabel = stringResource(Res.string.library_rename),
                confirmEnabled = { it.isNotBlank() && it.trim() != open.view.name },
                onConfirm = { name -> confirmed { onRenameView(open.view, name) } },
                onDismiss = onDismiss,
            )
        }

        LibraryDialog.AddTag -> {
            TagPickDialog(
                title = pluralStringResource(Res.plurals.library_add_tag_to_documents, selectedCount, selectedCount),
                options = tagOptions,
                confirmLabel = stringResource(Res.string.library_add),
                onConfirm = { tag -> confirmed { onAddTag(tag) } },
                onDismiss = onDismiss,
                allowNew = true,
            )
        }

        LibraryDialog.RemoveTag -> {
            TagPickDialog(
                title =
                    pluralStringResource(Res.plurals.library_remove_tag_from_documents, selectedCount, selectedCount),
                options = tagOptions,
                confirmLabel = stringResource(Res.string.library_remove),
                onConfirm = { tag -> confirmed { onRemoveTag(tag) } },
                onDismiss = onDismiss,
            )
        }

        LibraryDialog.ConfirmTrash -> {
            ConfirmDialog(
                title = stringResource(Res.string.library_trash_documents),
                message =
                    pluralStringResource(Res.plurals.library_move_to_trash_question, selectedCount, selectedCount),
                confirmLabel = stringResource(Res.string.library_trash),
                destructive = true,
                onConfirm = { confirmed(onTrash) },
                onDismiss = onDismiss,
            )
        }
    }
}
