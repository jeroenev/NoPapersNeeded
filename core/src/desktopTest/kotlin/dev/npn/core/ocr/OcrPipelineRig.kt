package dev.npn.core.ocr

import dev.npn.core.db.DeviceDatabase
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.OcrQueueDao
import dev.npn.core.db.OcrQueueEntity
import dev.npn.core.db.TempIndexDb
import dev.npn.core.db.deviceDatabase
import dev.npn.core.db.documentEntity
import dev.npn.core.db.npnDatabase
import dev.npn.core.fs.JvmLibraryFileSystem
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.PathExternalIo
import dev.npn.core.pdf.PdfBoxEngine
import dev.npn.core.util.EpochClock
import dev.npn.core.util.SystemEpochClock
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Writer keys that have a sidecar for [hash], however long [hash] is. */
internal suspend fun OcrSidecarFiles.writerKeysOf(hash: String): List<String> =
    list().mapNotNull { meta -> OcrSidecarName.parse(meta.name)?.takeIf { it.hash == hash }?.writerKey }

/**
 * Temp library folder + index for [OcrPipeline] tests: builds pipelines over them and stages
 * documents the way the ingest path would. Shared by the pipeline tests and the `onTextIndexed`
 * hook tests so neither class carries the fixture itself.
 */
internal class OcrPipelineRig {
    val libRoot: Path = Files.createTempDirectory("npn-ocr-lib")
    private val dbs = TempIndexDb("npn-ocr-db")
    val fs = JvmLibraryFileSystem(libRoot, PathExternalIo)
    val db: NpnDatabase = dbs.db

    /** This device's OCR queue and page cache live here, apart from the index. */
    val deviceDb: DeviceDatabase = dbs.deviceDb

    fun pipeline(
        deviceId: String,
        ocr: OcrEngine,
        database: NpnDatabase = db,
        queue: OcrQueueDao = deviceDb.ocrQueueDao(),
        engines: OcrEngineSelector = OcrEngineSelector(local = ocr),
        language: suspend () -> String = { "eng" },
        clock: EpochClock = SystemEpochClock,
        files: LibraryFileSystem = fs,
        onTextIndexed: suspend (docId: String, text: String) -> Unit = { _, _ -> },
        onOcrProduced: suspend (docId: String) -> Unit = {},
        onStaleFile: suspend (docId: String) -> Unit = {},
    ) = OcrPipeline(
        fs = files,
        pdfEngine = PdfBoxEngine(),
        engines = engines,
        index = database,
        ocrQueueDao = queue,
        deviceId = deviceId,
        language = language,
        clock = clock,
        onTextIndexed = onTextIndexed,
        onOcrProduced = onOcrProduced,
        onStaleFile = onStaleFile,
    )

    /**
     * A second, independent index over the same library folder — how another device sees a library
     * whose sidecars are already there, or this one after its index was rebuilt. Caller closes it.
     */
    fun openSecondIndex(name: String = "indexB.db"): NpnDatabase = npnDatabase(File(dbs.dir, name))

    /** Another device's own state, for a pipeline over [openSecondIndex] as that device. Caller closes it. */
    fun openSecondDevice(name: String = "deviceB.db"): DeviceDatabase = deviceDatabase(File(dbs.dir, name))

    /** Write [bytes] into the library and index it as an OCR-pending document. */
    suspend fun stageDoc(
        relPath: String,
        bytes: ByteArray,
    ): String {
        fs.writeBytesOnce(relPath, bytes)
        val docId = fs.sha256(relPath)
        db.documentDao().upsert(
            documentEntity(docId, relPath, size = bytes.size.toLong()),
        )
        deviceDb.ocrQueueDao().upsert(OcrQueueEntity(docId, "pending", updatedAt = 1))
        return docId
    }

    /** Publish a sidecar as if another device produced it (legacy, non-engine-qualified name). */
    suspend fun writeForeignSidecar(
        docId: String,
        dev: String,
        engine: String,
        text: String,
    ) {
        val sidecar =
            OcrSidecar(
                doc = docId,
                dev = dev,
                engine = engine,
                lang = "eng",
                createdAt = 1,
                pages = listOf(text),
            )
        OcrSidecarFiles(fs).writeOnce(docId, dev, OcrSidecarCodec.encode(sidecar))
    }

    fun close() {
        dbs.close()
        libRoot.toFile().deleteRecursively()
    }
}
