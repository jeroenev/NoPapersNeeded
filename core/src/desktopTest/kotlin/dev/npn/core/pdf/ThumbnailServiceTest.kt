package dev.npn.core.pdf

import dev.npn.core.db.DocumentDao
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.TempIndexDb
import dev.npn.core.db.documentEntity
import dev.npn.core.fs.JvmLibraryFileSystem
import dev.npn.core.fs.PathExternalIo
import dev.npn.core.index.requireByDocId
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test

class ThumbnailServiceTest {
    private val libRoot: Path = Files.createTempDirectory("npn-thumb-lib")
    private val fs = JvmLibraryFileSystem(libRoot, PathExternalIo)
    private val temp = TempIndexDb("npn-thumb-db")
    private val db = temp.db

    @AfterTest
    fun teardown() {
        libRoot.toFile().deleteRecursively()
        temp.close()
    }

    @Test
    fun generatesThumbnailAndBackfillsPageCount() =
        runTest {
            val bytes = PdfFixtures.embeddedTextPdf("hello world")
            fs.writeBytesOnce("doc.pdf", bytes)
            val docId = fs.sha256("doc.pdf")
            db.documentDao().upsert(
                documentEntity(docId, "doc.pdf", size = bytes.size.toLong(), mtime = 1, addedAt = 1),
            )
            val store = RecordingThumbnailStore()
            val service =
                ThumbnailService(PdfBoxEngine(), fs, store, db.thumbnailDao(), db.documentDao(), RenderGate(1))

            val path = service.ensureThumbnail(docId, "doc.pdf")
            path shouldNotBe null
            store.writes[docId] shouldBe 1
            isPng(store.lastPng.shouldNotBeNull()).shouldBeTrue()

            // pageCount backfilled from the open handle.
            db.documentDao().requireByDocId(docId).pageCount shouldBe 2
            db
                .thumbnailDao()
                .get(docId)
                .shouldNotBeNull()
                .cachePath shouldBe path
        }

    /**
     * Thumbnails generate while the first rescan updates rows. The page-count backfill must touch
     * only that column: a row copy read before a fold moved the file must not move it back.
     */
    @Test
    fun backfillingThePageCountKeepsWhatAConcurrentFoldWrote() =
        runTest {
            val bytes = PdfFixtures.embeddedTextPdf("hello world")
            fs.writeBytesOnce("doc.pdf", bytes)
            val docId = fs.sha256("doc.pdf")
            val dao = db.documentDao()
            dao.upsert(
                documentEntity(docId, "doc.pdf", size = bytes.size.toLong(), mtime = 1, addedAt = 1),
            )
            // A fold moves the document just before the backfill writes, whichever way it writes.
            val racingDao =
                object : DocumentDao by dao {
                    suspend fun foldLands() {
                        val row = dao.getByDocId(docId).shouldNotBeNull()
                        dao.upsert(row.copy(relPath = "moved/doc.pdf", title = "Folded"))
                    }

                    override suspend fun upsert(doc: DocumentEntity) {
                        foldLands()
                        dao.upsert(doc)
                    }

                    override suspend fun fillPageCount(
                        docId: String,
                        pageCount: Int,
                    ) {
                        foldLands()
                        dao.fillPageCount(docId, pageCount)
                    }
                }
            val service =
                ThumbnailService(
                    PdfBoxEngine(),
                    fs,
                    RecordingThumbnailStore(),
                    db.thumbnailDao(),
                    racingDao,
                    RenderGate(1),
                )

            service.ensureThumbnail(docId, "doc.pdf") shouldNotBe null

            val row = dao.getByDocId(docId).shouldNotBeNull()
            row.pageCount shouldBe 2
            row.relPath shouldBe "moved/doc.pdf"
            row.title shouldBe "Folded"
        }

    @Test
    fun aKnownPageCountIsNeverOverwritten() =
        runTest {
            db.documentDao().upsert(
                documentEntity("d", "d.pdf", size = 1, mtime = 1, addedAt = 1, pageCount = 3),
            )
            db.documentDao().fillPageCount("d", 5)
            db
                .documentDao()
                .getByDocId("d")
                .shouldNotBeNull()
                .pageCount shouldBe 3
        }

    @Test
    fun secondCallHitsCacheAndDoesNotRegenerate() =
        runTest {
            val bytes = PdfFixtures.embeddedTextPdf("hi")
            fs.writeBytesOnce("doc.pdf", bytes)
            val docId = fs.sha256("doc.pdf")
            db.documentDao().upsert(
                documentEntity(docId, "doc.pdf", size = bytes.size.toLong(), mtime = 1, addedAt = 1),
            )
            val store = RecordingThumbnailStore()
            val service =
                ThumbnailService(PdfBoxEngine(), fs, store, db.thumbnailDao(), db.documentDao(), RenderGate(1))

            val first = service.ensureThumbnail(docId, "doc.pdf")
            val second = service.ensureThumbnail(docId, "doc.pdf")
            second shouldBe first
            store.writes[docId] shouldBe 1 // only generated once
        }

    @Test
    fun returnsNullWhenFileMissing() =
        runTest {
            val store = RecordingThumbnailStore()
            val service =
                ThumbnailService(PdfBoxEngine(), fs, store, db.thumbnailDao(), db.documentDao(), RenderGate(1))
            service.ensureThumbnail("a".repeat(64), "nope.pdf") shouldBe null
        }

    @Test
    fun clearCacheDeletesEntriesAndForcesRegeneration() =
        runTest {
            val bytes = PdfFixtures.embeddedTextPdf("clear me")
            fs.writeBytesOnce("doc.pdf", bytes)
            val docId = fs.sha256("doc.pdf")
            db.documentDao().upsert(
                documentEntity(docId, "doc.pdf", size = bytes.size.toLong(), mtime = 1, addedAt = 1),
            )
            val store = RecordingThumbnailStore()
            val service =
                ThumbnailService(PdfBoxEngine(), fs, store, db.thumbnailDao(), db.documentDao(), RenderGate(1))

            service.ensureThumbnail(docId, "doc.pdf")
            db.thumbnailDao().get(docId) shouldNotBe null

            service.clearCache() shouldBe 1
            db.thumbnailDao().get(docId) shouldBe null
            store.writes.containsKey(docId).shouldBeFalse() // file deleted via store

            // Next ensure regenerates (writes again).
            service.ensureThumbnail(docId, "doc.pdf")
            store.writes[docId] shouldBe 1
        }
}

private class RecordingThumbnailStore : ThumbnailStore {
    val writes = mutableMapOf<String, Int>()
    var lastPng: ByteArray? = null

    override suspend fun write(
        docId: String,
        png: ByteArray,
    ): String {
        writes[docId] = (writes[docId] ?: 0) + 1
        lastPng = png
        return "/cache/$docId.png"
    }

    override suspend fun delete(docId: String) {
        writes.remove(docId)
    }
}
