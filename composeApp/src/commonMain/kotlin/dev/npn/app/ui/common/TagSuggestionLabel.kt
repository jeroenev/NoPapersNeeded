package dev.npn.app.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_new
import org.jetbrains.compose.resources.stringResource

/**
 * Label for an AI tag suggestion chip: the [tag] text, followed by a small "new" badge when
 * [isNew] so genuinely new (non-vocabulary) tags stand out from reused library tags.
 */
@Composable
fun TagSuggestionLabel(
    tag: String,
    isNew: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tag)
        if (isNew) {
            Text(
                stringResource(Res.string.common_new),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    androidx.compose.ui.Modifier
                        .padding(start = 6.dp),
            )
        }
    }
}
