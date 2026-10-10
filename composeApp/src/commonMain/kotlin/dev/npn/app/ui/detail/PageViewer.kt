package dev.npn.app.ui.detail

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.detectDragWhenZoomed
import dev.npn.app.ui.common.detectPinchTransform
import dev.npn.app.ui.common.viewerScroll
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_cancel
import no_papers_needed.composeapp.generated.resources.common_retry
import no_papers_needed.composeapp.generated.resources.detail_go
import no_papers_needed.composeapp.generated.resources.detail_go_to_page
import no_papers_needed.composeapp.generated.resources.detail_next_page
import no_papers_needed.composeapp.generated.resources.detail_no_pages
import no_papers_needed.composeapp.generated.resources.detail_page_number
import no_papers_needed.composeapp.generated.resources.detail_page_position
import no_papers_needed.composeapp.generated.resources.detail_page_range
import no_papers_needed.composeapp.generated.resources.detail_pages_failed
import no_papers_needed.composeapp.generated.resources.detail_previous_page
import no_papers_needed.composeapp.generated.resources.detail_render_page_failed
import no_papers_needed.composeapp.generated.resources.detail_zoom_in
import no_papers_needed.composeapp.generated.resources.detail_zoom_out
import no_papers_needed.composeapp.generated.resources.detail_zoom_percent
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * PDF page viewer. Pages live in a [HorizontalPager] (swipe/keys/wheel between them); the current
 * page supports centroid-anchored pinch/ctrl-scroll zoom, clamped pan, and double-tap toggle. Each
 * page is rendered to PNG via [render] at a resolution bucketed off the current zoom, cached in a
 * small LRU (with ±1 prefetch) so swipes are instant and re-rendering never blanks the page.
 *
 * Zoom is transient UI state owned here (it need not survive process death); [pageIndex] /
 * [onPageChange] keep the ViewModel as the source of truth for the current page so external
 * controls (prev/next, go-to) stay in sync.
 */
