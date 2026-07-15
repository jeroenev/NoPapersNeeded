@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package dev.npn.app.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.DocThumbnail
import dev.npn.app.ui.common.StatusBadge
import dev.npn.app.ui.common.TagChip
import dev.npn.app.ui.common.formatEpochDate
import dev.npn.app.ui.common.formatSize
import dev.npn.core.app.DocContentFacade
import dev.npn.core.app.DocListItem
import dev.npn.core.model.DocStatus

private fun DocListItem.displayTitle(): String =
    doc.title?.takeIf { it.isNotBlank() } ?: doc.fileName

@Composable
fun DocListRow(
    item: DocListItem,
    content: DocContentFacade,
    tagColors: Map<String, String?>,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
        }
        DocThumbnail(docId = item.doc.docId, content = content, size = 48.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = item.displayTitle(),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    item.tags.forEach { t -> TagChip(name = t, color = tagColors[t]) }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusBadge(DocStatus.fromWire(item.doc.status))
                Text(
                    text = item.doc.docDate ?: formatEpochDate(item.doc.addedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatSize(item.doc.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item.snippet?.let { snip ->
                Text(
                    text = snip,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun DocGridCard(
    item: DocListItem,
    content: DocContentFacade,
    tagColors: Map<String, String?>,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DocThumbnail(
                    docId = item.doc.docId,
                    content = content,
                    size = 120.dp,
                    modifier = Modifier.weight(1f).aspectRatio(0.75f),
                )
                if (selectionMode) {
                    Checkbox(checked = selected, onCheckedChange = null)
                }
            }
            Text(
                text = item.displayTitle(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    item.tags.take(3).forEach { t -> TagChip(name = t, color = tagColors[t]) }
                }
            }
            Spacer(Modifier.width(0.dp))
        }
    }
}
