package dev.npn.core.pdf

import kotlin.math.sqrt

/**
 * An opened PDF document. Backed by a platform PDF library (PDFBox 3.x on desktop,
 * pdfbox-android + android.graphics.pdf.PdfRenderer on Android). Callers must [close] the
 * handle to release native/temp resources.
 */
interface PdfDocumentHandle : AutoCloseable {
    val pageCount: Int

    /** Embedded text layer for [pageIndex] (0-based); "" when the page has no text layer. */
    fun extractText(pageIndex: Int): String

    /**
     * Render [pageIndex] (0-based) to a PNG image (aspect preserved). The longer side is at most
     * [maxDimensionPx] pixels, but the shorter side is never starved of resolution on very
     * elongated pages (see [pageRenderScale]).
     */
    fun renderPagePng(
        pageIndex: Int,
        maxDimensionPx: Int,
    ): ByteArray
}

/**
 * The widest aspect ratio (longer:shorter) treated as "normal" when picking a render scale. Pages
 * more elongated than this — long store receipts, for instance — are scaled by their shorter
 * (readable) side instead of their longer side, so the text axis keeps enough pixels and does not
 * turn blurry when the viewer fits the page to screen. 3:1 covers letter/A4 comfortably.
 */
private const val MAX_NORMAL_ASPECT = 3f

/**
 * Upper bound on rendered pixels (width x height) so a fix for elongated pages can never blow up
 * memory: a very tall receipt at a high zoom bucket could otherwise render a huge bitmap. Sized to
 * comfortably hold the largest normal-page render (longest side ~4800px x its shorter side).
 */
private const val MAX_RENDER_PIXELS = 4800f * 2600f

/**
 * Scale factor (px per PDF point, user space is 72 DPI) to render a [widthPoints] x [heightPoints]
 * page at.
 *
 * For pages up to [MAX_NORMAL_ASPECT] the longer side is put at [maxDimensionPx]. For more
 * elongated pages the *shorter* side is instead put at `maxDimensionPx / MAX_NORMAL_ASPECT`, which
 * bumps the resolution along the readable axis (a tall receipt would otherwise get a very
 * low-resolution width and read blurry). Equivalent to capping the aspect ratio used for scaling.
 * The result is finally capped so total rendered pixels stay under [MAX_RENDER_PIXELS].
 */
fun pageRenderScale(
    widthPoints: Float,
    heightPoints: Float,
    maxDimensionPx: Int,
): Float {
    val longer = maxOf(widthPoints, heightPoints).coerceAtLeast(1f)
    val shorter = minOf(widthPoints, heightPoints).coerceAtLeast(1f)
    val byLonger = maxDimensionPx.toFloat() / longer
    val byShorter = (maxDimensionPx.toFloat() / MAX_NORMAL_ASPECT) / shorter
    // maxOf: for normal pages byLonger wins; for elongated pages byShorter is larger and lifts the
    // resolution so the shorter side is not starved.
    val scale = maxOf(byLonger, byShorter)
    // Cap total pixels; kotlin.math.sqrt keeps the aspect ratio while bounding the area.
    val pixelCap = sqrt(MAX_RENDER_PIXELS / (longer * shorter))
    return minOf(scale, pixelCap).coerceAtLeast(0.01f)
}

/**
 * Platform PDF engine. The interface allows the desktop PDFBox 3.x and Android
 * pdfbox-android/PdfRenderer implementations to differ.
 */
interface PdfEngine {
    /** Open [bytes] as a PDF; throws [PdfOpenException] on an invalid/corrupt file. */
    suspend fun open(bytes: ByteArray): PdfDocumentHandle

    /** Combine JPEG/PNG page images into a single PDF (one image per page). */
    suspend fun imagesToPdf(images: List<ByteArray>): ByteArray

    /**
     * Build a new PDF containing exactly the pages of [bytes] at [pageIndices] (0-based, in the
     * given order). Throws [PdfOpenException] on an invalid source and [IllegalArgumentException]
     * on an empty or out-of-range index list. The source bytes are never modified.
     */
    suspend fun extractPages(
        bytes: ByteArray,
        pageIndices: List<Int>,
    ): ByteArray
}

class PdfOpenException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** JPEG magic bytes: FF D8 FF. */
internal fun isJpeg(bytes: ByteArray): Boolean =
    bytes.size >= 3 &&
        bytes[0] == 0xFF.toByte() &&
        bytes[1] == 0xD8.toByte() &&
        bytes[2] == 0xFF.toByte()

/** PNG magic bytes: 89 50 4E 47 0D 0A 1A 0A. */
internal fun isPng(bytes: ByteArray): Boolean =
    bytes.size >= 8 &&
        bytes[0] == 0x89.toByte() &&
        bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() &&
        bytes[3] == 0x47.toByte()

/** PDF magic bytes: %PDF (25 50 44 46). */
internal fun looksLikePdf(bytes: ByteArray): Boolean =
    bytes.size >= 4 &&
        bytes[0] == 0x25.toByte() &&
        bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x44.toByte() &&
        bytes[3] == 0x46.toByte()
