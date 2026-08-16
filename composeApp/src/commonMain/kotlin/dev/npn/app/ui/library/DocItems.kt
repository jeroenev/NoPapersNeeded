@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package dev.npn.app.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.DocThumbnail
import dev.npn.app.ui.common.HighlightText
import dev.npn.app.ui.common.ReviewedBadge
import dev.npn.app.ui.common.TagChip
import dev.npn.app.ui.common.formatEpochDate
import dev.npn.app.ui.common.formatSize
import dev.npn.app.ui.common.onSecondaryClick
import dev.npn.app.ui.common.parseFtsSnippet
import dev.npn.core.app.DocContentFacade
import dev.npn.core.app.DocListItem
import dev.npn.core.model.ReviewMode
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_add_tag
import no_papers_needed.composeapp.generated.resources.library_deselect
import no_papers_needed.composeapp.generated.resources.library_open
import no_papers_needed.composeapp.generated.resources.library_remove_tag
import no_papers_needed.composeapp.generated.resources.library_select
import no_papers_needed.composeapp.generated.resources.library_trash
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

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

/**
 * Actions of the desktop right-click context menu of a document item. Select/deselect is not in
 * here because the item already receives it; the rest routes back to the library screen (dialogs,
 * trash confirmation), acting on the whole selection after making sure the clicked document is
 * part of it.
 */
@Immutable
data class DocContextMenuActions(
    val onOpen: () -> Unit,
    val onAddTag: () -> Unit,
    val onRemoveTag: () -> Unit,
    val onTrash: () -> Unit,
)

/** The right-click context menu of a document item, anchored at the press position. */
@Composable
private fun DocContextMenu(
    at: Offset?,
    selected: Boolean,
    onDismiss: () -> Unit,
    onToggleSelect: () -> Unit,
    actions: DocContextMenuActions,
) {
    val position = at ?: return
    // Zero-size anchor at the press position; the menu opens right at the pointer.
    Box(Modifier.offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }) {
        DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.library_open)) },
                onClick = {
                    onDismiss()
                    actions.onOpen()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (selected) Res.string.library_deselect else Res.string.library_select,
                        ),
                    )
                },
                onClick = {
                    onDismiss()
                    onToggleSelect()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.common_add_tag)) },
                onClick = {
                    onDismiss()
                    actions.onAddTag()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.library_remove_tag)) },
                onClick = {
                    onDismiss()
                    actions.onRemoveTag()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.library_trash)) },
                onClick = {
                    onDismiss()
                    actions.onTrash()
                },
            )
        }
    }
}

/**
 * Selection checkbox overlaid on the thumbnail while the pointer hovers the item (desktop) or
 * while selection mode is active; touch devices never hover, so outside selection mode this
 * stays invisible there and long-press remains the entry point into selection mode.
 */
@Composable
private fun HoverSelectCheckbox(
    visible: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    Checkbox(checked = selected, onCheckedChange = { onToggleSelect() }, modifier = modifier)
}

/**
 * Shared selection scaffolding for a single document item, in list or grid form.
 *
 * Owns the gesture contract every item shares — hover tracking, ctrl-click toggle, secondary-click
 * context menu, and click/long-click — so the two item shapes cannot drift apart. [itemContent]
 * receives the modifier carrying those gestures and must apply it to its own root, plus the current
 * hover state for the select checkbox.
 */
@Composable
private fun SelectableDocItem(
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelect: () -> Unit,
    contextActions: DocContextMenuActions?,
    itemContent: @Composable (itemModifier: Modifier, hovered: Boolean) -> Unit,
) {
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    Box {
        itemContent(
            Modifier
                .fillMaxWidth()
                .hoverable(hoverInteraction)
                .ctrlClickToggle(onToggleSelect)
                .onSecondaryClick { if (contextActions != null) menuAt = it }
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            hovered,
        )
        contextActions?.let { actions ->
            DocContextMenu(
                at = menuAt,
                selected = selected,
                onDismiss = { menuAt = null },
                onToggleSelect = onToggleSelect,
                actions = actions,
            )
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
    contextActions: DocContextMenuActions? = null,
) {
    SelectableDocItem(
        selected = selected,
        onClick = onClick,
        onLongClick = onLongClick,
        onToggleSelect = onToggleSelect,
        contextActions = contextActions,
    ) { itemModifier, hovered ->
        Row(
            modifier = itemModifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                DocThumbnail(docId = item.doc.docId, content = content, size = 48.dp)
                // Also shown during selection mode: keeping it in the thumbnail spot avoids
                // shifting the row content when a leading checkbox would be inserted.
                HoverSelectCheckbox(
                    visible = hovered || selectionMode,
                    selected = selected,
                    onToggleSelect = onToggleSelect,
                )
            }
            DocListRowDetails(
                item = item,
                tagColors = tagColors,
                reviewMode = reviewMode,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Title, tag chips, date/size line and FTS snippet of one list row. */
@Composable
private fun DocListRowDetails(
    item: DocListItem,
    tagColors: Map<String, String?>,
    reviewMode: ReviewMode,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = item.doc.displayTitle,
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
            if (reviewMode.reviewMarkerIsAuthoritative) {
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
    contextActions: DocContextMenuActions? = null,
) {
    SelectableDocItem(
        selected = selected,
        onClick = onClick,
        onLongClick = onLongClick,
        onToggleSelect = onToggleSelect,
        contextActions = contextActions,
    ) { itemModifier, hovered ->
        Card(modifier = itemModifier) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box {
                    DocThumbnail(
                        docId = item.doc.docId,
                        content = content,
                        size = 120.dp,
                        modifier = Modifier.fillMaxWidth().aspectRatio(0.75f),
                    )
                    // Stays in the same top-right spot in selection mode so entering/leaving
                    // selection never shifts the card layout.
                    HoverSelectCheckbox(
                        visible = hovered || selectionMode,
                        selected = selected,
                        onToggleSelect = onToggleSelect,
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
                Text(
                    text = item.doc.displayTitle,
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
}
