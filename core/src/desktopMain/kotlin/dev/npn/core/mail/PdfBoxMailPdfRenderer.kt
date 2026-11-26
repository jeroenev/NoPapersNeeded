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
import org.apache.pdfbox.pdmodel.font.PDType0Font
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * A plain, readable PDF of one email: From, To, Date and Subject, then its body as text (the text
 * of its HTML when it has no plain text), wrapped onto A4 pages. It is deliberately simple, with no
 * layout and no inline images, so the document is text a person can read and search can index.
 * The text is set in the bundled Noto Sans (Latin, Greek and Cyrillic), embedded as a subset; a
 * character it has no glyph for (CJK, emoji) shows as `?`, while a tab is spaced and other control
 * and formatting characters are left out. The date is in [zone], this device's, with its offset
 * shown. The bytes depend only on the message, the zone and the bundled font, so saving the same
 * email again here is an import duplicate, not a new document; across devices and builds the
 * email's Message-ID is what tells it was saved before.
 */
internal class PdfBoxMailPdfRenderer(
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : MailPdfRenderer {
    override val available: Boolean = true

    override suspend fun render(message: MailMessage): ByteArray? =
        withContext(io) {
            PDDocument().use { document ->
                val regular = Typeface(PDType0Font.load(document, ByteArrayInputStream(REGULAR), true))
                val bold = Typeface(PDType0Font.load(document, ByteArrayInputStream(BOLD), true))
                val writer = PageWriter(document, regular)
                writer.line("From: ${printable(message.from)}", bold, SIZE + 1, GAP)
                writer.line("To: ${printable(message.to)}", regular, SIZE, GAP)
                writer.line("Date: ${formatDate(message.dateMillis)}", regular, SIZE, GAP)
                writer.line("Subject: ${printable(message.subject)}", bold, SIZE + 1, GAP * 1.5f)
                writer.paragraphs(printable(message.bodyText).ifBlank { "(no text)" })
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

    /** The sending time in this device's zone, with its offset from UTC, so it is never ambiguous. */
    private fun formatDate(millis: Long?): String =
        millis?.let {
            DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm 'UTC'xxx", Locale.ROOT)
                .format(Instant.ofEpochMilli(it).atZone(zone()))
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

    /**
     * A font and what it can show. Each code point is asked once: a font refuses one it has no glyph
     * for by throwing, which over a long body in another script would be slow. Widths come per code
     * point, so a line is measured in one pass whatever its length.
     */
    private class Typeface(
        val font: PDFont,
    ) {
        private val widths = HashMap<Int, Float>()

        /** [text] with every character the font cannot show as `?`. */
        fun safe(text: String): String =
            buildString(text.length) {
                text.codePoints().forEach { codePoint ->
                    if (width(codePoint) != null) appendCodePoint(codePoint) else append('?')
                }
            }

        /** One code point's width in thousandths of the font size; null when the font cannot show it. */
        fun width(codePoint: Int): Float? {
            widths[codePoint]?.let { known -> return known.takeIf { it >= 0f } }
            val char = String(Character.toChars(codePoint))
            val width =
                try {
                    font.encode(char)
                    font.getStringWidth(char)
                } catch (_: Exception) {
                    -1f
                }
            widths[codePoint] = width
            return width.takeIf { it >= 0f }
        }

        /** The width of text [safe] already passed, at [size] points. */
        fun width(
            text: String,
            size: Float,
        ): Float {
            var total = 0f
            text.codePoints().forEach { total += width(it) ?: 0f }
            return total / 1000f * size
        }
    }

    /** One page's content stream, replaced with a new page whenever the next line would not fit. */
    private class PageWriter(
        private val document: PDDocument,
        private val regular: Typeface,
    ) {
        private var page: PDPage = newPage()
        private var stream = PDPageContentStream(document, page)
        private var y: Float = PDRectangle.A4.height - MARGIN

        fun line(
            text: String,
            face: Typeface,
            size: Float,
            gap: Float,
        ) {
            wrap(face.safe(text), face, size).forEach { part ->
                if (y - size < MARGIN) turnPage()
                stream.beginText()
                stream.setFont(face.font, size)
                stream.newLineAtOffset(MARGIN, y - size)
                stream.showText(part)
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
        /** Spaces a tab stands for: no font draws a tab, and the email PDF has no tab stops. */
        const val TAB = "    "

        /**
         * [text] as the PDF can show it: a tab as spaces, a line break kept, and every other control
         * or formatting character (a form feed, a zero-width space, a byte-order mark) left out, as
         * no font has a glyph for one and it would print as `?`.
         */
        fun printable(text: String): String =
            buildString(text.length) {
                text.codePoints().forEach { codePoint ->
                    when {
                        codePoint == '\t'.code -> {
                            append(TAB)
                        }

                        codePoint == '\n'.code -> {
                            append('\n')
                        }

                        Character.isISOControl(codePoint) -> {}

                        Character.getType(codePoint) == Character.FORMAT.toInt() -> {}

                        else -> {
                            appendCodePoint(codePoint)
                        }
                    }
                }
            }

        const val MARGIN = 56f
        const val SIZE = 11f
        const val GAP = 15f
        const val MAX_WIDTH = 595f - 2 * MARGIN

        /** The bundled Noto Sans, read once; each PDF embeds the subset it uses. */
        val REGULAR: ByteArray by lazy { font("NotoSans-Regular.ttf") }
        val BOLD: ByteArray by lazy { font("NotoSans-Bold.ttf") }

        fun font(name: String): ByteArray =
            checkNotNull(PdfBoxMailPdfRenderer::class.java.getResourceAsStream("fonts/$name")) {
                "The bundled font $name is missing"
            }.use { it.readBytes() }

        /**
         * Break [text] into lines that fit [MAX_WIDTH], at spaces where it can, and inside a word too
         * long for a line. One pass over the text: each code point is measured once.
         */
        fun wrap(
            text: String,
            face: Typeface,
            size: Float,
        ): List<String> {
            if (text.isEmpty()) return listOf("")
            val lines = mutableListOf<String>()
            val line = StringBuilder()
            var lineWidth = 0f
            val space = face.width(" ", size)

            fun breakLine() {
                lines += line.toString()
                line.setLength(0)
                lineWidth = 0f
            }
            for (word in text.split(' ')) {
                val wordWidth = face.width(word, size)
                val joined = if (line.isEmpty()) wordWidth else lineWidth + space + wordWidth
                when {
                    joined <= MAX_WIDTH -> {
                        if (line.isNotEmpty()) line.append(' ')
                        line.append(word)
                        lineWidth = joined
                    }

                    wordWidth <= MAX_WIDTH -> {
                        breakLine()
                        line.append(word)
                        lineWidth = wordWidth
                    }

                    else -> {
                        if (line.isNotEmpty()) breakLine()
                        // A word wider than a line (a long link, an encoded blob): cut it where a line is full.
                        word.codePoints().forEach { codePoint ->
                            val width = (face.width(codePoint) ?: 0f) / 1000f * size
                            if (line.isNotEmpty() && lineWidth + width > MAX_WIDTH) breakLine()
                            line.appendCodePoint(codePoint)
                            lineWidth += width
                        }
                    }
                }
            }
            if (line.isNotEmpty() || lines.isEmpty()) lines += line.toString()
            return lines
        }
    }
}
