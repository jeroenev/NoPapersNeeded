package dev.npn.core.pdf

import kotlin.math.sqrt

/**
 * An opened PDF document. Backed by a platform PDF library (PDFBox 3.x on desktop,
 * pdfbox-android + android.graphics.pdf.PdfRenderer on Android). Callers must [close] the
 * handle to release native/temp resources.
 */
interface PdfDocumentHandle : AutoCloseable {
    val pageCount: Int

    /** The document-info `CreationDate`, in epoch millis; null when the PDF names none or a bad one. */
    val creationDate: Long?

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

    /**
     * [renderPagePng], or null when the document has no page [pageIndex]. A platform that renders
     * with a separate backend answers the bounds from that backend, so drawing a page never needs
     * the parse a [pageCount] alone could cost.
     */
    fun renderPagePngOrNull(
        pageIndex: Int,
        maxDimensionPx: Int,
    ): ByteArray? = if (pageIndex in 0 until pageCount) renderPagePng(pageIndex, maxDimensionPx) else null
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
    /**
     * Open [bytes] as a PDF; throws [PdfOpenException] on an invalid/corrupt file. A platform that
     * opens its backends lazily (Android) throws it from the first handle call that needs one.
     */
    suspend fun open(bytes: ByteArray): PdfDocumentHandle

    /**
     * The XML files embedded in [bytes] (PDF/A-3 attachments), for structured e-invoice detection.
     * Empty when the document has none; attachments that are not XML, or are too large to be an
     * invoice, are left out rather than read.
     */
    suspend fun embeddedXmlFiles(bytes: ByteArray): List<EmbeddedXmlFile> = emptyList()

    /**
     * Combine JPEG/PNG page images into a single PDF (one image per page). The bytes depend only on
     * [images], so converting the same images again makes a duplicate, not another document.
     */
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

/** One XML attachment a PDF carries (a PDF/A-3 embedded file), by its name and bytes. */
class EmbeddedXmlFile(
    val name: String,
    val bytes: ByteArray,
)

/**
 * Largest embedded XML attachment read for e-invoice detection. A structured invoice is kilobytes;
 * a larger file is not one, and reading it would only cost memory.
 */
const val MAX_EMBEDDED_XML_BYTES = 8 * 1024 * 1024

/** JPEG magic bytes: FF D8 FF. */
internal fun isJpeg(bytes: ByteArray): Boolean =
    bytes.size >= 3 &&
        bytes[0] == 0xFF.toByte() &&
        bytes[1] == 0xD8.toByte() &&
        bytes[2] == 0xFF.toByte()
