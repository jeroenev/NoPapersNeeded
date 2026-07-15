package dev.npn.core.pdf

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
     * Render [pageIndex] (0-based) to a PNG image whose longer side is at most
     * [maxDimensionPx] pixels (aspect preserved).
     */
    fun renderPagePng(pageIndex: Int, maxDimensionPx: Int): ByteArray
}

/**
 * Platform PDF engine (PLAN.md sec. 5, "OCR pipeline" / viewer). Behind an interface so a
 * desktop PDFBox 3.x actual and an Android pdfbox-android/PdfRenderer actual can differ.
 */
interface PdfEngine {
    /** Open [bytes] as a PDF; throws [PdfOpenException] on an invalid/corrupt file. */
    suspend fun open(bytes: ByteArray): PdfDocumentHandle

    suspend fun isValidPdf(bytes: ByteArray): Boolean

    /** Combine JPEG/PNG page images into a single PDF (one image per page). */
    suspend fun imagesToPdf(images: List<ByteArray>): ByteArray
}

class PdfOpenException(message: String, cause: Throwable? = null) : Exception(message, cause)

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
