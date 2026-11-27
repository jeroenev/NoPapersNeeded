package dev.npn.core.sim

import dev.npn.core.app.IngestFacade
import dev.npn.core.fs.ExternalSource
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.ocr.OcrEngine
import dev.npn.core.ocr.OcrEngineId
import dev.npn.core.pdf.PdfDocumentHandle
import dev.npn.core.pdf.PdfEngine
import dev.npn.core.pdf.PdfOpenException
import dev.npn.core.pdf.ThumbnailStore
import dev.npn.core.watch.WatchedFolder
import dev.npn.core.watch.WatchedFolderFile
import dev.npn.core.xmp.PdfXmpIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.nio.file.Path
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
 * generators give theirs: two files written in the same virtual millisecond, one renamed over the
 * other, would otherwise share a stat. The same label (the same baked copy of the same document,
 * made on two replicas) gets the same size, so it makes the same bytes.
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

/**
 * A [PdfEngine] over [SimPdf] bytes: [SimPdf.pagesOf] pages each, and the text [SimPdf.textOf] reads.
 * Pages it extracts make a new file each time, named by [maker] and a count, as PDFBox seeds a new
 * document's `/ID` from the clock: two replicas splitting one document, or a split retried, make
 * other bytes, never the same parts.
 */
class SimPdfEngine(
    private val sizes: SimSizes = SimSizes(),
    private val maker: String = "sim",
) : PdfEngine {
    private var made = 0

    override suspend fun open(bytes: ByteArray): PdfDocumentHandle {
        if (!SimPdf.isPdf(bytes)) throw PdfOpenException("Not a simulated PDF")
        return object : PdfDocumentHandle {
            override val pageCount: Int = SimPdf.pagesOf(bytes)

            override val creationDate: Long? = null

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
        val label = "pages-${sha256Bytes(bytes).take(HASH_LABEL)}-${pageIndices.joinToString("_")}-$maker-${made++}"
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
class GatedOcrEngine(
    /**
     * False for an installation without the engine, as a desktop before Tesseract is installed: the
     * pipeline then runs no OCR at all, and keeps a document's own text layer where it has none.
     */
    private val installed: Boolean = true,
) : OcrEngine {
    override val name: String = OcrEngineId.TESSERACT.column

    private val waiting = ArrayDeque<CompletableDeferred<Unit>>()

    /** While true, pages are read at once. */
    var gateOpen = false

    val inFlight: Int get() = waiting.size

    override suspend fun available(): Boolean = installed

    override suspend fun recognizePng(
        png: ByteArray,
        lang: String,
        runId: String?,
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

/**
 * A watched inbox folder held in memory, outside the library: a scanner app's output folder. Its
 * files are read through the library filesystem's [InMemoryLibraryFileSystem.externalSources] hook,
 * by the path [ExternalSource] names. A delete keeps to the interface's contract (only while the
 * file still holds what was imported), and records as a violation a delete of bytes the library
 * has no document of.
 *
 * The importer proves the library holds the document ([IngestFacade.holdsDocument]) and then
 * deletes the source, two calls on two filesystems with time between them: the sync tool or another
 * process may take the library's file in that gap, as they may a moment after the delete. A delete
 * whose bytes the library no longer holds is no violation when the importer's last proof of the
 * document, made through [proving], held when it was made and only the sync tool or the outside
 * world took the document's bytes from the library folder since: the app acted on a fresh proof and
 * took nothing itself. A delete with no such proof, or after the app itself took the bytes, is one.
 */
internal class SimWatchedFolder(
    private val name: String,
    private val now: () -> Long,
    /** Whether the library has a document of these bytes: holds them, or a relink superseded them. */
    private val accounted: suspend (hash: String) -> Boolean,
    /** Every time bytes left the library folder: who took a document's bytes since a proof. */
    private val losses: Losses,
) : WatchedFolder {
    /** When the importer last started a [IngestFacade.holdsDocument] that answered yes, by document. */
    private val provenAt = HashMap<String, Long>()

    /** [ingest], with every proof that the library holds a document recorded for [delete] to judge by. */
    fun proving(ingest: IngestFacade): IngestFacade =
        object : IngestFacade by ingest {
            override suspend fun holdsDocument(docId: String): Boolean {
                val startedAt = now()
                return ingest.holdsDocument(docId).also { if (it) provenAt[docId] = startedAt }
            }
        }

    /**
     * Whether, since the importer last proved the library holds [docId], something other than the
     * app took its bytes from the library folder (the last copy there among them) and the app took
     * none: the gap between a proof and the delete, not a stale or skipped proof.
     */
    private fun takenSinceProof(docId: String): Boolean {
        val since = provenAt[docId] ?: return false
        val taken = losses.recordsOf(docId).filter { it.atMillis >= since }
        return taken.any { it.lastHere } && taken.none { it.loss == Loss.APP || it.loss == Loss.APP_TRASH }
    }

    private class Entry(
        var bytes: ByteArray,
        var mtime: Long,
    )

    private val files = sortedMapOf<String, Entry>()
    private val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Deletes of a file whose bytes the library held nowhere: the importer threw away a document. */
    val violations = ArrayList<String>()

    val root = "/sim-watched/$name"

    val names: List<String> get() = files.keys.toList()

    fun bytesFor(path: Path): ByteArray? =
        path.toString().takeIf { it.startsWith("$root/") }?.let { files[it.removePrefix("$root/")]?.bytes?.copyOf() }

    fun write(
        file: String,
        bytes: ByteArray,
    ) {
        files[file] = Entry(bytes.copyOf(), now())
        events.tryEmit(Unit)
    }

    /** Write [bytes] as [file] in place, [chunks] pieces [intervalMillis] apart, as a scanner app saving slowly. */
    suspend fun writeInChunks(
        file: String,
        bytes: ByteArray,
        chunks: Int,
        intervalMillis: Long,
    ) {
        val step = (bytes.size + chunks - 1) / chunks
        var written = 0
        while (written < bytes.size) {
            written = minOf(bytes.size, written + step)
            val entry = files.getOrPut(file) { Entry(ByteArray(0), now()) }
            entry.bytes = bytes.copyOf(written)
            entry.mtime = now()
            events.tryEmit(Unit)
            if (written < bytes.size) delay(intervalMillis)
        }
    }

    fun remove(file: String) {
        files.remove(file)
        events.tryEmit(Unit)
    }

    override suspend fun listPdfs(): List<WatchedFolderFile> =
        files.filterKeys { it.endsWith(".pdf") }.map { (file, entry) ->
            WatchedFolderFile(
                "$root/$file",
                file,
                entry.bytes.size.toLong(),
                entry.mtime,
                ExternalSource(Path.of(root, file)),
            )
        }

    override suspend fun delete(
        file: WatchedFolderFile,
        docId: String,
    ): Boolean {
        val entry = files[file.name] ?: return false
        if (!file.stillHolds(docId, entry.bytes.size.toLong(), entry.mtime) { sha256Bytes(entry.bytes) }) return false
        val hash = sha256Bytes(entry.bytes)
        if (!accounted(hash) && !takenSinceProof(docId)) {
            violations += "$name deleted '${file.name}' (${hash.take(ID)}), which the library holds nowhere"
        }
        files.remove(file.name)
        events.tryEmit(Unit)
        return true
    }

    override fun changes(): Flow<Unit> = events.asSharedFlow()

    private companion object {
        const val ID = 10
    }
}
