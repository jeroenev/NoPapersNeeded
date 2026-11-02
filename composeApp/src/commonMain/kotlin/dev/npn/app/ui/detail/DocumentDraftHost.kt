package dev.npn.app.ui.detail

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.npn.app.ui.common.SnackbarMessages
import dev.npn.app.ui.common.TopBanner
import dev.npn.app.ui.common.WithTopBanner
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.detail_drafts_failed
import no_papers_needed.composeapp.generated.resources.detail_drafts_invalid
import no_papers_needed.composeapp.generated.resources.detail_drafts_unsaved
import no_papers_needed.composeapp.generated.resources.ux_resume_draft
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Makes [store] the drafts every document editor in [content] works on. */
@Composable
internal fun DocumentDraftHost(
    store: DocumentDrafts,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalDocumentDrafts provides store, content = content)
}

/**
 * Puts the "documents with unsaved changes" banner above [content] (see [WithTopBanner]), so a draft
 * left behind on another screen stays visible instead of being silently held. Leaving a document
 * saves its draft, so what is left is a draft that cannot be written: an entry its field refuses, or
 * a write that failed; the banner says which. A draft whose editor is currently on screen — the
 * full-screen detail route or the wide layout's side preview panel — or whose save is running is
 * excluded, so the banner never offers to open a document the user is already looking at.
 *
 * It also shows the document screens' [DocumentDrafts.notices] on [snackbar], the shell's own: a save
 * or a trash that landed as its document closed, whose Undo has to outlive that screen.
 */
@Composable
internal fun WithDraftResumeBanner(
    snackbar: SnackbarHostState,
    onOpen: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    val store = LocalDocumentDrafts.current
    SnackbarMessages(store.notices.messages, snackbar)
    // store.state / store.onScreen are snapshot-backed reads, so this recomposes on either change.
    val pending = store.state.filterValues { it.dirty && !it.saving } - store.onScreen
    WithTopBanner(
        banner =
            if (pending.isEmpty()) {
                null
            } else {
                { DraftResumeBanner(pending.values, onOpen = { onOpen(pending.keys.first()) }) }
            },
        content = content,
    )
}

@Composable
private fun DraftResumeBanner(
    drafts: Collection<DocumentDraft>,
    onOpen: () -> Unit,
) {
    val count = drafts.size
    val reason =
        when {
            drafts.all { !it.writable } -> Res.plurals.detail_drafts_invalid
            drafts.all { it.error != null } -> Res.plurals.detail_drafts_failed
            else -> Res.plurals.detail_drafts_unsaved
        }
    // The attention (tertiary) colour convention: red stays reserved for failures.
    TopBanner(
        message = pluralStringResource(reason, count, count),
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        TextButton(onClick = onOpen) { Text(stringResource(Res.string.ux_resume_draft)) }
    }
}
