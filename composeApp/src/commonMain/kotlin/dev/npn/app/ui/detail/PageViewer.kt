package dev.npn.app.ui.detail

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.decodeToImageBitmap

private sealed interface PageState {
    data object Loading : PageState
    data class Ready(val bitmap: ImageBitmap) : PageState
    data object Failed : PageState
}

/**
 * PDF page viewer. Renders the current page to PNG via [render] (off the main thread in the facade),
 * decodes it with the common compose-resources decoder, and shows it with pinch/scroll zoom and pan.
 * A modest per-page cache (rendered at a scale derived from the current zoom bucket) avoids
 * re-rendering while the user pans; the page is re-rendered only when the page index or zoom bucket
 * changes.
 */
@Composable
fun PageViewer(
    pageIndex: Int,
    pageCount: Int,
    zoom: Float,
    render: suspend (pageIndex: Int, maxDim: Int) -> ByteArray?,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onSetZoom: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Render bucket: bump the render resolution in steps so we get crisper pixels as the user zooms
    // in, without re-rendering on every fractional zoom change.
    val bucket = zoom.coerceAtLeast(1f).let { if (it <= 1f) 1 else if (it <= 2f) 2 else if (it <= 3.5f) 3 else 4 }
    val maxDim = 1200 * bucket

    val cache = remember { mutableStateOf<Triple<Int, Int, ImageBitmap>?>(null) }
    var state by remember { mutableStateOf<PageState>(PageState.Loading) }

    LaunchedEffect(pageIndex, bucket, pageCount) {
        val cached = cache.value
        if (cached != null && cached.first == pageIndex && cached.second == bucket) {
            state = PageState.Ready(cached.third)
            return@LaunchedEffect
        }
        state = PageState.Loading
        val bytes = runCatching { render(pageIndex, maxDim) }.getOrNull()
        val bmp = bytes?.let { runCatching { it.decodeToImageBitmap() }.getOrNull() }
        state = if (bmp != null) {
            cache.value = Triple(pageIndex, bucket, bmp)
            PageState.Ready(bmp)
        } else {
            PageState.Failed
        }
    }

    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    // Reset pan whenever the page changes.
    LaunchedEffect(pageIndex) {
        offsetX = 0f
        offsetY = 0f
    }

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(pageIndex) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    if (gestureZoom != 1f) onSetZoom(zoom * gestureZoom)
                    if (zoom > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (val s = state) {
            is PageState.Ready -> Image(
                bitmap = s.bitmap,
                contentDescription = "Page ${pageIndex + 1}",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = zoom,
                        scaleY = zoom,
                        translationX = if (zoom > 1f) offsetX else 0f,
                        translationY = if (zoom > 1f) offsetY else 0f,
                    ),
                contentScale = ContentScale.Fit,
            )
            PageState.Loading -> CircularProgressIndicator()
            PageState.Failed -> Text(
                "Could not render this page.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Zoom controls (bottom-right).
        Surface(
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 3.dp,
        ) {
            Row {
                IconButton(onClick = onZoomOut, enabled = zoom > 1f) {
                    Icon(Icons.Filled.ZoomOut, contentDescription = "Zoom out")
                }
                IconButton(onClick = onZoomIn, enabled = zoom < 5f) {
                    Icon(Icons.Filled.ZoomIn, contentDescription = "Zoom in")
                }
            }
        }

        // Page navigation (bottom-center).
        if (pageCount > 1) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 3.dp,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(onClick = onPrev, enabled = pageIndex > 0) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous page")
                    }
                    Text(
                        "${pageIndex + 1} / $pageCount",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    IconButton(onClick = onNext, enabled = pageIndex < pageCount - 1) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next page")
                    }
                }
            }
        }
    }
}
