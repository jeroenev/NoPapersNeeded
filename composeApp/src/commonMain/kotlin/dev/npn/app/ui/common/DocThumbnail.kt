package dev.npn.app.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.npn.core.app.DocContentFacade
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private sealed interface ThumbState {
    data object Loading : ThumbState

    data class Loaded(
        val bitmap: ImageBitmap,
    ) : ThumbState

    data object Failed : ThumbState
}

/**
 * Process-wide LRU cache of decoded thumbnail [ImageBitmap]s keyed by docId. Avoids re-reading
 * and re-decoding the cached PNG from disk every time an item scrolls back into view, which
 * otherwise adds needless IO/decoding load during fast scrolling over a large library. The cap is
 * platform-dependent (see [thumbCacheMaxEntries]); the cache is cleared on library close.
 */
private val thumbBitmapCache =
    object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, ImageBitmap>): Boolean = size > thumbCacheMaxEntries()
    }

private val thumbCacheMutex = Mutex()

private suspend fun cachedThumb(docId: String): ImageBitmap? = thumbCacheMutex.withLock { thumbBitmapCache[docId] }

private suspend fun putThumb(
    docId: String,
    bitmap: ImageBitmap,
) {
    thumbCacheMutex.withLock { thumbBitmapCache[docId] = bitmap }
}

/** Drop every decoded thumbnail. Called when a library is closed so the next library starts fresh. */
suspend fun clearThumbCache() {
    thumbCacheMutex.withLock { thumbBitmapCache.clear() }
}

/**
 * Renders a document thumbnail. Resolves the cache path via [content], decodes the file bytes
 * in a platform actual, and shows a placeholder icon while loading or on failure.
 */
@Composable
fun DocThumbnail(
    docId: String,
    content: DocContentFacade,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(4.dp),
) {
    val state by produceState<ThumbState>(ThumbState.Loading, docId) {
        // Serve from the in-memory cache instantly on re-scroll without touching disk.
        cachedThumb(docId)?.let {
            value = ThumbState.Loaded(it)
            return@produceState
        }
        value = ThumbState.Loading
        val path = runCatching { content.thumbnailPath(docId) }.getOrNull()
        value =
            if (path == null) {
                ThumbState.Failed
            } else {
                val bmp = loadImageBitmap(path)
                if (bmp != null) {
                    putThumb(docId, bmp)
                    ThumbState.Loaded(bmp)
                } else {
                    ThumbState.Failed
                }
            }
    }

    Box(
        modifier =
            modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when (val s = state) {
            is ThumbState.Loaded -> {
                Image(
                    bitmap = s.bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }

            else -> {
                Icon(
                    imageVector = Icons.Filled.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(size / 2),
                )
            }
        }
    }
}
