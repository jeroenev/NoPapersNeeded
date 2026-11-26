package dev.npn.core.einvoice

import dev.npn.core.pdf.PdfFixtures

/** The e-invoice XML fixtures under `/einvoice`, and PDFs that carry them. */
object EInvoiceFixtures {
    fun xml(fixture: String): String =
        checkNotNull(javaClass.getResourceAsStream("/einvoice/$fixture")) { "missing fixture $fixture" }
            .use { it.readBytes() }
            .decodeToString()

    /**
     * A PDF carrying [fixture] as the attachment [attachmentName], after [edit]: an edit makes
     * another invoice of it, and so another document.
     */
    fun pdf(
        attachmentName: String,
        fixture: String,
        edit: (String) -> String = { it },
    ): ByteArray = PdfFixtures.pdfWithEmbeddedFile(attachmentName, edit(xml(fixture)).encodeToByteArray())
}
