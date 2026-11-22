package dev.npn.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import dev.npn.app.platform.sharePdfBytes
import dev.npn.app.platform.supportsSharing
import dev.npn.app.ui.common.ConfirmDialog
import dev.npn.app.ui.common.TextInputDialog
import dev.npn.app.ui.common.TooltipIconButton
import dev.npn.app.ui.common.TopBarAction
import dev.npn.app.ui.common.TopBarActions
import dev.npn.app.ui.common.formatUsd
import dev.npn.app.ui.common.uiText
import dev.npn.core.app.AiOcrOffer
import dev.npn.core.app.DocListItem
import dev.npn.core.fs.NpnPaths
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher
import io.github.vinceglb.filekit.write
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_spend_estimate_cost
import no_papers_needed.composeapp.generated.resources.ai_spend_estimate_tokens
import no_papers_needed.composeapp.generated.resources.ai_spend_estimate_tokens_only
import no_papers_needed.composeapp.generated.resources.detail_action_needs_saved_draft
import no_papers_needed.composeapp.generated.resources.detail_ai_ocr
import no_papers_needed.composeapp.generated.resources.detail_ai_ocr_confirm
import no_papers_needed.composeapp.generated.resources.detail_ai_ocr_large
import no_papers_needed.composeapp.generated.resources.detail_ai_ocr_large_prompt
import no_papers_needed.composeapp.generated.resources.detail_bake_in_place
import no_papers_needed.composeapp.generated.resources.detail_bake_metadata_in_place
import no_papers_needed.composeapp.generated.resources.detail_bake_prompt
import no_papers_needed.composeapp.generated.resources.detail_baking
import no_papers_needed.composeapp.generated.resources.detail_export_baked_copy
import no_papers_needed.composeapp.generated.resources.detail_more_actions
import no_papers_needed.composeapp.generated.resources.detail_new_file_name
import no_papers_needed.composeapp.generated.resources.detail_rename
import no_papers_needed.composeapp.generated.resources.detail_rename_file
import no_papers_needed.composeapp.generated.resources.detail_rerun_ocr
import no_papers_needed.composeapp.generated.resources.detail_rerunning_ocr
import no_papers_needed.composeapp.generated.resources.detail_share_baked_copy
import no_papers_needed.composeapp.generated.resources.detail_split_document
import no_papers_needed.composeapp.generated.resources.detail_splitting
import no_papers_needed.composeapp.generated.resources.detail_trash
import no_papers_needed.composeapp.generated.resources.detail_trash_document
import no_papers_needed.composeapp.generated.resources.detail_trash_prompt
import org.jetbrains.compose.resources.stringResource

/** Which document-action dialog is open, if any. */
private enum class DocActionDialog { RENAME, TRASH, BAKE, SPLIT, AI_OCR }

/**
 * What the document actions do, all at once rather than through the document's draft.
 * [DocDetailViewModel] implements it; results are its messages.
 */
interface DocumentActions {
    fun rename(newFileName: String)

    fun reOcr()

    /** Re-read the text with the AI (vision) engine; a long document is confirmed first. */
    fun ocrWithAi()

    /** Split into one document per [segments] entry; the original goes to the trash. */
    fun split(segments: List<IntRange>)

    /** Rewrite the library file with the document's metadata. */
    fun bakeInPlace()

    /** Write a copy carrying the document's metadata through [write] (a file the user picked). */
    fun exportBakedCopy(write: suspend (ByteArray) -> Unit)

    /** Hand a copy carrying the document's metadata to the platform's share sheet through [share]. */
    fun shareBakedCopy(share: suspend (ByteArray) -> Unit)

    /** Move the document to the trash. */
    fun trash()
}

/**
 * [actions] acting on the document as its draft shows it: those that read its metadata (a shared,
 * exported or baked copy, a split, whose parts copy it) save the draft first and run once it landed
 * ([DocumentDrafts.whenSaved]). While a typed value does not fit its field nothing can be saved, so
 * they do not run, and [DocumentDrafts.notices] says why. [persisted] and [title] are the document's,
 * as the library holds it and as its draft shows it.
 */
