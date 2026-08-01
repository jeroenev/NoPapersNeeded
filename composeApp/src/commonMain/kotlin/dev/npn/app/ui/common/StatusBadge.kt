package dev.npn.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_needs_review
import no_papers_needed.composeapp.generated.resources.common_reviewed
import org.jetbrains.compose.resources.stringResource

/** Small badge showing whether a document has been reviewed. */
@Composable
fun ReviewedBadge(
    reviewed: Boolean,
    modifier: Modifier = Modifier,
) {
    val (label, bg, fg) =
        if (reviewed) {
            Triple(
                stringResource(Res.string.common_reviewed),
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.onPrimaryContainer,
            )
        } else {
            Triple(
                stringResource(Res.string.common_needs_review),
                MaterialTheme.colorScheme.tertiaryContainer,
                MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier =
            modifier
                .clip(RoundedCornerShape(4.dp))
                .background(bg)
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
