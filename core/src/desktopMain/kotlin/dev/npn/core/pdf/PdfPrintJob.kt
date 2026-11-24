package dev.npn.core.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.printing.PDFPageable
import java.awt.print.Pageable

/**
 * A document's pages as an AWT [Pageable] for printing, with the [PDDocument] it renders from held
 * for as long as the job draws. The bytes it was opened from are never written.
 *
 * Desktop-only, beside [PdfBoxEngine], so the UI module prints through `core.pdf` like it reads
 * every other PDF.
 */
class PdfPrintJob internal constructor(
    private val document: PDDocument,
) : AutoCloseable {
    val pages: Pageable = PDFPageable(document)

    val pageCount: Int get() = pages.numberOfPages

    override fun close() = document.close()
}

/** Opens [bytes] for printing; throws when they are not a readable PDF. */
fun pdfPrintJob(bytes: ByteArray): PdfPrintJob = PdfPrintJob(Loader.loadPDF(bytes))
