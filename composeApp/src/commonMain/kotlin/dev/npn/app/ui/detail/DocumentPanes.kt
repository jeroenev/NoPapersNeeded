package dev.npn.app.ui.detail

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.LocalWindowWidthClass
import dev.npn.core.app.DocListItem
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ux_resize_panes
import org.jetbrains.compose.resources.stringResource

/** Smallest usable width of either pane; the split fraction is clamped so neither drops below it. */
private val PaneMinWidth = 320.dp

/** Width of the draggable divider between the two panes; excluded from the area the panes share. */
private val DividerWidth = 48.dp

/** How far one arrow key moves the divider, as a fraction of the width the panes share. */
private const val KEY_STEP = 0.05f

/**
 * The page preview and the metadata tabs of [item], the document as its draft shows it: side by
 * side on a wide window, or the preview as the first tab on a compact one. The library's side panel
 * counts as compact (see [dev.npn.app.ui.library.DocPreviewPanel]). [refused] are the keys whose
 * draft value is an entry its editor refused, as typed. [writeBlocked] holds while this document
 * cannot be written: a save is in flight, or the draft is not writable yet (an unparseable date).
 * Only controls that *trigger* a write follow it — editing itself stays live, so a save in progress
 * never disables the field the user is typing in.
 */
@Composable
fun MetadataPanel(
    state: MetadataUiState,
    item: DocListItem,
    refused: Set<String>,
    writeBlocked: Boolean,
    actions: MetadataActions,
    previewTab: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The tab picked lives here, not in the tabs: the wide layout composes them inside
    // BoxWithConstraints, a subcomposition, and movable content keeps its state when it moves out
    // of one but not into one, so a tab picked on a compact window would be lost on a wide one.
    var picked by rememberSaveable { mutableStateOf<PanelTab?>(null) }
    // One composition of the tabs, moved between the two layouts rather than built again in each:
    // what they hold (a dialog open over them, text typed into it, an editor's entry) stays when the
    // window turns compact.
    val tabs =
        remember {
            movableContentOf { args: MetadataTabsArgs ->
                MetadataTabs(
                    args.state,
                    args.item,
                    args.refused,
                    args.writeBlocked,
                    args.actions,
                    args.picked,
                    args.onPick,
                    args.previewTab,
                    args.modifier,
                )
            }
        }
    val onPick = { tab: PanelTab -> picked = tab }
    Box(modifier) {
        if (LocalWindowWidthClass.current.isWide) {
            DocumentPanes(
                preview = previewTab,
                details = {
                    tabs(
                        MetadataTabsArgs(
                            state,
                            item,
                            refused,
                            writeBlocked,
                            actions,
                            picked,
                            onPick,
                            previewTab = null,
                            it,
                        ),
                    )
                },
            )
        } else {
            tabs(
                MetadataTabsArgs(
                    state,
                    item,
                    refused,
                    writeBlocked,
                    actions,
                    picked,
                    onPick,
                    previewTab,
                    Modifier.fillMaxSize(),
                ),
            )
        }
    }
}

/** What [MetadataTabs] is shown with, as one value its movable content takes. */
private class MetadataTabsArgs(
    val state: MetadataUiState,
    val item: DocListItem,
    val refused: Set<String>,
    val writeBlocked: Boolean,
    val actions: MetadataActions,
    val picked: PanelTab?,
    val onPick: (PanelTab) -> Unit,
    val previewTab: (@Composable (Modifier) -> Unit)?,
    val modifier: Modifier,
)

@Composable
private fun DocumentPanes(
    preview: @Composable (Modifier) -> Unit,
    details: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        var fraction by rememberSaveable { mutableFloatStateOf(0.55f) }
        val available = maxWidth - DividerWidth
        // Clamp the minimum to 0.5 first so the [minimum, maximum] range can never invert (and make
        // coerceIn throw) when the available width is too small to give both panes their minimum —
        // unreachable at the widths this runs at today, but self-contained against future constant moves.
        val minimum = (PaneMinWidth / available).coerceAtMost(0.5f)
        val maximum = 1f - minimum
        // Single place the split fraction is confined to the legal range; reused by every handler below.
        val clamp = { value: Float -> value.coerceIn(minimum, maximum) }
        val effective = clamp(fraction)
        val widthPx = with(LocalDensity.current) { available.toPx() }
        val label = stringResource(Res.string.ux_resize_panes)
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(available * effective).fillMaxHeight()) { preview(Modifier.fillMaxSize()) }
            Box(
                Modifier
                    .width(DividerWidth)
                    .fillMaxHeight()
                    .semantics {
                        contentDescription = label
                        progressBarRangeInfo = ProgressBarRangeInfo(effective, minimum..maximum)
                        setProgress {
                            fraction = clamp(it)
                            true
                        }
                    }.paneResizeKeys(
                        onShrink = { fraction = clamp(effective - KEY_STEP) },
                        onGrow = { fraction = clamp(effective + KEY_STEP) },
                    ).focusable()
                    .draggable(
                        state =
                            rememberDraggableState { delta ->
                                fraction = clamp(clamp(fraction) + delta / widthPx)
                            },
                        orientation = Orientation.Horizontal,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.DragIndicator, contentDescription = null)
            }
            Box(Modifier.weight(1f).fillMaxHeight()) { details(Modifier.fillMaxSize()) }
        }
    }
}
