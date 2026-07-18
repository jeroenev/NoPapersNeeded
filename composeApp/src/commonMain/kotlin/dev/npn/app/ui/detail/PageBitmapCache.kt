package dev.npn.app.ui.detail

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap

/** Base render dimension (px on the longest side) at zoom 1; multiplied by the zoom bucket. */
const val BASE_MAX_DIM = 1200

internal sealed interface PageState {
    data object Loading : PageState
    data class Ready(val bitmap: ImageBitmap) : PageState
    data object Failed : PageState
}

/**
 * Render resolution bucket for [zoom]: coarse steps so panning at a fixed zoom never re-renders,
 * but zooming in fetches progressively sharper pixels. Always >= 1.
 */
fun zoomBucket(zoom: Float): Int = when {
    zoom <= 1f -> 1
    zoom <= 2f -> 2
    zoom <= 3.5f -> 3
    else -> 4
}

/**
 * Small LRU of rendered page bitmaps keyed by (pageIndex, bucket), plus the on-screen "fitted" size
 * of each page so the transform math can clamp pan correctly. Holding a few entries lets the pager
 * prefetch neighbours (instant swipes) and lets a page keep showing a coarser cached bitmap while a
 * sharper bucket renders (no spinner flash mid-zoom).
 *
 * Not thread-safe; only touched from the composition/main thread.
 */
class PageBitmapCache(private val maxEntries: Int) {
    private data class Key(val page: Int, val bucket: Int)

    private val bitmaps = LinkedHashMap<Key, ImageBitmap>()
    // Intrinsic (pixel) size of the source bitmap per page, used to derive the fitted size.
    private val contentSizes = HashMap<Int, Size>()

    fun get(page: Int, bucket: Int): ImageBitmap? {
        val key = Key(page, bucket)
        val bmp = bitmaps.remove(key) ?: return null
        bitmaps[key] = bmp // move to most-recently-used
        return bmp
    }

    /** Any cached bitmap for [page], preferring the highest bucket, for instant display. */
    fun best(page: Int): ImageBitmap? =
        bitmaps.entries.filter { it.key.page == page }.maxByOrNull { it.key.bucket }?.value

    fun put(page: Int, bucket: Int, bitmap: ImageBitmap) {
        bitmaps[Key(page, bucket)] = bitmap
        while (bitmaps.size > maxEntries) {
            val oldest = bitmaps.keys.firstOrNull() ?: break
            bitmaps.remove(oldest)
        }
    }

    /** Record the source bitmap's intrinsic size the first time a page renders. */
    fun recordContentSize(page: Int, bitmap: ImageBitmap) {
        if (contentSizes[page] == null) {
            contentSizes[page] = Size(bitmap.width.toFloat(), bitmap.height.toFloat())
        }
    }

    /** The letterboxed on-screen size of [page] inside [container], or [Size.Zero] if unknown. */
    fun fittedSizeFor(page: Int, container: Size): Size {
        val content = contentSizes[page] ?: return Size.Zero
        return fittedContentSize(content, container)
    }
}
