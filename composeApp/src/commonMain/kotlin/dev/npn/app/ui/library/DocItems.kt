@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package dev.npn.app.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LabelOff
import androidx.compose.material.icons.filled.NewLabel
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.ActionMenuItems
import dev.npn.app.ui.common.DocThumbnail
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.HighlightText
import dev.npn.app.ui.common.ReviewedBadge
import dev.npn.app.ui.common.TagChip
import dev.npn.app.ui.common.TopBarAction
import dev.npn.app.ui.common.formatEpochDate
import dev.npn.app.ui.common.formatSize
import dev.npn.app.ui.common.onSecondaryClick
import dev.npn.app.ui.common.parseFtsSnippet
import dev.npn.core.app.DocContentFacade
import dev.npn.core.app.DocListItem
import dev.npn.core.app.effectiveReviewed
import dev.npn.core.model.ReviewMode
import dev.npn.core.model.reviewMarkerIsAuthoritative
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_add_tag
import no_papers_needed.composeapp.generated.resources.library_deselect
import no_papers_needed.composeapp.generated.resources.library_more_tags
import no_papers_needed.composeapp.generated.resources.library_open
import no_papers_needed.composeapp.generated.resources.library_preview
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
 * How the library wires each document item, in the list and the grid alike: what an item shows
 * besides its own row (thumbnails from [content], tag colours, the review badge) and where its
 * clicks and its right-click menu go, each action taking the item's id. [LibraryContent] makes one
 * per screen state, so every item gets the same instance and skips while the list scrolls.
 *
 * The menu's tag and trash actions act on the whole selection; the screen pulls the clicked
 * document into it first.
 */
internal class DocItemWiring(
    val content: DocContentFacade,
    val tagColors: Map<String, String?>,
    val reviewMode: ReviewMode,
    /**
     * Whether a click that does not select previews the document (wide layouts) rather than opening
     * it (compact layouts). Only the accessibility click label differs; the click goes to [onOpen].
     */
    val previewOnClick: Boolean,
    val onOpen: (String) -> Unit,
    val onToggleSelect: (String) -> Unit,
    val onStartSelection: (String) -> Unit,
    val onAddTag: (String) -> Unit,
    val onRemoveTag: (String) -> Unit,
    val onTrash: (String) -> Unit,
)

