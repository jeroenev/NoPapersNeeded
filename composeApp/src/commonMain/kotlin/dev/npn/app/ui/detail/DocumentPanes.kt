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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.LocalWindowWidthClass
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ux_resize_panes
import org.jetbrains.compose.resources.stringResource

/** Smallest usable width of either pane; the split fraction is clamped so neither drops below it. */
private val PaneMinWidth = 320.dp

/** Width of the draggable divider between the two panes; excluded from the area the panes share. */
private val DividerWidth = 48.dp

/**
 * The page preview and the metadata tabs side by side on a wide window, or the preview as the first
 * tab on a compact one. The library's side panel counts as compact
 * (see [dev.npn.app.ui.library.DocPreviewPanel]).
 */
@Composable
fun MetadataPanel(
    state: MetadataUiState,
    actions: MetadataActions,
    previewTab: (@Composable (Modifier) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        if (LocalWindowWidthClass.current.isWide && previewTab != null) {
            DocumentPanes(
                preview = previewTab,
                details = { MetadataTabs(state, actions, modifier = it) },
            )
        } else {
            MetadataTabs(state, actions, previewTab, Modifier.fillMaxSize())
        }
    }
}

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
                    }.onKeyEvent {
                        if (it.type != KeyEventType.KeyDown) {
                            false
                        } else {
                            when (it.key) {
                                Key.DirectionLeft -> {
                                    fraction = clamp(effective - 0.05f)
                                    true
                                }

                                Key.DirectionRight -> {
                                    fraction = clamp(effective + 0.05f)
                                    true
                                }

                                else -> {
                                    false
                                }
                            }
                        }
                    }.focusable()
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