@Composable
fun PageViewer(
    pageIndex: Int,
    pageCount: PageCountState,
    render: suspend (pageIndex: Int, maxDim: Int) -> ByteArray?,
    onPageChange: (Int) -> Unit,
    onRetryPageCount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = pageCount as? PageCountState.Ready
    if (ready == null) {
        PageCountPlaceholder(pageCount, onRetryPageCount, modifier)
        return
    }
    val count = ready.count

    val cache = remember { PageBitmapCache(maxEntries = 6) }
    val pagerState = rememberPagerState(initialPage = pageIndex.coerceIn(0, count - 1)) { count }
    val scope = rememberCoroutineScope()

    // Keep pager and the external page index in sync, both directions: settled pager page -> VM;
    // VM page (prev/next/go-to) -> animate the pager. The VM ignores a page it already holds, so
    // reporting every settle cannot loop, and comparing against [pageIndex] here would read the
    // value this effect started with.
    val reportPage by rememberUpdatedState(onPageChange)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { reportPage(it) }
    }
    LaunchedEffect(pageIndex) {
        if (pageIndex != pagerState.currentPage) pagerState.animateScrollToPage(pageIndex)
    }

    var showGoToPage by remember { mutableStateOf(false) }
    var containerSize by remember { mutableStateOf(Size.Zero) }

    // The interactive page is whichever the pager currently shows; its transform is owned per-page
    // so zooming one page and swiping away resets cleanly.
    val current = pagerState.currentPage
    val transforms = remember { mutableStateMapOf<Int, ViewerTransform>() }

    fun transformOf(page: Int) = transforms[page] ?: ViewerTransform()

    fun setTransform(
        page: Int,
        t: ViewerTransform,
    ) {
        transforms[page] = t
    }

    // The scroll/zoom gesture lambdas below are captured once by pointerInput and never refreshed,
    // so they must read the current page through State, not the composition-time `current` local
    // (otherwise every wheel flip is computed from page 0 and paging stalls after the first step).
    val currentPage = rememberUpdatedState(current)

    fun goToPage(target: Int) {
        scope.launch { pagerState.animateScrollToPage(target.coerceIn(0, count - 1)) }
    }

    // Accumulates fractional touchpad scroll toward one page flip when at 1x (a mouse wheel notch
    // arrives as a single event with |delta| >= 1 and flips immediately).
    val pageScrollAccum = remember { mutableStateOf(0f) }

    Box(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .onSizeChanged { containerSize = Size(it.width.toFloat(), it.height.toFloat()) }
                .viewerKeys(
                    pagerState = pagerState,
                    pageCount = count,
                    transform = { transformOf(currentPage.value) },
                    onTransform = { setTransform(currentPage.value, it) },
                    containerSize = { containerSize },
                    fittedSize = { cache.fittedSizeFor(currentPage.value, containerSize) },
                    onPage = { goToPage(it) },
                ).viewerScroll(
                    // Ctrl+scroll: cursor-anchored zoom.
                    onZoom = { delta, position ->
                        val page = currentPage.value
                        val fitted = cache.fittedSizeFor(page, containerSize)
                        setTransform(
                            page,
                            transformOf(page).applyGesture(
                                zoomChange = 1f - delta * CTRL_SCROLL_ZOOM_STEP,
                                pan = Offset.Zero,
                                centroid = position,
                                fittedSize = fitted,
                                container = containerSize,
                            ),
                        )
                    },
                    // Plain wheel: pan vertically when zoomed, otherwise page up/down (PDF-reader style).
                    onScroll = { delta, _ ->
                        val page = currentPage.value
                        val t = transformOf(page)
                        if (t.isZoomed) {
                            val fitted = cache.fittedSizeFor(page, containerSize)
                            setTransform(
                                page,
                                t.copy(
                                    offset =
                                        clampPan(
                                            t.offset - Offset(0f, delta * WHEEL_PAN_STEP),
                                            fitted,
                                            containerSize,
                                            t.zoom,
                                        ),
                                ),
                            )
                            true
                        } else {
                            val acc = pageScrollAccum.value
                            val sameDir = acc == 0f || (acc > 0f) == (delta > 0f)
                            val next = if (sameDir) acc + delta else delta
                            if (kotlin.math.abs(next) >= 1f) {
                                goToPage(page + if (next > 0f) 1 else -1)
                                pageScrollAccum.value = 0f
                            } else {
                                pageScrollAccum.value = next
                            }
                            true
                        }
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            // While zoomed, drag belongs to pan until the page hits its horizontal edge.
            userScrollEnabled =
                transformOf(current)
                    .canPagerConsumeHorizontal(cache.fittedSizeFor(current, containerSize), containerSize),
            beyondViewportPageCount = 1,
        ) { page ->
            PageSlide(
                page = page,
                isCurrent = page == current,
                render = render,
                cache = cache,
                container = containerSize,
                zoom = if (page == current) transformOf(current).zoom else 1f,
                transform = if (page == current) transformOf(current) else ViewerTransform(),
                onTransformChange = { setTransform(page, it) },
            )
        }

        fun zoomButton(delta: Float) {
            val fitted = cache.fittedSizeFor(current, containerSize)
            setTransform(current, zoomAtCenter(transformOf(current), delta, containerSize, fitted))
        }
        ZoomControls(
            transform = transformOf(current),
            onZoomOut = { zoomButton(-ZOOM_STEP) },
            onZoomIn = { zoomButton(ZOOM_STEP) },
            onReset = { setTransform(current, ViewerTransform()) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
        )

        if (count > 1) {
            PageIndicator(
                pageIndex = current,
                pageCount = count,
                onPrev = { goToPage(current - 1) },
                onNext = { goToPage(current + 1) },
                onClick = { showGoToPage = true },
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            )
        }
    }

    if (showGoToPage) {
        GoToPageDialog(
            pageCount = count,
            current = current,
            onGo = {
                showGoToPage = false
                scope.launch { pagerState.animateScrollToPage(it) }
            },
            onDismiss = { showGoToPage = false },
        )
    }
}

/**
 * What the viewer shows when there is no page to page through: a spinner while the count resolves,
 * a message for a document with no pages, and a retry for a count that could not be read (a corrupt
 * or unreadable file). Kept apart so a count that failed never spins forever.
 */
@Composable
private fun PageCountPlaceholder(
    state: PageCountState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) {
        when (state) {
            // Handled by the caller; never reaches here.
            is PageCountState.Ready -> {
                return@Box
            }

            PageCountState.Loading -> {
                CircularProgressIndicator()
            }

            PageCountState.Empty -> {
                Text(
                    stringResource(Res.string.detail_no_pages),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            PageCountState.Failed -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(Res.string.detail_pages_failed),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onRetry) { Text(stringResource(Res.string.common_retry)) }
                }
            }
        }
    }
}

