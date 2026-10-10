package dev.npn.core.pdf

import java.nio.ByteBuffer
import java.security.MessageDigest

/** Length of a PDF file identifier, as PDFBox's own (an MD5) has. */
private const val ID_BYTES = 16

/**
 * The file identifier (the trailer's `/ID`) of the PDF [PdfEngine.imagesToPdf] makes from [images]:
 * the first bytes of a SHA-256 over the images in order, each preceded by its length. PDFBox seeds a
 * missing `/ID` from the clock, which would make the same images a different PDF, so a repeated or
 * retried share a second document instead of a duplicate.
 */
internal fun imagesPdfId(images: List<ByteArray>): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    for (image in images) {
        digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(image.size.toLong()).array())
        digest.update(image)
    }
    return digest.digest().copyOf(ID_BYTES)
}
