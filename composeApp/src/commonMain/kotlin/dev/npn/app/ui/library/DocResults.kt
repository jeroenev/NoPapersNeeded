package dev.npn.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.EchoTracker
import dev.npn.app.ui.common.ScrollToTopOnNewResults
import dev.npn.app.ui.common.ScrollbarWidth
import dev.npn.app.ui.common.VerticalScrollbar
import dev.npn.app.ui.common.ctrlScrollZoom
import dev.npn.app.ui.common.isLandscape
import dev.npn.app.ui.common.pinchToZoom
import dev.npn.core.app.DocListItem
import dev.npn.core.settings.GridSizing
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

private const val GRID_ZOOM_COMMIT_DEBOUNCE_MS = 300L

// Layout metrics of the grid, shared with the column-step math below.
private const val GRID_H_PADDING_DP = 12f
private const val GRID_SPACING_DP = 8f

/**
 * Column count [GridCells.Adaptive] produces for a min cell [width] in [available] dp
 * (mirrors its formula: floor((available + spacing) / (minSize + spacing)), at least 1).
 */
internal fun gridColumnsFor(
    available: Float,
    width: Float,
    spacing: Float = GRID_SPACING_DP,
): Int = ((available + spacing) / (width + spacing)).toInt().coerceAtLeast(1)

/**
 * The largest min cell width (dp) at which [GridCells.Adaptive] yields exactly [columns] in
 * [available] dp. Floored so dp/px rounding cannot tip the layout into one column fewer.
 */
internal fun gridWidthForColumns(
    available: Float,
    columns: Int,
    spacing: Float = GRID_SPACING_DP,
): Float = floor((available + spacing) / columns - spacing)

/**
 * The grid's item size as the view menu steps it: [zoomIn] (larger items, one column fewer) and
 * [zoomOut], each enabled while the grid has a column to give or take. The [DocGrid] on screen
 * answers them; with none, they do nothing and both are disabled.
 */
@Stable
internal class GridZoom {
    var canZoomIn by mutableStateOf(false)
        private set
    var canZoomOut by mutableStateOf(false)
        private set
    private var step: ((Int) -> Unit)? = null

    fun zoomIn() {
        step?.invoke(-1)
    }

    fun zoomOut() {
        step?.invoke(1)
    }

    /** What the grid on screen can do now, and how it steps its column count. */
    fun attach(
        canZoomIn: Boolean,
        canZoomOut: Boolean,
        step: (Int) -> Unit,
    ) {
        this.canZoomIn = canZoomIn
        this.canZoomOut = canZoomOut
        this.step = step
    }

    fun detach() {
        canZoomIn = false
        canZoomOut = false
        step = null
    }
}

