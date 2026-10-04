package dev.npn.core.pdf

/** The text side of an Android PDF handle: pdfbox-android's parse of the document. */
internal interface PdfTextBackend : AutoCloseable {
    val pageCount: Int

    fun extractText(pageIndex: Int): String
}

/** The image side of an Android PDF handle: the platform renderer, over a staged copy of the file. */
internal interface PdfPageBackend : AutoCloseable {
    val pageCount: Int

    fun renderPagePng(
        pageIndex: Int,
        maxDimensionPx: Int,
    ): ByteArray
}

/**
 * A [PdfDocumentHandle] that opens each backend the first time a call needs it. Drawing a page
 * stages the file and opens the renderer without parsing it; reading text parses it without staging
 * a copy. A page count comes from whichever backend is open, else from the parse, which needs no
 * copy. Like the platform classes behind it, a handle is not for concurrent use.
 */
internal class LazyPdfDocumentHandle(
    private val openText: () -> PdfTextBackend,
    private val openPages: () -> PdfPageBackend,
) : PdfDocumentHandle {
    private var text: PdfTextBackend? = null
    private var pages: PdfPageBackend? = null
    private var closed = false

    override val pageCount: Int get() = pages?.pageCount ?: text().pageCount

    override fun extractText(pageIndex: Int): String = text().extractText(pageIndex)

    override fun renderPagePng(
        pageIndex: Int,
        maxDimensionPx: Int,
    ): ByteArray = pages().renderPagePng(pageIndex, maxDimensionPx)

    override fun renderPagePngOrNull(
        pageIndex: Int,
        maxDimensionPx: Int,
    ): ByteArray? {
        val pages = pages()
        return if (pageIndex in 0 until pages.pageCount) pages.renderPagePng(pageIndex, maxDimensionPx) else null
    }

    private fun text(): PdfTextBackend {
        check(!closed) { "PDF handle is closed" }
        return text ?: openText().also { text = it }
    }

    private fun pages(): PdfPageBackend {
        check(!closed) { "PDF handle is closed" }
        return pages ?: openPages().also { pages = it }
    }

    /** Close whichever backends were opened; one failing to close does not keep the other open. */
    override fun close() {
        if (closed) return
        closed = true
        try {
            pages?.close()
        } finally {
            text?.close()
        }
    }
}
