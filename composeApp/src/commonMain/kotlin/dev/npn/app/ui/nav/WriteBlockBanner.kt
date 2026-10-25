package dev.npn.app.ui.nav

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import dev.npn.app.ui.common.TopBanner
import dev.npn.app.ui.common.WithTopBanner
import dev.npn.app.ui.common.reasonText
import dev.npn.app.ui.common.resolve
import dev.npn.core.index.WriteBlock
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_rebuild_index
import no_papers_needed.composeapp.generated.resources.common_retry
import org.jetbrains.compose.resources.stringResource

/**
 * Puts the "this library is read-only" banner above [content] (see [WithTopBanner]) while [block]
 * refuses writes, so the user learns it before a write fails rather than from the failure. Not
 * dismissable: it follows the session's live state and goes once a pass reads the journal whole.
 * [actions] read the journal again, or build this device's index again from the folder alone, for a
 * block no reading lifts (a journal object nobody holds any more). Both are offered only where they
 * can lift the block: not for a library a newer app changed, which only an update reads.
 */
@Composable
internal fun WithWriteBlockBanner(
    block: WriteBlock?,
    actions: WriteBlockActions,
    content: @Composable () -> Unit,
) {
    WithTopBanner(
        banner = block?.let { { WriteBlockBanner(it, actions) } },
        content = content,
    )
}

@Composable
private fun WriteBlockBanner(
    block: WriteBlock,
    actions: WriteBlockActions,
) {
    // The reason of a refused write, which reads as the rest of a "Could not …: %s" sentence; on its
    // own it starts a sentence. Every locale's starts with a letter that has a capital.
    val message = block.reasonText().resolve().replaceFirstChar { it.titlecase() }
    // Red: every write the user tries fails while it shows.
    TopBanner(
        message = message,
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        if (block != WriteBlock.NEWER_FORMAT) {
            TextButton(onClick = actions::retry) { Text(stringResource(Res.string.common_retry)) }
            TextButton(onClick = actions::rebuildIndex) { Text(stringResource(Res.string.common_rebuild_index)) }
        }
    }
}