/**
 * Thumbnail grid with direct-manipulation sizing between [GridSizing.MIN_COLUMN_WIDTH] and
 * [GridSizing.MAX_COLUMN_WIDTH] dp: pinch (touch) resizes continuously; ctrl+scroll (desktop)
 * and the view menu's size items step exactly one column per notch/press at the current grid
 * width, since with adaptive cells the column count is the only visible state. The view menu
 * steps it the same way, through [zoom]. Changes preview live via local state and are committed
 * (debounced) through [onColumnWidthChange], which persists them in settings.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun DocGrid(
    results: DocumentResults,
    wiring: DocItemWiring,
    selectionMode: Boolean,
    selected: Set<String>,
    columnWidth: Int,
    onColumnWidthChange: (Int) -> Unit,
    zoom: GridZoom,
) {
    // Live cell width; re-seeded when the persisted value changes elsewhere (the stored value
    // arriving, another window), never by the late echo of one of its own commits.
    var width by remember { mutableFloatStateOf(columnWidth.toFloat()) }
    val echoes = remember { EchoTracker(columnWidth) }
    // The width last committed or last heard from the owner: what a commit is compared against.
    var requested by remember { mutableIntStateOf(columnWidth) }
    DisposableEffect(columnWidth) {
        echoes.ownerValue(columnWidth)?.let {
            requested = it
            width = it.toFloat()
        }
        onDispose {}
    }

    // Debounced commit so pinches and fine-grained touchpad deltas don't write settings per event.
    val commit by rememberUpdatedState(onColumnWidthChange)
    LaunchedEffect(Unit) {
        snapshotFlow { width.roundToInt() }
            .debounce(GRID_ZOOM_COMMIT_DEBOUNCE_MS)
            .collect {
                if (it != requested) {
                    requested = it
                    echoes.typed(it)
                    commit(it)
                }
            }
    }

    val minWidth = GridSizing.MIN_COLUMN_WIDTH.toFloat()
    val maxWidth = GridSizing.MAX_COLUMN_WIDTH.toFloat()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Width the adaptive cells actually divide: the grid area minus its content padding and
        // the gutter reserved for the scrollbar (zero where no scrollbar is drawn).
        // NOTE: the gesture lambdas below are captured once by pointerInput and never refreshed,
        // so everything they touch must be backed by State (or read state at call time), never by
        // plain composition-time locals.
        val available by
            rememberUpdatedState(
                this.maxWidth.value - 2 * GRID_H_PADDING_DP - ScrollbarWidth.value,
            )
        val columns = gridColumnsFor(available, width)
        // The width bounds cap how far the count can step in either direction.
        val fewestColumns = gridColumnsFor(available, maxWidth)
        val mostColumns = gridColumnsFor(available, minWidth)

        // Zoom in = bigger cells = fewer columns (photo-gallery convention). Reads width/available
        // at call time so it stays correct when invoked from the stale gesture closure.
        fun stepColumns(delta: Int) {
            val avail = available
            val cols = gridColumnsFor(avail, width)
            val target =
                (cols + delta)
                    .coerceIn(gridColumnsFor(avail, maxWidth), gridColumnsFor(avail, minWidth))
            if (target != cols) {
                width = gridWidthForColumns(avail, target).coerceIn(minWidth, maxWidth)
            }
        }

        // Pinch stays continuous (a fingertip gesture has no natural notion of notches).
        fun pinchBy(factor: Float) {
            width = (width * factor).coerceIn(minWidth, maxWidth)
        }

        // The view menu's size items step the grid as shown now; zoom in = one column fewer.
        SideEffect {
            zoom.attach(
                canZoomIn = columns > fewestColumns,
                canZoomOut = columns < mostColumns,
                ::stepColumns,
            )
        }
        DisposableEffect(zoom) { onDispose(zoom::detach) }

        // Accumulates fractional touchpad deltas up to one notch; a mouse wheel notch arrives as a
        // single event with |delta| >= 1 and steps exactly one column regardless of its magnitude.
        val scrollAccumulated = remember { mutableFloatStateOf(0f) }

        Box(
            Modifier
                .fillMaxSize()
                .pinchToZoom(onZoom = ::pinchBy)
                .ctrlScrollZoom { delta, _ ->
                    val acc = scrollAccumulated.floatValue
                    val sameDirection = acc == 0f || (acc > 0f) == (delta > 0f)
                    val next = if (sameDirection) acc + delta else delta
                    if (abs(next) >= 1f) {
                        // Scroll up = zoom in = one column fewer; scroll down = one more.
                        stepColumns(if (next > 0f) 1 else -1)
                        scrollAccumulated.floatValue = 0f
                    } else {
                        scrollAccumulated.floatValue = next
                    }
                },
        ) {
            val gridState = rememberLazyGridState()
            ScrollToTopOnNewResults(results.query, gridState)
            DocGridCards(
                docs = results.items,
                wiring = wiring,
                selectionMode = selectionMode,
                selected = selected,
                cellWidth = width.roundToInt(),
                state = gridState,
            )

            VerticalScrollbar(gridState)
        }
    }
}

/**
 * The scrolling card grid itself, with [cellWidth] as the adaptive minimum cell width in dp.
 * Sizing decisions and zoom gestures stay in [DocGrid]; this only lays the cards out, keeping an
 * end gutter clear for the scrollbar.
 */
@Composable
private fun DocGridCards(
    docs: List<DocListItem>,
    wiring: DocItemWiring,
    selectionMode: Boolean,
    selected: Set<String>,
    cellWidth: Int,
    state: LazyGridState,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(cellWidth.dp),
        state = state,
        modifier =
            Modifier
                .fillMaxSize()
                .padding(start = GRID_H_PADDING_DP.dp, end = GRID_H_PADDING_DP.dp + ScrollbarWidth),
        horizontalArrangement = Arrangement.spacedBy(GRID_SPACING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(GRID_SPACING_DP.dp),
    ) {
        items(docs, key = { it.doc.docId }) { item ->
            DocGridCard(item, wiring, selectionMode, selected = item.doc.docId in selected)
        }
    }
}

@Composable
internal fun DocList(
    results: DocumentResults,
    wiring: DocItemWiring,
    selectionMode: Boolean,
    selected: Set<String>,
    landscapeColumns: Boolean,
) {
    val row: @Composable (DocListItem) -> Unit = { item ->
        DocListRow(item, wiring, selectionMode, selected = item.doc.docId in selected)
    }

    // Multi-column list when the option is on and the content area is landscape; otherwise the
    // classic single-column list. Two columns keeps each row wide enough to stay readable.
    // Rows keep an end gutter clear so their content never sits under the scrollbar.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (landscapeColumns && isLandscape) {
            val gridState = rememberLazyGridState()
            ScrollToTopOnNewResults(results.query, gridState)
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                modifier = Modifier.fillMaxSize().padding(end = ScrollbarWidth),
            ) {
                items(results.items, key = { it.doc.docId }) { item ->
                    Column {
                        row(item)
                        HorizontalDivider()
                    }
                }
            }
            VerticalScrollbar(gridState)
        } else {
            val listState = rememberLazyListState()
            ScrollToTopOnNewResults(results.query, listState)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(end = ScrollbarWidth),
            ) {
                items(results.items, key = { it.doc.docId }) { item ->
                    row(item)
                    HorizontalDivider()
                }
            }
            VerticalScrollbar(listState)
        }
    }
}
