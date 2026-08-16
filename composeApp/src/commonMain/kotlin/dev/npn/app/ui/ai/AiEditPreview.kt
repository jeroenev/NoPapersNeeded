package dev.npn.app.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.DocThumbnail
import dev.npn.app.ui.common.resolve
import dev.npn.core.app.DocContentFacade
import dev.npn.core.app.DocListItem
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_collapse
import no_papers_needed.composeapp.generated.resources.ai_expand
import org.jetbrains.compose.resources.stringResource

/** Thumbnail size of a preview row; small enough that a full batch stays scannable. */
private val PreviewThumbnailSize = 36.dp

/**
 * The scope summary line ("N documents, M in this batch") doubling as the toggle for a list of the
 * exact documents the next batch would process. Lets the user verify the scope before spending
 * model calls, which a bare count cannot.
 */
@Composable
internal fun BatchPreview(
    preview: ScopePreview,
    batchSize: Int,
    content: DocContentFacade,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(selected = expanded, enabled = preview.batch.isNotEmpty()) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                scopeCountText(preview.total, batchSize).resolve(),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (preview.batch.isNotEmpty()) {
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription =
                        stringResource(if (expanded) Res.string.ai_collapse else Res.string.ai_expand),
                )
            }
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                preview.batch.forEach { item -> PreviewRow(item, content) }
            }
        }
    }
}

/** One document in the batch: thumbnail, display title and the metadata it currently carries. */
@Composable
private fun PreviewRow(
    item: DocListItem,
    content: DocContentFacade,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DocThumbnail(docId = item.doc.docId, content = content, size = PreviewThumbnailSize)
        Column(Modifier.weight(1f)) {
            Text(
                item.doc.displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Existing metadata makes "missing a chosen field" scopes verifiable at a glance.
            val details = listOfNotNull(item.doc.correspondent, item.doc.docDate).joinToString(" · ")
            if (details.isNotBlank()) {
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