private const val CTRL_SCROLL_ZOOM_STEP = 0.1f

// Pixels of pan per unit of plain-wheel scroll delta when zoomed in.
private const val WHEEL_PAN_STEP = 60f

/**
 * A single page in the pager. Renders the cached bitmap (loading it if absent), applies the
 * transform through a graphics layer scaled about the page's centre (as [ViewerTransform]'s math
 * assumes), and wires up pinch + double-tap on the current page only.
 */
@Composable
private fun PageSlide(
    page: Int,
    isCurrent: Boolean,
    render: suspend (pageIndex: Int, maxDim: Int) -> ByteArray?,
    cache: PageBitmapCache,
    container: Size,
    zoom: Float,
    transform: ViewerTransform,
    onTransformChange: (ViewerTransform) -> Unit,
) {
    val bucket = zoomBucket(zoom)
    var state by remember(page) { mutableStateOf<PageState>(PageState.Loading) }
    // Bumped by the retry action so a failed render re-runs the effect below without changing the
    // page or bucket.
    var renderAttempt by remember(page) { mutableStateOf(0) }

    // Gesture detectors below are captured once by pointerInput and never refreshed, so they must
    // read the live transform and viewer size through these States rather than the captured
    // parameters (otherwise a second double-tap sees the stale zoom=1 and re-zooms instead of
    // resetting, and a drag after a resize or rotation clamps against the old size).
    val liveTransform = rememberUpdatedState(transform)
    val liveContainer = rememberUpdatedState(container)

    LaunchedEffect(page, bucket, isCurrent, renderAttempt) {
        // Show any cached bitmap immediately (even at a coarser bucket) to avoid a spinner flash.
        cache.best(page)?.let { state = PageState.Ready(it) }
        val existing = cache.get(page, bucket)
        if (existing != null) {
            state = PageState.Ready(existing)
        } else {
            val bytes = runCatchingCancellable { render(page, BASE_MAX_DIM * bucket) }.getOrNull()
            // Decode the possibly-large PNG off the composition/main thread.
            val bmp =
                bytes?.let {
                    runCatchingCancellable { withContext(Dispatchers.Default) { it.decodeToImageBitmap() } }.getOrNull()
                }
            if (bmp != null) {
                cache.put(page, bucket, bmp)
                state = PageState.Ready(bmp)
            } else if (cache.best(page) == null) {
                state = PageState.Failed
            }
        }
    }

    val gestures =
        if (isCurrent) {
            Modifier.pageGestures(page, liveContainer, cache, liveTransform, onTransformChange)
        } else {
            Modifier
        }

    Box(
        Modifier.fillMaxSize().then(gestures),
        contentAlignment = Alignment.Center,
    ) {
        when (val s = state) {
            is PageState.Ready -> {
                Image(
                    bitmap = s.bitmap,
                    contentDescription = stringResource(Res.string.detail_page_number, page + 1),
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .onSizeChanged { cache.recordContentSize(page, s.bitmap) }
                            .graphicsLayer {
                                transformOrigin = TransformOrigin(0.5f, 0.5f)
                                scaleX = transform.zoom
                                scaleY = transform.zoom
                                translationX = transform.offset.x
                                translationY = transform.offset.y
                            },
                    contentScale = ContentScale.Fit,
                )
            }

            PageState.Loading -> {
                CircularProgressIndicator()
            }

            PageState.Failed -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(Res.string.detail_render_page_failed),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            state = PageState.Loading
                            renderAttempt++
                        },
                    ) {
                        Text(stringResource(Res.string.common_retry))
                    }
                }
            }
        }
    }
}

