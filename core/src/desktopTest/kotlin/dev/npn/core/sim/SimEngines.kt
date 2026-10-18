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

/**
 * The simulation's "PDFs": any bytes starting `%PDF-` open, so a document is just distinct bytes.
 * A file holding `text:<words>` has that embedded text on its one page; one without is a scan,
 * which only OCR reads. Every simulated PDF ends with `%%EOF`, so a partial one (still being
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
    ): ByteArray {
        val body = HEADER + "%sim $label\n" + (text?.let { "text:$it\n" } ?: "")
        val padding = size?.let { (it - body.length - TRAILER.length - 2).coerceAtLeast(0) } ?: 0
        val padded = if (padding > 0) body + "%" + " ".repeat(padding) + "\n" else body
        return (padded + TRAILER).encodeToByteArray()
    }

    fun isPdf(bytes: ByteArray): Boolean = bytes.decodeToString().startsWith(HEADER.trim())

    fun textOf(bytes: ByteArray): String =
        bytes
            .decodeToString()
            .lineSequence()
            .firstOrNull { it.startsWith("text:") }
            ?.removePrefix("text:")
            .orEmpty()
}

/** A [PdfEngine] over [SimPdf] bytes: one page each, and its embedded text as [SimPdf.textOf] reads it. */
class SimPdfEngine : PdfEngine {
    var opened = 0
        private set

    override suspend fun open(bytes: ByteArray): PdfDocumentHandle {
        if (!SimPdf.isPdf(bytes)) throw PdfOpenException("Not a simulated PDF")
        opened++
        return object : PdfDocumentHandle {
            override val pageCount: Int = 1

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
        require(pageIndices.isNotEmpty() && pageIndices.all { it == 0 }) { "Pages out of range: $pageIndices" }
        return SimPdf.bytes("pages-${sha256Bytes(bytes).take(HASH_LABEL)}-$pageIndices", SimPdf.textOf(bytes))
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

/** No XMP: the simulation does not bake (see docs/SIMULATION.md, what it does not model). */
object NoXmpIO : PdfXmpIO {
    override suspend fun readXmp(pdfBytes: ByteArray): String? = null

    override suspend fun writeBaked(
        pdfBytes: ByteArray,
        xmp: String,
        keywords: List<String>,
        title: String?,
    ): ByteArray = throw UnsupportedOperationException("The simulation does not bake")
}