internal class SavedFirstActions(
    private val actions: DocumentActions,
    private val drafts: DocumentDrafts,
    private val docId: String,
    private val persisted: State<Map<String, String>>,
    private val title: State<String>,
) : DocumentActions by actions {
    private fun afterSave(action: () -> Unit) {
        if (!drafts.whenSaved(docId, persisted.value, title.value, action)) {
            drafts.notices.message(uiText(Res.string.detail_action_needs_saved_draft))
        }
    }

    override fun split(segments: List<IntRange>) = afterSave { actions.split(segments) }

    override fun bakeInPlace() = afterSave(actions::bakeInPlace)

    override fun exportBakedCopy(write: suspend (ByteArray) -> Unit) = afterSave { actions.exportBakedCopy(write) }

    override fun shareBakedCopy(share: suspend (ByteArray) -> Unit) = afterSave { actions.shareBakedCopy(share) }
}

/**
 * Shared document actions: a share/export button for a baked copy plus an overflow menu with
 * rename, re-run OCR, split, bake-in-place and trash. Used by the detail screen's top bar and by
 * the library's side-panel preview header, so both surfaces offer the same operations. Owns the
 * confirmation dialogs; [state] says which actions are busy or offered.
 *
 * A document that left the library (trashed, or split into new documents) says so in
 * [DetailUiState.left], which the host follows to close itself. A [compact] bar keeps every action
 * in the overflow menu.
 */
@Composable
fun DocActions(
    item: DocListItem,
    state: DetailUiState,
    actions: DocumentActions,
    compact: Boolean,
) {
    var dialog by rememberSaveable { mutableStateOf<DocActionDialog?>(null) }

    ShareOrExportButton(
        exportName = exportName(item),
        onExport = { file -> actions.exportBakedCopy { bytes -> file.write(bytes) } },
        onShare = { fileName -> actions.shareBakedCopy { bytes -> sharePdfBytes(fileName, bytes) } },
    )
    DocActionsMenu(
        compact = compact,
        state = state,
        onRename = { dialog = DocActionDialog.RENAME },
        onReOcr = actions::reOcr,
        // A long document is offered like any other, but its cost is worth a sentence first: the
        // model is billed per page, and the page count is the one thing the user cannot see from
        // a menu item.
        onAiOcr = {
            if (state.aiOcr is AiOcrOffer.Large) dialog = DocActionDialog.AI_OCR else actions.ocrWithAi()
        },
        onSplit = { dialog = DocActionDialog.SPLIT },
        onBakeInPlace = { dialog = DocActionDialog.BAKE },
        onTrash = { dialog = DocActionDialog.TRASH },
    )
    DocActionDialogs(
        dialog = dialog,
        fileName = NpnPaths.stripPdfExtension(NpnPaths.fileName(item.doc.relPath)),
        pageCount = state.pageCount.count,
        aiOcr = state.aiOcr,
        actions = actions,
        onDismiss = { dialog = null },
    )
}

/** Share on platforms with a share sheet (Android), save-to-file elsewhere (desktop). */
@Composable
private fun ShareOrExportButton(
    exportName: String,
    onExport: (PlatformFile) -> Unit,
    onShare: (fileName: String) -> Unit,
) {
    val saver =
        rememberFileSaverLauncher(
            dialogSettings = FileKitDialogSettings.createDefault(),
        ) { file: PlatformFile? ->
            if (file != null) onExport(file)
        }

    if (supportsSharing()) {
        TooltipIconButton(
            icon = Icons.Filled.Share,
            label = stringResource(Res.string.detail_share_baked_copy),
            onClick = { onShare("$exportName.pdf") },
        )
    } else {
        TooltipIconButton(
            icon = Icons.Filled.Save,
            label = stringResource(Res.string.detail_export_baked_copy),
            onClick = { saver.launch(suggestedName = exportName, defaultExtension = "pdf") },
        )
    }
}

