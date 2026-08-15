package dev.npn.core.app

import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.npnDatabase
import dev.npn.core.fs.JvmLibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.index.DeviceIdentity
import dev.npn.core.index.MutableClock
import dev.npn.core.ocr.OcrEngine
import dev.npn.core.pathrules.StoragePathRule
import dev.npn.core.pdf.PdfBoxEngine
import dev.npn.core.pdf.ThumbnailStore
import dev.npn.core.xmp.PdfBoxXmpIO
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** No-op OCR engine (unavailable) so the pipeline uses embedded text only. */
class NoopOcrEngine : OcrEngine {
    override val available: Boolean = false
    override val name: String = "noop"

    override suspend fun recognizePng(
        png: ByteArray,
        lang: String,
    ): String = ""
}

class FakeThumbStore(
    private val dir: File,
) : ThumbnailStore {
    override suspend fun write(
        docId: String,
        png: ByteArray,
    ): String {
        dir.mkdirs()
        val f = File(dir, "$docId.png")
        f.writeBytes(png)
        return f.absolutePath
    }

    override suspend fun delete(docId: String) {
        File(dir, "$docId.png").delete()
    }
}

/**
 * Temp-dir rig that builds a real [LibrarySessionImpl] over a JVM filesystem + real Room DB +
 * real journal/index/ocr pieces. A second session on the same library (different deviceId) is
 * built via [secondSession] for convergence tests.
 */
class AppSessionRig private constructor(
    val libRoot: Path,
    val dbDir: File,
    val fs: JvmLibraryFileSystem,
    val db: NpnDatabase,
    val clock: MutableClock,
    val scope: CoroutineScope,
    val session: LibrarySessionImpl,
) {
    fun writePdf(
        relPath: String,
        bytes: ByteArray,
    ) {
        val p = libRoot.resolve(relPath)
        Files.createDirectories(p.parent ?: libRoot)
        Files.write(p, bytes)
    }

    fun exists(relPath: String): Boolean = Files.exists(libRoot.resolve(relPath))

    /** A document's id is the hash of its bytes, so the library path is enough to name it. */
    suspend fun docId(relPath: String): String = fs.sha256(relPath)

    /** Published journal objects, for asserting that a write committed exactly once. */
    suspend fun journalObjectCount(): Int =
        fs
            .listAllUnder(NpnPaths.JOURNAL_DIR)
            .count { NpnPaths.journalPath(it.relPath) is NpnPaths.JournalPath.ObjectV2 }

    suspend fun close() {
        session.close()
        db.close()
        libRoot.toFile().deleteRecursively()
        dbDir.deleteRecursively()
    }

    companion object {
        suspend fun create(
            scope: CoroutineScope,
            ocr: OcrEngine = NoopOcrEngine(),
            storagePathRule: suspend () -> StoragePathRule? = { null },
        ): AppSessionRig {
            val libRoot = Files.createTempDirectory("npn-app-lib")
            val dbDir = Files.createTempDirectory("npn-app-db").toFile()
            val fs = JvmLibraryFileSystem(libRoot)
            val db = npnDatabase(File(dbDir, "index.db"))
            val clock = MutableClock(autoTick = true)
            val deps =
                SessionDeps(
                    fs = fs,
                    db = db,
                    pdfEngine = PdfBoxEngine(),
                    ocrEngine = ocr,
                    xmpIO = PdfBoxXmpIO(),
                    thumbnailStore = FakeThumbStore(File(dbDir, "thumbs")),
                    scope = scope,
                    ocrLanguage = { "eng" },
                    trashRetentionDays = { 30 },
                    clock = clock,
                    installationId = "test-installation",
                    storagePathRule = storagePathRule,
                )
            val session = LibrarySessionImpl.openSession(deps)
            return AppSessionRig(libRoot, dbDir, fs, db, clock, scope, session)
        }
    }

    /**
     * Build a SECOND session over the SAME library folder but a fresh, separate DB cache and a
     * distinct deviceId (simulating another device syncing the same folder). Returns the session
     * plus its db so the caller can close it.
     */
    suspend fun secondSession(
        scope: CoroutineScope,
        ocr: OcrEngine = NoopOcrEngine(),
    ): Pair<LibrarySessionImpl, NpnDatabase> {
        val db2 = npnDatabase(File(dbDir, "index2.db"))
        // Force a distinct deviceId by seeding the kv before openSession.
        db2.keyValueDao().put(DeviceIdentity.KEY_DEVICE_ID, "device-two")
        val deps =
            SessionDeps(
                fs = fs,
                db = db2,
                pdfEngine = PdfBoxEngine(),
                ocrEngine = ocr,
                xmpIO = PdfBoxXmpIO(),
                thumbnailStore = FakeThumbStore(File(dbDir, "thumbs2")),
                scope = scope,
                ocrLanguage = { "eng" },
                trashRetentionDays = { 30 },
                clock = MutableClock(50_000L, autoTick = true),
            )
        return LibrarySessionImpl.openSession(deps) to db2
    }
}
