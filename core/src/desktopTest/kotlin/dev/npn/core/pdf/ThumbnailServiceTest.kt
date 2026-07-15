package dev.npn.core.pdf

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.npnDatabase
import dev.npn.core.fs.JvmLibraryFileSystem
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class ThumbnailServiceTest {

    private lateinit var libRoot: Path
    private lateinit var dbDir: File
    private lateinit var fs: JvmLibraryFileSystem
    private lateinit var db: NpnDatabase

    @BeforeTest
    fun setup() {
        libRoot = Files.createTempDirectory("npn-thumb-lib")
        dbDir = Files.createTempDirectory("npn-thumb-db").toFile()
        fs = JvmLibraryFileSystem(libRoot)
        db = npnDatabase(File(dbDir, "index.db"))
    }

    @AfterTest
    fun teardown() {
        db.close()
        libRoot.toFile().deleteRecursively()
        dbDir.deleteRecursively()
    }

    @Test
    fun generatesThumbnailAndBackfillsPageCount() = runTest {
        val bytes = PdfFixtures.embeddedTextPdf("hello world")
        fs.writeBytesOnce("doc.pdf", bytes)
        val docId = fs.sha256("doc.pdf")
        db.documentDao().upsert(
            DocumentEntity(
                docId = docId, relPath = "doc.pdf", fileName = "doc.pdf",
                size = bytes.size.toLong(), mtime = 1, addedAt = 1, pageCount = null,
            ),
        )
        val store = RecordingThumbnailStore()
        val service = ThumbnailService(PdfBoxEngine(), fs, store, db.thumbnailDao(), db.documentDao())

        val path = service.ensureThumbnail(docId, "doc.pdf")
        path shouldNotBe null
        store.writes[docId] shouldBe 1
        isPng(store.lastPng!!).shouldBeTrue()

        // pageCount backfilled from the open handle.
        db.documentDao().getByDocId(docId)!!.pageCount shouldBe 2
        db.thumbnailDao().get(docId)!!.cachePath shouldBe path
    }

    @Test
    fun secondCallHitsCacheAndDoesNotRegenerate() = runTest {
        val bytes = PdfFixtures.embeddedTextPdf("hi")
        fs.writeBytesOnce("doc.pdf", bytes)
        val docId = fs.sha256("doc.pdf")
        db.documentDao().upsert(
            DocumentEntity(
                docId = docId, relPath = "doc.pdf", fileName = "doc.pdf",
                size = bytes.size.toLong(), mtime = 1, addedAt = 1,
            ),
        )
        val store = RecordingThumbnailStore()
        val service = ThumbnailService(PdfBoxEngine(), fs, store, db.thumbnailDao(), db.documentDao())

        val first = service.ensureThumbnail(docId, "doc.pdf")
        val second = service.ensureThumbnail(docId, "doc.pdf")
        second shouldBe first
        store.writes[docId] shouldBe 1 // only generated once
    }

    @Test
    fun returnsNullWhenFileMissing() = runTest {
        val store = RecordingThumbnailStore()
        val service = ThumbnailService(PdfBoxEngine(), fs, store, db.thumbnailDao(), db.documentDao())
        service.ensureThumbnail("a".repeat(64), "nope.pdf") shouldBe null
    }
}

private class RecordingThumbnailStore : ThumbnailStore {
    val writes = mutableMapOf<String, Int>()
    var lastPng: ByteArray? = null

    override suspend fun write(docId: String, png: ByteArray): String {
        writes[docId] = (writes[docId] ?: 0) + 1
        lastPng = png
        return "/cache/$docId.png"
    }

    override suspend fun delete(docId: String) {
        writes.remove(docId)
    }
}
