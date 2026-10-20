package dev.npn.core.sim

import dev.npn.core.fs.sha256Bytes
import dev.npn.core.ocr.OcrEngine
import dev.npn.core.ocr.OcrEngineId
import dev.npn.core.pdf.PdfDocumentHandle
import dev.npn.core.pdf.PdfEngine
import dev.npn.core.pdf.PdfOpenException
import dev.npn.core.pdf.ThumbnailStore
import dev.npn.core.xmp.PdfXmpIO
import kotlinx.coroutines.CompletableDeferred
import kotlin.io.encoding.Base64

/**
 * The simulation's "PDFs": any bytes starting `%PDF-` open, so a document is just distinct bytes.
 * A file holding `text:<words>` has that embedded text on its pages; one without is a scan, which
 * only OCR reads. `pages:<n>` gives it more than one page, and `xmp:<base64>` is the metadata a
 * bake wrote ([SimXmpIO]). Every simulated PDF ends with `%%EOF`, so a partial one (still being
 * written in chunks) lacks it, as a real partial file does.
 */
object SimPdf {
    private const val HEADER = "%PDF-1.7\n"
    private const val TRAILER = "\n%%EOF\n"

    /**
     * A complete simulated PDF named [label]; with [text] it carries that text layer. With [size]
     * it is padded (in a comment line) to exactly that many bytes, when it is not longer already.
     */
    fun bytes(
        label: String,
        text: String? = null,
        size: Int? = null,
        pages: Int = 1,
        xmp: String? = null,
    ): ByteArray {
        val body =
            HEADER + "%sim $label\n" + text?.let { "text:$it\n" }.orEmpty() +
                (if (pages > 1) "pages:$pages\n" else "") +
                xmp?.let { "xmp:${Base64.encode(it.encodeToByteArray())}\n" }.orEmpty()
        val padding = size?.let { (it - body.length - TRAILER.length - 2).coerceAtLeast(0) } ?: 0
        val padded = if (padding > 0) body + "%" + " ".repeat(padding) + "\n" else body
        return (padded + TRAILER).encodeToByteArray()
    }

    fun isPdf(bytes: ByteArray): Boolean = bytes.decodeToString().startsWith(HEADER.trim())

    private fun line(
        bytes: ByteArray,
        prefix: String,
    ): String? =
        bytes
            .decodeToString()
            .lineSequence()
            .firstOrNull { it.startsWith(prefix) }
            ?.removePrefix(prefix)

    fun textOf(bytes: ByteArray): String = line(bytes, "text:").orEmpty()

    fun labelOf(bytes: ByteArray): String = line(bytes, "%sim ").orEmpty()

    fun pagesOf(bytes: ByteArray): Int = line(bytes, "pages:")?.toIntOrNull() ?: 1

    fun xmpOf(bytes: ByteArray): String? = line(bytes, "xmp:")?.let { Base64.decode(it).decodeToString() }
}

/**
 * Sizes for the PDFs the engines make (split parts, baked copies), one per label, as the
 * generators give theirs where storage reports mtimes: two files written in the same virtual
 * millisecond, one renamed over the other, would otherwise share a stat. The same label (the same
 * part of the same document, made on two replicas) gets the same size, so it makes the same bytes.
 */
class SimSizes(
    private val base: Int = BASE,
) {
    private val byLabel = HashMap<String, Int>()

    fun sizeOf(label: String): Int = byLabel.getOrPut(label) { base + byLabel.size }

    private companion object {
        /** Above every size the generators give the PDFs they make (see [SimGenerator]). */
        const val BASE = 20_000
    }
}

