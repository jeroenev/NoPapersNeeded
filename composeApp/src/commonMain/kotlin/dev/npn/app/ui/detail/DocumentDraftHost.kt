package dev.npn.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
 * Puts the "you have unsaved documents" banner above [content], so a draft left behind on another
 * screen stays visible instead of being silently held. A draft whose editor is currently on screen
 * — the full-screen detail route or the wide layout's side preview panel — is excluded, so the
 * banner never offers to open a document the user is already looking at, regardless of the route.
 *
 * The banner takes the status-bar inset while it is visible and consumes it for [content], so the
 * screens' own top bars are never padded twice; with no banner, they keep drawing behind the
 * status bar exactly as they do without this wrapper.
 */
@Composable
internal fun WithDraftResumeBanner(
    onOpen: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    val store = LocalDocumentDrafts.current
    // store.state / store.onScreen are snapshot-backed reads, so this recomposes on either change.
    val pending = store.state.filterValues { it.dirty }.keys - store.onScreen
    Column(Modifier.fillMaxSize()) {
        if (pending.isNotEmpty()) {
            DraftResumeBanner(
                count = pending.size,
                onOpen = { onOpen(pending.first()) },
            )
        }
        Box(
            Modifier
                .weight(1f)
                .then(
                    if (pending.isEmpty()) Modifier else Modifier.consumeWindowInsets(WindowInsets.statusBars),
                ),
        ) { content() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DraftResumeBanner(
    count: Int,
    onOpen: () -> Unit,
) {
    // The banner sits above whatever screen is showing, so it needs a surface of its own or it
    // reads as part of that screen's top bar. Use the attention (tertiary) colour convention —
    // red stays reserved for failures. The Surface colour fills behind the status bar; the inner
    // statusBarsPadding keeps the text and button below it.
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        FlowRow(
            Modifier.statusBarsPadding().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                stringResource(Res.string.ux_drafts_count, count),
                modifier = Modifier.padding(vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onOpen) { Text(stringResource(Res.string.ux_resume_draft)) }
        }
    }
}
