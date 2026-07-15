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
import dev.npn.core.model.DocStatus

@Composable
fun StatusBadge(status: DocStatus, modifier: Modifier = Modifier) {
    val (label, bg, fg) = when (status) {
        DocStatus.INBOX -> Triple("Inbox", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        DocStatus.REVIEWED -> Triple("Reviewed", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        DocStatus.FILED -> Triple("Filed", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Statuses in lifecycle order, for dropdowns. */
fun statusLabel(status: DocStatus): String = when (status) {
    DocStatus.INBOX -> "Inbox"
    DocStatus.REVIEWED -> "Reviewed"
    DocStatus.FILED -> "Filed"
}