/**
 * Touch/mouse gestures for the current page: double-tap toggle zoom, multitouch pinch, and
 * drag-to-pan (only while zoomed, so at 1x drags fall through to the pager for swipe paging). All
 * read [liveTransform] and [liveContainer] rather than captured values so they reflect the latest
 * zoom/offset and the viewer's size now, after a resize, a rotation or a pane drag.
 */
private fun Modifier.pageGestures(
    page: Int,
    liveContainer: State<Size>,
    cache: PageBitmapCache,
    liveTransform: State<ViewerTransform>,
    onTransformChange: (ViewerTransform) -> Unit,
): Modifier =
    this
        .pointerInput(page) {
            detectTapGestures(
                onDoubleTap = { tap ->
                    val container = liveContainer.value
                    onTransformChange(
                        liveTransform.value.toggleZoom(tap, cache.fittedSizeFor(page, container), container),
                    )
                },
            )
        }.pointerInput(page) {
            detectPinchTransform { centroid, gestureZoom ->
                val container = liveContainer.value
                onTransformChange(
                    liveTransform.value.applyGesture(
                        zoomChange = gestureZoom,
                        pan = Offset.Zero,
                        centroid = centroid,
                        fittedSize = cache.fittedSizeFor(page, container),
                        container = container,
                    ),
                )
            }
        }.pointerInput(page) {
            detectDragWhenZoomed(isZoomed = { liveTransform.value.isZoomed }) { pan ->
                val container = liveContainer.value
                val t = liveTransform.value
                onTransformChange(
                    t.copy(
                        offset = clampPan(t.offset + pan, cache.fittedSizeFor(page, container), container, t.zoom),
                    ),
                )
            }
        }

@Composable
private fun ZoomControls(
    transform: ViewerTransform,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onZoomOut, enabled = transform.zoom > MIN_ZOOM) {
                Icon(Icons.Filled.ZoomOut, contentDescription = stringResource(Res.string.detail_zoom_out))
            }
            TextButton(onClick = onReset, enabled = transform.isZoomed) {
                Text(stringResource(Res.string.detail_zoom_percent, (transform.zoom * 100).roundToInt()))
            }
            IconButton(onClick = onZoomIn, enabled = transform.zoom < MAX_ZOOM) {
                Icon(Icons.Filled.ZoomIn, contentDescription = stringResource(Res.string.detail_zoom_in))
            }
        }
    }
}

@Composable
private fun PageIndicator(
    pageIndex: Int,
    pageCount: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrev, enabled = pageIndex > 0) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(Res.string.detail_previous_page),
                )
            }
            TextButton(onClick = onClick) {
                Text(
                    stringResource(Res.string.detail_page_position, pageIndex + 1, pageCount),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            IconButton(onClick = onNext, enabled = pageIndex < pageCount - 1) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(Res.string.detail_next_page),
                )
            }
        }
    }
}

@Composable
private fun GoToPageDialog(
    pageCount: Int,
    current: Int,
    onGo: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf((current + 1).toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in 1..pageCount
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.detail_go_to_page)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit) },
                label = { Text(stringResource(Res.string.detail_page_range, pageCount)) },
                singleLine = true,
                isError = text.isNotEmpty() && !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onGo(parsed!! - 1) }) {
                Text(stringResource(Res.string.detail_go))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
}
