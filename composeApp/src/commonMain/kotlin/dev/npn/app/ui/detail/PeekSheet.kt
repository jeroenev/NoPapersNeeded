package dev.npn.app.ui.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import dev.npn.app.ui.common.TagChip
import dev.npn.app.ui.common.formatEpochDate
import dev.npn.core.app.DocListItem
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.detail_peek_hide_details
import no_papers_needed.composeapp.generated.resources.detail_peek_show_details
import org.jetbrains.compose.resources.stringResource

/** How much of the screen the expanded sheet may take, keeping a strip of the preview visible. */
private const val EXPANDED_SHEET_FRACTION = 0.86f

/** The collapsed sheet's height: enough for the title, its date and a row or two of tags. */
private val CollapsedSheetHeight = 196.dp

/** How far the sheet slides, in pixels per second, before a fling decides its end by direction. */
private const val FLING_THRESHOLD = 900f

private val SheetAnimation = tween<Float>(durationMillis = 220)

/**
 * The compact detail's metadata sheet: collapsed over the preview it leaves visible, showing the
 * document's title, date and tags; dragged up or tapped it expands to the metadata editor, with the
 * preview still behind it. [metadata] is the existing panel (the one editor all hosts share) and
 * [statusBar] its save line; nothing here is a second editor.
 *
 * [state] is hoisted so the screen's back handling can collapse the sheet before it leaves the
 * detail, whether Back came from the system gesture, Escape or the top bar. System back is taken
 * only while expanded; once collapsed it falls through to the detail route's own back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PeekDocumentSheet(
    item: DocListItem,
    tagColors: Map<String, String?>,
    state: PeekSheetState,
    preview: @Composable (Modifier) -> Unit,
    metadata: @Composable (Modifier) -> Unit,
    statusBar: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = state.expanded,
        onBackCompleted = { state.collapse() },
    )
    BoxWithConstraints(modifier.fillMaxSize()) {
        val expandedHeight = maxHeight * EXPANDED_SHEET_FRACTION
        val collapsedHeight = minOf(CollapsedSheetHeight, maxHeight * 0.45f)
        val travel: Dp = (expandedHeight - collapsedHeight).coerceAtLeast(0.dp)
        val travelPx = with(LocalDensity.current) { travel.toPx() }
        val animated by animateFloatAsState(
            targetValue = if (state.expanded) 1f else 0f,
            animationSpec = SheetAnimation,
            label = "peek-sheet",
        )
        // While a finger is on the sheet its own position wins; the animated value takes over again
        // when it is released.
        var draggingFraction by remember { mutableStateOf<Float?>(null) }
        val fraction = draggingFraction ?: animated
        val dragState =
            rememberDraggableState { delta ->
                val from = draggingFraction ?: animated
                draggingFraction = (from - delta / travelPx).coerceIn(0f, 1f)
            }

        preview(Modifier.fillMaxSize().padding(bottom = collapsedHeight))
        Surface(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(collapsedHeight + travel * fraction),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .draggable(
                            state = dragState,
                            orientation = Orientation.Vertical,
                            onDragStopped = { velocity ->
                                val current = draggingFraction ?: animated
                                val expand =
                                    when {
                                        velocity < -FLING_THRESHOLD -> true
                                        velocity > FLING_THRESHOLD -> false
                                        else -> current >= 0.5f
                                    }
                                draggingFraction = null
                                state.set(expand)
                            },
                        ).clickable(
                            onClickLabel =
                                stringResource(
                                    if (state.expanded) {
                                        Res.string.detail_peek_hide_details
                                    } else {
                                        Res.string.detail_peek_show_details
                                    },
                                ),
                            onClick = state::toggle,
                        ),
                ) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .width(40.dp)
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                        )
                    }
                    Column(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = item.doc.displayTitle,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val date = item.doc.docDate ?: formatEpochDate(item.doc.createdAt)
                        Text(
                            text = date,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (item.tags.isNotEmpty()) {
                            FlowRow(
                                maxLines = 1,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                item.tags.forEach { tag -> TagChip(name = tag, color = tagColors[tag]) }
                            }
                        }
                    }
                }
                if (state.expanded || (draggingFraction ?: 0f) > 0.5f) {
                    metadata(Modifier.fillMaxWidth().weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                statusBar(Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * Whether the compact detail's metadata sheet is collapsed or expanded. Hoisted above the sheet so
 * both the sheet's own gestures and the screen's back handling speak the same state.
 */
@androidx.compose.runtime.Stable
internal class PeekSheetState(
    initialExpanded: Boolean = false,
) {
    var expanded: Boolean by mutableStateOf(initialExpanded)
        private set

    fun expand() {
        expanded = true
    }

    fun collapse() {
        expanded = false
    }

    fun toggle() {
        expanded = !expanded
    }

    fun set(value: Boolean) {
        expanded = value
    }

    /** Collapse an expanded sheet, reporting whether it took the action rather than letting it through. */
    fun collapseIfExpanded(): Boolean {
        if (!expanded) return false
        expanded = false
        return true
    }

    companion object {
        val Saver: Saver<PeekSheetState, Boolean> =
            Saver(
                save = { it.expanded },
                restore = { PeekSheetState(initialExpanded = it) },
            )
    }
}
