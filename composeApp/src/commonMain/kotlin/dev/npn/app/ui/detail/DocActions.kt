package dev.npn.app.ui.detail

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.npn.app.ui.common.ConfirmDialog
import dev.npn.app.ui.common.TextInputDialog
import dev.npn.app.ui.common.TooltipIconButton
import dev.npn.app.ui.common.TopBarAction
import dev.npn.app.ui.common.TopBarActions
import dev.npn.app.ui.common.sharePdfBytes
import dev.npn.app.ui.common.supportsSharing
import dev.npn.core.app.AiOcrOffer
import dev.npn.core.app.DocListItem
import dev.npn.core.util.runCatchingCancellable
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher
import io.github.vinceglb.filekit.write
import kotlinx.coroutines.launch
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_unknown_error
import no_papers_needed.composeapp.generated.resources.detail_ai_ocr
import no_papers_needed.composeapp.generated.resources.detail_ai_ocr_confirm
import no_papers_needed.composeapp.generated.resources.detail_ai_ocr_large
import no_papers_needed.composeapp.generated.resources.detail_ai_ocr_large_prompt
import no_papers_needed.composeapp.generated.resources.detail_bake_in_place
import no_papers_needed.composeapp.generated.resources.detail_bake_metadata_in_place
import no_papers_needed.composeapp.generated.resources.detail_bake_prompt
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
import no_papers_needed.composeapp.generated.resources.detail_splitting
import no_papers_needed.composeapp.generated.resources.detail_trash
import no_papers_needed.composeapp.generated.resources.detail_trash_document
import no_papers_needed.composeapp.generated.resources.detail_trash_prompt
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** Which document-action dialog is open, if any. */
private enum class DocActionDialog { RENAME, TRASH, BAKE, SPLIT, AI_OCR }

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
    compact: Boolean = true,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<DocActionDialog?>(null) }

    ShareOrExportButton(item = item, vm = vm, snackbar = snackbar)
    DocActionsMenu(
        compact = compact,
        reindexing = ui.reindexing,
        splitting = ui.splitting,
        baking = ui.baking,
        canSplit = ui.pageCount.count > 1,
        aiOcr = ui.aiOcr,
        onRename = { dialog = DocActionDialog.RENAME },
        onReOcr = vm::reOcr,
        // A long document is offered like any other, but its cost is worth a sentence first: the
        // model is billed per page, and the page count is the one thing the user cannot see from
        // a menu item.
        onAiOcr = {
            if (ui.aiOcr is AiOcrOffer.Large) dialog = DocActionDialog.AI_OCR else vm.ocrWithAi()
        },
        onSplit = { dialog = DocActionDialog.SPLIT },
        onBakeInPlace = { dialog = DocActionDialog.BAKE },
        onTrash = { dialog = DocActionDialog.TRASH },
    )
    DocActionDialogs(
        dialog = dialog,
        pageCount = ui.pageCount.count,
        aiOcr = ui.aiOcr,
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
                        runCatchingCancellable { file.write(bytes) }
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
        TooltipIconButton(
            icon = Icons.Filled.Share,
            label = stringResource(Res.string.detail_share_baked_copy),
            onClick = {
                scope.launch {
                    val bytes = vm.bakedCopyBytes()
                    if (bytes != null) {
                        runCatchingCancellable { sharePdfBytes(exportName(item) + ".pdf", bytes) }
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
            },
        )
    } else {
        TooltipIconButton(
            icon = Icons.Filled.Save,
            label = stringResource(Res.string.detail_export_baked_copy),
            onClick = { saver.launch(suggestedName = exportName(item), defaultExtension = "pdf") },
        )
    }
}

@Composable
private fun DocActionsMenu(
    compact: Boolean,
    reindexing: Boolean,
    splitting: Boolean,
    baking: Boolean,
    canSplit: Boolean,
    aiOcr: AiOcrOffer,
    onRename: () -> Unit,
    onReOcr: () -> Unit,
    onAiOcr: () -> Unit,
    onSplit: () -> Unit,
    onBakeInPlace: () -> Unit,
    onTrash: () -> Unit,
) {
    val rename =
        TopBarAction(Icons.Filled.Edit, stringResource(Res.string.detail_rename_file), onRename)
    val reOcr =
        TopBarAction(
            icon = Icons.Filled.Refresh,
            label = stringResource(if (reindexing) Res.string.detail_rerunning_ocr else Res.string.detail_rerun_ocr),
            onClick = onReOcr,
            enabled = !reindexing,
        )
    val split =
        TopBarAction(
            icon = Icons.Filled.ContentCut,
            label = stringResource(if (splitting) Res.string.detail_splitting else Res.string.detail_split_document),
            onClick = onSplit,
            enabled = canSplit && !splitting,
        )
    val bake =
        TopBarAction(
            icon = Icons.Filled.PictureAsPdf,
            label = stringResource(if (baking) Res.string.detail_baking else Res.string.detail_bake_metadata_in_place),
            onClick = onBakeInPlace,
            enabled = !baking,
        )
    // Hidden only without an endpoint: there the action can do nothing but explain itself, and this
    // menu is not where AI gets introduced. Length never hides or disables it — a long document is
    // the case the vision model is most wanted for — so the label just says so, and the
    // confirmation carries the cost.
    val aiOcrAction =
        if (aiOcr == AiOcrOffer.Unavailable) {
            null
        } else {
            TopBarAction(
                icon = Icons.Filled.AutoAwesome,
                label =
                    when (aiOcr) {
                        is AiOcrOffer.Large -> stringResource(Res.string.detail_ai_ocr_large, aiOcr.pages)
                        else -> stringResource(Res.string.detail_ai_ocr)
                    },
                onClick = onAiOcr,
                enabled = !reindexing,
            )
        }
    val trash = TopBarAction(Icons.Filled.Delete, stringResource(Res.string.detail_trash), onTrash)
    // A wide bar promotes the direct document edits to icons; AI OCR and bake-in-place stay one tap
    // away in the overflow. A compact bar — and the narrow side-panel header — keep everything in
    // the overflow so the few icons that fit there are not crowded out.
    val inline =
        if (compact) {
            emptyList()
        } else {
            buildList {
                add(rename)
                add(reOcr)
                add(split)
                add(trash)
            }
        }
    val overflow =
        buildList {
            if (compact) add(rename)
            if (compact) add(reOcr)
            aiOcrAction?.let { add(it) }
            if (compact) add(split)
            add(bake)
            if (compact) add(trash)
        }
    TopBarActions(
        inline = inline,
        overflow = overflow,
        overflowLabel = stringResource(Res.string.detail_more_actions),
    )
}

@Composable
private fun DocActionDialogs(
    dialog: DocActionDialog?,
    pageCount: Int,
    aiOcr: AiOcrOffer,
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
                destructive = true,
                onConfirm = {
                    onDismiss()
                    vm.trash(onGone)
                },
                onDismiss = onDismiss,
            )
        }

        DocActionDialog.BAKE -> {
            ConfirmDialog(
                title = stringResource(Res.string.detail_bake_metadata_in_place),
                message = stringResource(Res.string.detail_bake_prompt),
                confirmLabel = stringResource(Res.string.detail_bake_in_place),
                onConfirm = {
                    onDismiss()
                    vm.bakeInPlace()
                },
                onDismiss = onDismiss,
            )
        }

        DocActionDialog.AI_OCR -> {
            // Only reachable for a long document; a plain one runs straight off the menu item.
            val pages = (aiOcr as? AiOcrOffer.Large)?.pages ?: pageCount
            ConfirmDialog(
                title = stringResource(Res.string.detail_ai_ocr),
                message = stringResource(Res.string.detail_ai_ocr_large_prompt, pages),
                confirmLabel = stringResource(Res.string.detail_ai_ocr_confirm),
                onConfirm = {
                    onDismiss()
                    vm.ocrWithAi()
                },
                onDismiss = onDismiss,
            )
        }

        DocActionDialog.SPLIT -> {
            SplitDialog(
                pageCount = pageCount,
                onConfirm = { segments ->
                    onDismiss()
                    // The original is trashed on success; the host should leave/close.
                    vm.split(segments, onGone)
                },
                onDismiss = onDismiss,
            )
        }

        null -> {}
    }
}

internal fun exportName(item: DocListItem): String {
    val base =
        item.doc.displayTitle
            .substringBeforeLast('.')
            .ifBlank { "document" }
    return base
}
