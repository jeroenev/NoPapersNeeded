package dev.npn.app.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.npn.app.ui.common.ConfirmDialog
import dev.npn.app.ui.common.TextInputDialog
import dev.npn.app.ui.common.sharePdfBytes
import dev.npn.app.ui.common.supportsSharing
import dev.npn.core.app.DocListItem
import dev.npn.core.app.SplitOutcome
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher
import io.github.vinceglb.filekit.write
import kotlinx.coroutines.launch
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_unknown_error
import no_papers_needed.composeapp.generated.resources.detail_bake_failed
import no_papers_needed.composeapp.generated.resources.detail_bake_in_place
import no_papers_needed.composeapp.generated.resources.detail_bake_metadata_in_place
import no_papers_needed.composeapp.generated.resources.detail_bake_prompt
import no_papers_needed.composeapp.generated.resources.detail_baked_in_place
import no_papers_needed.composeapp.generated.resources.detail_baking
import no_papers_needed.composeapp.generated.resources.detail_could_not_bake_copy
import no_papers_needed.composeapp.generated.resources.detail_export_baked_copy
import no_papers_needed.composeapp.generated.resources.detail_export_failed
import no_papers_needed.composeapp.generated.resources.detail_exported_baked_copy
import no_papers_needed.composeapp.generated.resources.detail_more_actions
import no_papers_needed.composeapp.generated.resources.detail_new_file_name
import no_papers_needed.composeapp.generated.resources.detail_rename
import no_papers_needed.composeapp.generated.resources.detail_rename_file
import no_papers_needed.composeapp.generated.resources.detail_rerun_ocr
import no_papers_needed.composeapp.generated.resources.detail_rerunning_ocr
import no_papers_needed.composeapp.generated.resources.detail_share_baked_copy
import no_papers_needed.composeapp.generated.resources.detail_share_failed
import no_papers_needed.composeapp.generated.resources.detail_shared_baked_copy
import no_papers_needed.composeapp.generated.resources.detail_split_document
import no_papers_needed.composeapp.generated.resources.detail_split_failed
import no_papers_needed.composeapp.generated.resources.detail_splitting
import no_papers_needed.composeapp.generated.resources.detail_trash
import no_papers_needed.composeapp.generated.resources.detail_trash_document
import no_papers_needed.composeapp.generated.resources.detail_trash_prompt
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** Which document-action dialog is open, if any. */
private enum class DocActionDialog { RENAME, TRASH, BAKE, SPLIT }

/**
 * Shared document actions: a share/export button for a baked copy plus an overflow menu with
 * rename, re-run OCR, split, bake-in-place and trash. Used by the detail screen's top bar and by
 * the library's side-panel preview header, so both surfaces offer the same operations. Owns the
 * confirmation dialogs; results surface on [snackbar].
 *
 * [onGone] runs after the document left the library (trashed, or split into new documents) so the
 * host can close itself.
 */
@Composable
fun DocActions(
    item: DocListItem,
    vm: DocDetailViewModel,
    snackbar: SnackbarHostState,
    onGone: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<DocActionDialog?>(null) }

    ShareOrExportButton(item = item, vm = vm, snackbar = snackbar)
    DocActionsMenu(
        reindexing = ui.reindexing,
        splitting = ui.splitting,
        baking = ui.baking,
        canSplit = ui.pageCount > 1,
        onRename = { dialog = DocActionDialog.RENAME },
        onReOcr = vm::reOcr,
        onSplit = { dialog = DocActionDialog.SPLIT },
        onBakeInPlace = { dialog = DocActionDialog.BAKE },
        onTrash = { dialog = DocActionDialog.TRASH },
    )
    DocActionDialogs(
        dialog = dialog,
        pageCount = ui.pageCount,
        vm = vm,
        snackbar = snackbar,
        onGone = onGone,
        onDismiss = { dialog = null },
    )
}

/** Share on platforms with a share sheet (Android), save-to-file elsewhere (desktop). */
@Composable
private fun ShareOrExportButton(
    item: DocListItem,
    vm: DocDetailViewModel,
    snackbar: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    val exportedBakedCopy = stringResource(Res.string.detail_exported_baked_copy)
    val sharedBakedCopy = stringResource(Res.string.detail_shared_baked_copy)
    val couldNotBakeCopy = stringResource(Res.string.detail_could_not_bake_copy)
    val unknownError = stringResource(Res.string.common_unknown_error)

    val saver =
        rememberFileSaverLauncher(
            dialogSettings = FileKitDialogSettings.createDefault(),
        ) { file: PlatformFile? ->
            if (file != null) {
                scope.launch {
                    val bytes = vm.bakedCopyBytes()
                    if (bytes != null) {
                        runCatching { file.write(bytes) }
                            .onSuccess { snackbar.showSnackbar(exportedBakedCopy) }
                            .onFailure {
                                snackbar.showSnackbar(
                                    getString(Res.string.detail_export_failed, it.message ?: unknownError),
                                )
                            }
                    } else {
                        snackbar.showSnackbar(couldNotBakeCopy)
                    }
                }
            }
        }

    if (supportsSharing()) {
        IconButton(onClick = {
            scope.launch {
                val bytes = vm.bakedCopyBytes()
                if (bytes != null) {
                    runCatching { sharePdfBytes(exportName(item) + ".pdf", bytes) }
                        .onSuccess { snackbar.showSnackbar(sharedBakedCopy) }
                        .onFailure {
                            snackbar.showSnackbar(
                                getString(Res.string.detail_share_failed, it.message ?: unknownError),
                            )
                        }
                } else {
                    snackbar.showSnackbar(couldNotBakeCopy)
                }
            }
        }) {
            Icon(
                Icons.Filled.Share,
                contentDescription = stringResource(Res.string.detail_share_baked_copy),
            )
        }
    } else {
        IconButton(onClick = { saver.launch(suggestedName = exportName(item), defaultExtension = "pdf") }) {
            Icon(
                Icons.Filled.Save,
                contentDescription = stringResource(Res.string.detail_export_baked_copy),
            )
        }
    }
}

