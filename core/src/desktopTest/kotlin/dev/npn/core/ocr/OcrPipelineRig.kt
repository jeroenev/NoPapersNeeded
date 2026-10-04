package dev.npn.core.ocr

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.OcrQueueEntity
import dev.npn.core.db.npnDatabase
import dev.npn.core.fs.JvmLibraryFileSystem
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.OcrSidecarFiles
import dev.npn.core.journal.EpochClock
import dev.npn.core.journal.SystemEpochClock
import dev.npn.core.pdf.PdfBoxEngine
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Temp library folder + index for [OcrPipeline] tests: builds pipelines over them and stages
 * documents the way the ingest path would. Shared by the pipeline tests and the `onTextIndexed`
 * hook tests so neither class carries the fixture itself.
 */
internal class OcrPipelineRig {
    val libRoot: Path = Files.createTempDirectory("npn-ocr-lib")
    private val dbDir: File = Files.createTempDirectory("npn-ocr-db").toFile()
    val fs = JvmLibraryFileSystem(libRoot)
    val db: NpnDatabase = npnDatabase(File(dbDir, "index.db"))

    fun pipeline(
        deviceId: String,
        ocr: OcrEngine,
        database: NpnDatabase = db,
        engines: OcrEngineSelector = OcrEngineSelector(local = ocr),
        config: OcrConfig = OcrConfig(),
        clock: EpochClock = SystemEpochClock,
        files: LibraryFileSystem = fs,
    ) = OcrPipeline(
        fs = files,
        sidecars = OcrSidecarFiles(files),
        pdfEngine = PdfBoxEngine(),
        engines = engines,
        documentDao = database.documentDao(),
        ftsDao = database.ftsDao(),
        ocrQueueDao = database.ocrQueueDao(),
        tagDao = database.tagDao(),
        fieldDao = database.documentFieldDao(),
        deviceId = deviceId,
        config = config,
        clock = clock,
    )

    /**
     * A second, independent index over the same library folder — how another device sees a library
     * whose sidecars are already there. Caller closes it.
     */
    fun openSecondIndex(name: String = "indexB.db"): NpnDatabase = npnDatabase(File(dbDir, name))

    /** Write [bytes] into the library and index it as an OCR-pending document. */
    suspend fun stageDoc(
        relPath: String,
        bytes: ByteArray,
    ): String {
        fs.writeBytesOnce(relPath, bytes)
        val docId = fs.sha256(relPath)
        db.documentDao().upsert(
            DocumentEntity(
                docId = docId,
                relPath = relPath,
                fileName = relPath.substringAfterLast('/'),
                size = bytes.size.toLong(),
                mtime = 1000,
                addedAt = 1000,
            ),
        )
        db.ocrQueueDao().upsert(OcrQueueEntity(docId, "pending", updatedAt = 1))
        return docId
    }

    fun close() {
        db.close()
        libRoot.toFile().deleteRecursively()
        dbDir.deleteRecursively()
    }
}

/** Records how many times OCR was invoked and returns canned text. */
internal class FakeOcrEngine(
    private val isAvailable: Boolean,
    override val name: String,
    private val canned: String = "canned ocr text",
    /** What [languagesFor] resolves a request to; all of it by default, like a real engine. */
    private val installed: (String) -> String = { it },
) : OcrEngine {
    var calls = 0
        private set

    /** The `lang` of the last [recognizePng] call. */
    var lastLang: String? = null
        private set

    /** Thrown by every call while set, the way an engine reports a page it could not read. */
    var failure: Exception? = null

    override suspend fun available(): Boolean = isAvailable

    override suspend fun languagesFor(lang: String): String = installed(lang)

    override suspend fun recognizePng(
        png: ByteArray,
        lang: String,
    ): String {
        calls++
        lastLang = lang
        failure?.let { throw it }
        return canned
    }
}