@Composable
private fun DocActionsMenu(
    compact: Boolean,
    state: DetailUiState,
    onRename: () -> Unit,
    onReOcr: () -> Unit,
    onAiOcr: () -> Unit,
    onSplit: () -> Unit,
    onBakeInPlace: () -> Unit,
    onTrash: () -> Unit,
) {
    val reindexing = state.reindexing
    val aiOcr = state.aiOcr
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
            label =
                stringResource(if (state.splitting) Res.string.detail_splitting else Res.string.detail_split_document),
            onClick = onSplit,
            enabled = state.pageCount.count > 1 && !state.splitting,
        )
    val bake =
        TopBarAction(
            icon = Icons.Filled.PictureAsPdf,
            label =
                stringResource(
                    if (state.baking) Res.string.detail_baking else Res.string.detail_bake_metadata_in_place,
                ),
            onClick = onBakeInPlace,
            enabled = !state.baking,
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
                    if (aiOcr is AiOcrOffer.Large) {
                        stringResource(Res.string.detail_ai_ocr_large, aiOcr.pages)
                    } else {
                        stringResource(Res.string.detail_ai_ocr)
                    },
                onClick = onAiOcr,
                enabled = !reindexing,
            )
        }
    val trash = TopBarAction(Icons.Filled.Delete, stringResource(Res.string.detail_trash), onTrash)
    // A wide bar promotes the direct document edits to icons; AI OCR and bake-in-place stay one tap
    // away in the overflow. A compact bar — and the narrow side-panel header — keep everything in
    // the overflow so the few icons that fit there are not crowded out.
    TopBarActions(
        inline = if (compact) emptyList() else listOf(rename, reOcr, split, trash),
        overflow =
            if (compact) {
                listOfNotNull(rename, reOcr, aiOcrAction, split, bake, trash)
            } else {
                listOfNotNull(aiOcrAction, bake)
            },
        overflowLabel = stringResource(Res.string.detail_more_actions),
    )
}

@Composable
private fun DocActionDialogs(
    dialog: DocActionDialog?,
    /** The document's file name without `.pdf`, which a rename adds back. */
    fileName: String,
    pageCount: Int,
    aiOcr: AiOcrOffer,
    actions: DocumentActions,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        DocActionDialog.RENAME -> {
            TextInputDialog(
                title = stringResource(Res.string.detail_rename_file),
                label = stringResource(Res.string.detail_new_file_name),
                initial = fileName,
                confirmLabel = stringResource(Res.string.detail_rename),
                confirmEnabled = { it.isNotBlank() && it.trim() != fileName },
                onConfirm = {
                    actions.rename(it)
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
                    actions.trash()
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
                    actions.bakeInPlace()
                },
                onDismiss = onDismiss,
            )
        }

        DocActionDialog.AI_OCR -> {
            // Only reachable for a long document; a plain one runs straight off the menu item.
            val large = aiOcr as? AiOcrOffer.Large
            val pages = large?.pages ?: pageCount
            val estimate = large?.estimate
            ConfirmDialog(
                title = stringResource(Res.string.detail_ai_ocr),
                confirmLabel = stringResource(Res.string.detail_ai_ocr_confirm),
                onConfirm = {
                    onDismiss()
                    actions.ocrWithAi()
                },
                onDismiss = onDismiss,
            ) {
                androidx.compose.foundation.layout.Column(
                    verticalArrangement =
                        androidx.compose.foundation.layout.Arrangement
                            .spacedBy(4.dp),
                ) {
                    androidx.compose.material3.Text(stringResource(Res.string.detail_ai_ocr_large_prompt, pages))
                    if (estimate != null) {
                        val cost = estimate.estimatedCostUsd
                        if (estimate.priceKnown && cost != null) {
                            androidx.compose.material3.Text(
                                stringResource(Res.string.ai_spend_estimate_tokens, estimate.inputTokens),
                            )
                            androidx.compose.material3.Text(
                                stringResource(Res.string.ai_spend_estimate_cost, formatUsd(cost)),
                            )
                        } else {
                            androidx.compose.material3.Text(
                                stringResource(Res.string.ai_spend_estimate_tokens_only, estimate.inputTokens),
                            )
                        }
                    }
                }
            }
        }

        DocActionDialog.SPLIT -> {
            SplitDialog(
                pageCount = pageCount,
                onConfirm = { segments ->
                    onDismiss()
                    actions.split(segments)
                },
                onDismiss = onDismiss,
            )
        }

        null -> {}
    }
}

/**
 * The name, without its extension, that a shared or saved copy of [item] gets: its title as one
 * file name, never a path. A separator becomes `_`, leading and trailing dots go (no `..`, no
 * hidden file), and a dot inside the title stays; a title with nothing left gives "document".
 */
internal fun exportName(item: DocListItem): String =
    NpnPaths.sanitizeSegment(NpnPaths.stripPdfExtension(item.doc.displayTitle)).ifBlank { "document" }
