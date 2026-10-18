package dev.npn.app.ui.detail

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import dev.npn.app.ui.common.TopBanner
import dev.npn.app.ui.common.WithTopBanner
import dev.npn.core.app.LibrarySession
import kotlinx.coroutines.CoroutineScope
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ux_drafts_count
import no_papers_needed.composeapp.generated.resources.ux_resume_draft
import org.jetbrains.compose.resources.stringResource

/**
 * The open library's drafts, kept for as long as [session] is open, and through Android's process
 * death in their saved form. Remembered above the app's [dev.npn.app.ui.common.LocaleProvider]
 * (`LocalizedApp`): a change of language rebuilds everything below it, and must not lose an edit.
 */
@Composable
internal fun rememberDocumentDrafts(
    session: LibrarySession,
    scope: CoroutineScope,
): DocumentDrafts =
    rememberSaveable(
        session,
        saver =
            Saver(
                save = { it.serialize() },
                restore = { DocumentDrafts(session.meta, scope, it) },
            ),
    ) { DocumentDrafts(session.meta, scope) }

/** Makes [store] the drafts every document editor in [content] works on. */
@Composable
internal fun DocumentDraftHost(
    store: DocumentDrafts,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalDocumentDrafts provides store, content = content)
}

/**
 * Puts the "you have unsaved documents" banner above [content] (see [WithTopBanner]), so a draft
 * left behind on another screen stays visible instead of being silently held. A draft whose editor
 * is currently on screen — the full-screen detail route or the wide layout's side preview panel —
 * is excluded, so the banner never offers to open a document the user is already looking at,
 * regardless of the route.
 */
@Composable
internal fun WithDraftResumeBanner(
    onOpen: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    val store = LocalDocumentDrafts.current
    // store.state / store.onScreen are snapshot-backed reads, so this recomposes on either change.
    val pending = store.state.filterValues { it.dirty }.keys - store.onScreen
    WithTopBanner(
        banner =
            if (pending.isEmpty()) {
                null
            } else {
                { DraftResumeBanner(count = pending.size, onOpen = { onOpen(pending.first()) }) }
            },
        content = content,
    )
}

@Composable
private fun DraftResumeBanner(
    count: Int,
    onOpen: () -> Unit,
) {
    // The attention (tertiary) colour convention: red stays reserved for failures.
    TopBanner(
        message = stringResource(Res.string.ux_drafts_count, count),
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        TextButton(onClick = onOpen) { Text(stringResource(Res.string.ux_resume_draft)) }
    }
}
