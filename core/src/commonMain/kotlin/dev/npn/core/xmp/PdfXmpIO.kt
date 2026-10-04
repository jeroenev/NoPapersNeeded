package dev.npn.core.xmp

/**
 * Platform bridge that moves raw XMP metadata bytes and document-info fields in and out of a
 * PDF. Implementations use PDFBox (desktop) / pdfbox-android (Android); no XMP generation or
 * parsing happens here (that is [XmpSerializer]/[XmpParser]'s job) - these only carry the raw
 * packet and set doc-info `Keywords`/`Title`.
 *
 * This is a plain interface, not an `expect`, so [BakeService] and tests can depend on it and
 * fakes can be substituted freely.
 */
interface PdfXmpIO {
    /** Return the raw XMP packet embedded in [pdfBytes], or null if there is none. */
    suspend fun readXmp(pdfBytes: ByteArray): String?

    /**
     * Return a new PDF: [xmp] set as the metadata stream, doc-info `Keywords` set to
     * [keywords] joined by ", " (omitted when empty) and doc-info `Title` set to [title]
     * (omitted when null). Must throw on invalid PDF input.
     */
    suspend fun writeBaked(
        pdfBytes: ByteArray,
        xmp: String,
        keywords: List<String>,
        title: String?,
    ): ByteArray
}
