package dev.npn.core.mail

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSString
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * A plain, readable PDF of one email: From, To, Date and Subject, then its plain-text body, wrapped
 * onto A4 pages. It is deliberately simple — no HTML, no inline images — so the document is text a
 * person can read and OCR can index. The bytes depend only on the message, so saving the same email
 * again is an import duplicate, not a second document.
 */
internal class PdfBoxMailPdfRenderer(
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : MailPdfRenderer {
    override val available: Boolean = true

    override suspend fun render(message: MailMessage): ByteArray? =
        withContext(io) {
            PDDocument().use { document ->
                val regular = PDType1Font(Standard14Fonts.FontName.HELVETICA)
                val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
                val writer = PageWriter(document, regular, bold)
                writer.line("From: ${message.from}", bold, SIZE + 1, GAP)
                writer.line("To: ${message.to}", regular, SIZE, GAP)
                writer.line("Date: ${formatDate(message.dateMillis)}", regular, SIZE, GAP)
                writer.line("Subject: ${message.subject}", bold, SIZE + 1, GAP * 1.5f)
                writer.paragraphs(message.bodyText.ifBlank { "(no plain-text body)" })
                writer.close()
                // A deterministic identity and metadata: saving the same email again must be an
                // import duplicate, not a new document seeded from the clock and a random /ID.
                document.documentInformation.creationDate = epoch()
                document.documentInformation.modificationDate = epoch()
                document.documentInformation.producer = "No Papers Needed"
                val id = COSString(pdfId(message))
                document.getDocument().trailer.setItem(
                    COSName.ID,
                    COSArray().apply {
                        add(id)
                        add(id)
                    },
                )
                val out = java.io.ByteArrayOutputStream()
                document.save(out)
                out.toByteArray()
            }
        }

    private fun formatDate(millis: Long?): String =
        millis?.let {
            DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm")
                .format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
        } ?: "unknown"

    /** A stable `/ID` from what the PDF shows, so the same message always hashes to the same bytes. */
    private fun pdfId(message: MailMessage): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in listOf(
            message.from,
            message.to,
            message.subject,
            message.bodyText,
            message.dateMillis?.toString().orEmpty(),
        )) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(bytes.size.toLong()).array())
            digest.update(bytes)
        }
        return digest.digest().copyOf(16)
    }

    /** The fixed timestamp the PDF's metadata carries, so a retried save has the same bytes. */
    private fun epoch(): Calendar = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply { timeInMillis = 0 }

    /** One page's content stream, replaced with a new page whenever the next line would not fit. */
    private class PageWriter(
        private val document: PDDocument,
        private val regular: PDFont,
        private val bold: PDFont,
    ) {
        private var page: PDPage = newPage()
        private var stream = PDPageContentStream(document, page)
        private var y: Float = PDRectangle.A4.height - MARGIN

        fun line(
            text: String,
            font: PDFont,
            size: Float,
            gap: Float,
        ) {
            wrap(text, font, size).forEach { part ->
                if (y - size < MARGIN) turnPage()
                stream.beginText()
                stream.setFont(font, size)
                stream.newLineAtOffset(MARGIN, y - size)
                stream.showText(safe(part, font))
                stream.endText()
                y -= gap
            }
        }

        fun paragraphs(text: String) {
            text.split('\n').forEach { raw ->
                val line = raw.trimEnd('\r')
                if (line.isBlank()) {
                    y -= GAP
                    if (y < MARGIN) turnPage()
                } else {
                    line(line, regular, SIZE, GAP)
                }
            }
        }

        fun close() = stream.close()

        private fun turnPage() {
            stream.close()
            page = newPage()
            stream = PDPageContentStream(document, page)
            y = PDRectangle.A4.height - MARGIN
        }

        private fun newPage(): PDPage = PDPage(PDRectangle.A4).also(document::addPage)
    }

    private companion object {
        const val MARGIN = 56f
        const val SIZE = 11f
        const val GAP = 15f
        const val MAX_WIDTH = 595f - 2 * MARGIN

        /** Break [text] into lines that fit [MAX_WIDTH]; a word too long for a line is split. */
        fun wrap(
            text: String,
            font: PDFont,
            size: Float,
        ): List<String> {
            if (text.isEmpty()) return listOf("")
            val lines = mutableListOf<String>()
            val current = StringBuilder()
            for (word in text.split(' ')) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (width(candidate, font, size) <= MAX_WIDTH) {
                    current.setLength(0)
                    current.append(candidate)
                    continue
                }
                if (current.isNotEmpty()) {
                    lines += current.toString()
                    current.setLength(0)
                }
                if (width(word, font, size) <= MAX_WIDTH) {
                    current.append(word)
                } else {
                    var rest = word
                    while (width(rest, font, size) > MAX_WIDTH) {
                        var take = rest.length
                        while (take > 1 && width(rest.take(take), font, size) > MAX_WIDTH) take--
                        lines += rest.take(take)
                        rest = rest.drop(take)
                    }
                    current.append(rest)
                }
            }
            if (current.isNotEmpty() || lines.isEmpty()) lines += current.toString()
            return lines
        }

        fun width(
            text: String,
            font: PDFont,
            size: Float,
        ): Float {
            val safe = safe(text, font)
            return try {
                font.getStringWidth(safe) / 1000f * size
            } catch (_: Exception) {
                // A character the font cannot measure makes the line as wide as its character count.
                safe.length * size * 0.6f
            }
        }

        /** Replace characters the standard font cannot encode with `?`; the body stays readable. */
        fun safe(
            text: String,
            font: PDFont,
        ): String =
            buildString(text.length) {
                for (char in text) {
                    try {
                        font.encode(char.toString())
                        append(char)
                    } catch (_: Exception) {
                        append('?')
                    }
                }
            }
    }
}