/** The right-click context menu of a document item, anchored at the press position. */
@Composable
private fun DocContextMenu(
    at: Offset?,
    docId: String,
    selected: Boolean,
    wiring: DocItemWiring,
    onDismiss: () -> Unit,
) {
    val position = at ?: return
    // Zero-size anchor at the press position; the menu opens right at the pointer.
    Box(Modifier.offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }) {
        DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
            ActionMenuItems(
                actions =
                    listOf(
                        TopBarAction(Icons.Filled.OpenInNew, stringResource(Res.string.library_open), {
                            wiring.onOpen(docId)
                        }),
                        TopBarAction(
                            if (selected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                            stringResource(if (selected) Res.string.library_deselect else Res.string.library_select),
                            { wiring.onToggleSelect(docId) },
                        ),
                        TopBarAction(Icons.Filled.NewLabel, stringResource(Res.string.common_add_tag), {
                            wiring.onAddTag(docId)
                        }),
                        TopBarAction(Icons.Filled.LabelOff, stringResource(Res.string.library_remove_tag), {
                            wiring.onRemoveTag(docId)
                        }),
                        TopBarAction(Icons.Filled.Delete, stringResource(Res.string.library_trash), {
                            wiring.onTrash(docId)
                        }),
                    ),
                onDismiss = onDismiss,
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
 * The one wiring of a document item, in list or grid form: hover tracking, ctrl-click toggle, the
 * secondary-click context menu, and click/long-click, all from [wiring], so the two item shapes
 * cannot drift apart. [itemContent] receives the modifier carrying those gestures and must apply it
 * to its own root, plus the current hover state for the select checkbox.
 */
@Composable
private fun SelectableDocItem(
    docId: String,
    wiring: DocItemWiring,
    selectionMode: Boolean,
    selected: Boolean,
    itemContent: @Composable (itemModifier: Modifier, hovered: Boolean) -> Unit,
) {
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    val openLabel =
        stringResource(if (wiring.previewOnClick) Res.string.library_preview else Res.string.library_open)
    val selectionLabel =
        stringResource(if (selected) Res.string.library_deselect else Res.string.library_select)
    Box {
        itemContent(
            Modifier
                .fillMaxWidth()
                .hoverable(hoverInteraction)
                .ctrlClickToggle { wiring.onToggleSelect(docId) }
                .onSecondaryClick { menuAt = it }
                .semantics { this.selected = selected }
                .combinedClickable(
                    onClickLabel = if (selectionMode) selectionLabel else openLabel,
                    role = Role.Button,
                    onLongClickLabel = selectionLabel,
                    onClick = { if (selectionMode) wiring.onToggleSelect(docId) else wiring.onOpen(docId) },
                    onLongClick = { wiring.onStartSelection(docId) },
                ),
            hovered,
        )
        DocContextMenu(
            at = menuAt,
            docId = docId,
            selected = selected,
            wiring = wiring,
            onDismiss = { menuAt = null },
        )
    }
}

@Composable
internal fun DocListRow(
    item: DocListItem,
    wiring: DocItemWiring,
    selectionMode: Boolean,
    selected: Boolean,
) {
    val docId = item.doc.docId
    SelectableDocItem(docId, wiring, selectionMode, selected) { itemModifier, hovered ->
        Row(
            modifier = itemModifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                DocThumbnail(docId = docId, content = wiring.content, size = 48.dp)
                // Also shown during selection mode: keeping it in the thumbnail spot avoids
                // shifting the row content when a leading checkbox would be inserted.
                HoverSelectCheckbox(
                    visible = hovered || selectionMode,
                    selected = selected,
                    onToggleSelect = { wiring.onToggleSelect(docId) },
                )
            }
            DocListRowDetails(
                item = item,
                tagColors = wiring.tagColors,
                reviewMode = wiring.reviewMode,
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
                item.tags.take(MAX_LIST_TAGS).forEach { t -> TagChip(name = t, color = tagColors[t]) }
                TagOverflow(item.tags.size - MAX_LIST_TAGS)
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
            HelpText(item.doc.docDate ?: formatEpochDate(item.doc.addedAt))
            HelpText(formatSize(item.doc.size))
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
internal fun DocGridCard(
    item: DocListItem,
    wiring: DocItemWiring,
    selectionMode: Boolean,
    selected: Boolean,
) {
    val docId = item.doc.docId
    SelectableDocItem(docId, wiring, selectionMode, selected) { itemModifier, hovered ->
        Card(modifier = itemModifier) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box {
                    DocThumbnail(
                        docId = docId,
                        content = wiring.content,
                        size = 120.dp,
                        modifier = Modifier.fillMaxWidth().aspectRatio(0.75f),
                    )
                    // Stays in the same top-right spot in selection mode so entering/leaving
                    // selection never shifts the card layout.
                    HoverSelectCheckbox(
                        visible = hovered || selectionMode,
                        selected = selected,
                        onToggleSelect = { wiring.onToggleSelect(docId) },
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
                // The only card badge: review status, and only where the marker is real (inbox
                // workflow). Deliberately minimal — no snippet/date/size on the card.
                if (wiring.reviewMode.reviewMarkerIsAuthoritative) {
                    ReviewedBadge(item.effectiveReviewed(wiring.reviewMode))
                }
                Text(
                    text = item.doc.displayTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.tags.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        item.tags.take(MAX_GRID_TAGS).forEach { t -> TagChip(name = t, color = wiring.tagColors[t]) }
                        TagOverflow(item.tags.size - MAX_GRID_TAGS)
                    }
                }
            }
        }
    }
}

@Composable
private fun TagOverflow(hidden: Int) {
    if (hidden <= 0) return
    Text(
        text = stringResource(Res.string.library_more_tags, hidden),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

private const val MAX_LIST_TAGS = 5
private const val MAX_GRID_TAGS = 3