/** A [PdfEngine] over [SimPdf] bytes: [SimPdf.pagesOf] pages each, and the text [SimPdf.textOf] reads. */
class SimPdfEngine(
    private val sizes: SimSizes = SimSizes(),
) : PdfEngine {
    var opened = 0
        private set

    override suspend fun open(bytes: ByteArray): PdfDocumentHandle {
        if (!SimPdf.isPdf(bytes)) throw PdfOpenException("Not a simulated PDF")
        opened++
        return object : PdfDocumentHandle {
            override val pageCount: Int = SimPdf.pagesOf(bytes)

            override fun extractText(pageIndex: Int): String = SimPdf.textOf(bytes)

            override fun renderPagePng(
                pageIndex: Int,
                maxDimensionPx: Int,
            ): ByteArray = "png:${sha256Bytes(bytes)}:$pageIndex".encodeToByteArray()

            override fun close() = Unit
        }
    }

    override suspend fun imagesToPdf(images: List<ByteArray>): ByteArray =
        SimPdf.bytes("images-" + sha256Bytes(images.fold(ByteArray(0)) { all, image -> all + image }).take(HASH_LABEL))

    override suspend fun extractPages(
        bytes: ByteArray,
        pageIndices: List<Int>,
    ): ByteArray {
        val pages = SimPdf.pagesOf(bytes)
        require(
            pageIndices.isNotEmpty() && pageIndices.all { it in 0 until pages },
        ) { "Pages out of range: $pageIndices" }
        val label = "pages-${sha256Bytes(bytes).take(HASH_LABEL)}-${pageIndices.joinToString("_")}"
        return SimPdf.bytes(label, SimPdf.textOf(bytes), sizes.sizeOf(label), pages = pageIndices.size)
    }

    private companion object {
        const val HASH_LABEL = 12
    }
}

/**
 * A local OCR engine whose every page waits for the simulation to let it finish ([complete] one,
 * or [openGate] for all), so OCR runs stay in flight across other operations, as a slow Tesseract
 * does. Its text names the page it read, so a document's OCR text is its own.
 */
class GatedOcrEngine : OcrEngine {
    override val name: String = OcrEngineId.TESSERACT.column

    private val waiting = ArrayDeque<CompletableDeferred<Unit>>()

    /** While true, pages are read at once. */
    var gateOpen = false

    var recognized = 0
        private set

    val inFlight: Int get() = waiting.size

    override suspend fun available(): Boolean = true

    override suspend fun recognizePng(
        png: ByteArray,
        lang: String,
    ): String {
        if (!gateOpen) {
            val turn = CompletableDeferred<Unit>()
            waiting.addLast(turn)
            try {
                turn.await()
            } finally {
                waiting.remove(turn)
            }
        }
        recognized++
        return "ocr " + png.decodeToString().substringAfter("png:").take(OCR_LABEL)
    }

    /** Let the oldest waiting page finish; false when none waits. */
    fun complete(): Boolean = waiting.firstOrNull()?.complete(Unit) ?: false

    /** Let every page through, now and until [closeGate]. */
    fun openGate() {
        gateOpen = true
        waiting.toList().forEach { it.complete(Unit) }
    }

    fun closeGate() {
        gateOpen = false
    }

    /** Forget the pages a killed process was waiting on: they never finish, and never leave the queue. */
    fun forgetWaiting() = waiting.clear()

    private companion object {
        const val OCR_LABEL = 16
    }
}

/** Thumbnails in memory: disposable, device-local, never compared. */
class MemoryThumbnailStore : ThumbnailStore {
    private val pngs = HashMap<String, ByteArray>()

    override suspend fun existing(docId: String): String? = docId.takeIf { pngs.containsKey(it) }

    override suspend fun write(
        docId: String,
        png: ByteArray,
    ): String {
        pngs[docId] = png
        return docId
    }

    override suspend fun clear(): Int = pngs.size.also { pngs.clear() }
}

/**
 * XMP for [SimPdf] bytes: the packet travels as the PDF's `xmp:` line, so a bake writes new bytes
 * (a new document identity) that read back as carrying the metadata, and bake-in-place relinks.
 * The same packet on the same PDF makes the same bytes; anything not a simulated PDF is refused,
 * as a PDF library refuses a file it cannot parse.
 */
class SimXmpIO(
    private val sizes: SimSizes = SimSizes(),
) : PdfXmpIO {
    override suspend fun readXmp(pdfBytes: ByteArray): String? = SimPdf.xmpOf(pdfBytes)

    override suspend fun writeBaked(
        pdfBytes: ByteArray,
        xmp: String,
        keywords: List<String>,
        title: String?,
    ): ByteArray {
        if (!SimPdf.isPdf(pdfBytes)) throw PdfOpenException("Not a simulated PDF")
        val label = "${SimPdf.labelOf(
            pdfBytes,
        ).substringBefore('+')}+baked-${sha256Bytes(pdfBytes + xmp.encodeToByteArray()).take(LABEL)}"
        return SimPdf.bytes(
            label,
            SimPdf.textOf(pdfBytes).ifEmpty { null },
            sizes.sizeOf(label),
            pages = SimPdf.pagesOf(pdfBytes),
            xmp = xmp,
        )
    }

    private companion object {
        const val LABEL = 10
    }
}
