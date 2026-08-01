@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package dev.npn.app.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.DocThumbnail
import dev.npn.app.ui.common.HighlightText
import dev.npn.app.ui.common.ReviewedBadge
import dev.npn.app.ui.common.TagChip
import dev.npn.app.ui.common.documentDisplayTitle
import dev.npn.app.ui.common.formatEpochDate
import dev.npn.app.ui.common.formatSize
import dev.npn.app.ui.common.parseFtsSnippet
import dev.npn.core.app.DocContentFacade
import dev.npn.core.app.DocListItem
import dev.npn.core.model.ReviewMode

private fun DocListItem.displayTitle(): String = documentDisplayTitle(doc.title, doc.fileName)

/**
 * Effective reviewed state for display. In UNTAGGED_ONLY the marker is derived (reviewed == has
 * any tag); in INBOX_WORKFLOW the stored reviewed flag is authoritative.
 */
fun DocListItem.effectiveReviewed(mode: ReviewMode): Boolean =
    when (mode) {
        ReviewMode.UNTAGGED_ONLY -> tags.isNotEmpty()
        ReviewMode.INBOX_WORKFLOW -> doc.reviewed
    }

/**
 * Desktop convenience: Ctrl/Cmd+click toggles the document's selection instead of opening it.
 * The press is consumed on the [PointerEventPass.Initial] pass so [combinedClickable]'s tap
 * detector never sees it; all other gestures pass straight through. Toggling happens on press
 * (the consumed down makes an up/cancel wait return immediately, so there is nothing to await).
 */
@Composable
private fun Modifier.ctrlClickToggle(onToggleSelect: () -> Unit): Modifier {
    val current by rememberUpdatedState(onToggleSelect)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val press = event.changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
                if (event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed) {
                    press.consume()
                    current()
                }
            }
        }
    }
}

@Composable
fun DocListRow(
    item: DocListItem,
    content: DocContentFacade,
    tagColors: Map<String, String?>,
    reviewMode: ReviewMode,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelect: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .ctrlClickToggle(onToggleSelect)
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
                // Only the inbox workflow surfaces a review status. Without it, an untagged
                // document is not "wrong", so no "Needs review" badge is shown.
                if (reviewMode == ReviewMode.INBOX_WORKFLOW) {
                    ReviewedBadge(item.effectiveReviewed(reviewMode))
                }
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
                HighlightText(
                    segments = parseFtsSnippet(snip),
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
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
    onToggleSelect: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .ctrlClickToggle(onToggleSelect)
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
                    Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
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
        }
    }
}
