package dev.npn.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_needs_review
import no_papers_needed.composeapp.generated.resources.common_reviewed
import org.jetbrains.compose.resources.stringResource

/** The one shape and metric every inline status chip in the app uses. */
@Composable
fun StatusBadge(
    label: String,
    background: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = contentColor,
        modifier =
            modifier
                .clip(RoundedCornerShape(4.dp))
                .background(background)
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Small badge showing whether a document has been reviewed. */
@Composable
fun ReviewedBadge(
    reviewed: Boolean,
    modifier: Modifier = Modifier,
) {
    if (reviewed) {
        StatusBadge(
            label = stringResource(Res.string.common_reviewed),
            background = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = modifier,
        )
    } else {
        StatusBadge(
            label = stringResource(Res.string.common_needs_review),
            background = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = modifier,
        )
    }
}
