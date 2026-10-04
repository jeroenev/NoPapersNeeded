package dev.npn.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_edit_replaces
import org.jetbrains.compose.resources.stringResource

/**
 * One AI-suggested field value: the field's [label], the suggested [value], and the value it
 * [replaces] when accepting it would overwrite one. The bulk Review and the document panel both
 * show a suggestion through this, so an overwrite reads the same wherever it is accepted.
 */
@Composable
fun SuggestedFieldValue(
    label: String,
    value: String,
    replaces: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        // What this would destroy, in the error colour: it is the one suggestion kind that does
        // not add to the library but replaces part of it.
        replaces?.let {
            Text(
                stringResource(Res.string.ai_edit_replaces, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