@Composable
private fun DocActionsMenu(
    reindexing: Boolean,
    splitting: Boolean,
    baking: Boolean,
    canSplit: Boolean,
    onRename: () -> Unit,
    onReOcr: () -> Unit,
    onSplit: () -> Unit,
    onBakeInPlace: () -> Unit,
    onTrash: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(Res.string.detail_more_actions),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.detail_rename_file)) },
                onClick = {
                    menu = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (reindexing) Res.string.detail_rerunning_ocr else Res.string.detail_rerun_ocr,
                        ),
                    )
                },
                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                enabled = !reindexing,
                onClick = {
                    menu = false
                    onReOcr()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (splitting) Res.string.detail_splitting else Res.string.detail_split_document,
                        ),
                    )
                },
                leadingIcon = { Icon(Icons.Filled.ContentCut, contentDescription = null) },
                enabled = canSplit && !splitting,
                onClick = {
                    menu = false
                    onSplit()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (baking) Res.string.detail_baking else Res.string.detail_bake_metadata_in_place,
                        ),
                    )
                },
                enabled = !baking,
                onClick = {
                    menu = false
                    onBakeInPlace()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.detail_trash)) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = {
                    menu = false
                    onTrash()
                },
            )
        }
    }
}

@Composable
private fun DocActionDialogs(
    dialog: DocActionDialog?,
    pageCount: Int,
    vm: DocDetailViewModel,
    snackbar: SnackbarHostState,
    onGone: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        DocActionDialog.RENAME -> {
            TextInputDialog(
                title = stringResource(Res.string.detail_rename_file),
                label = stringResource(Res.string.detail_new_file_name),
                confirmLabel = stringResource(Res.string.detail_rename),
                onConfirm = {
                    vm.rename(it)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }

        DocActionDialog.TRASH -> {
            ConfirmDialog(
                title = stringResource(Res.string.detail_trash_document),
                message = stringResource(Res.string.detail_trash_prompt),
                confirmLabel = stringResource(Res.string.detail_trash),
                onConfirm = {
                    onDismiss()
                    vm.trash(onGone)
                },
                onDismiss = onDismiss,
            )
        }

        DocActionDialog.BAKE -> {
            BakeDialog(vm = vm, snackbar = snackbar, onDismiss = onDismiss)
        }

        DocActionDialog.SPLIT -> {
            SplitActionDialog(
                pageCount = pageCount,
                vm = vm,
                snackbar = snackbar,
                onGone = onGone,
                onDismiss = onDismiss,
            )
        }

        null -> {
            Unit
        }
    }
}

@Composable
private fun BakeDialog(
    vm: DocDetailViewModel,
    snackbar: SnackbarHostState,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    ConfirmDialog(
        title = stringResource(Res.string.detail_bake_metadata_in_place),
        message = stringResource(Res.string.detail_bake_prompt),
        confirmLabel = stringResource(Res.string.detail_bake_in_place),
        onConfirm = {
            onDismiss()
            vm.bakeInPlace { ok ->
                scope.launch {
                    snackbar.showSnackbar(
                        if (ok) {
                            getString(Res.string.detail_baked_in_place)
                        } else {
                            getString(Res.string.detail_bake_failed)
                        },
                    )
                }
            }
        },
        onDismiss = onDismiss,
    )
}

@Composable
private fun SplitActionDialog(
    pageCount: Int,
    vm: DocDetailViewModel,
    snackbar: SnackbarHostState,
    onGone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val unknownError = stringResource(Res.string.common_unknown_error)
    SplitDialog(
        pageCount = pageCount,
        onConfirm = { segments ->
            onDismiss()
            vm.split(segments) { outcome ->
                when (outcome) {
                    // The original is trashed on success; the host should leave/close.
                    is SplitOutcome.Done -> {
                        onGone()
                    }

                    is SplitOutcome.Failed -> {
                        scope.launch {
                            snackbar.showSnackbar(
                                getString(
                                    Res.string.detail_split_failed,
                                    outcome.message.ifBlank { unknownError },
                                ),
                            )
                        }
                    }
                }
            }
        },
        onDismiss = onDismiss,
    )
}

internal fun exportName(item: DocListItem): String {
    val base =
        item.doc.displayTitle
            .substringBeforeLast('.')
            .ifBlank { "document" }
    return base
}
