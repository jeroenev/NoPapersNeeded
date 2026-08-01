package dev.npn.core.watch

import dev.npn.core.app.AppSessionRig
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySort
import dev.npn.core.ocr.OcrEngine
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.Test

class WatchedFolderImporterTest {
    private val watchDir: Path = Files.createTempDirectory("npn-watched")

    @AfterTest
    fun cleanup() {
        watchDir.toFile().deleteRecursively()
    }

    /** Write a PDF into the watched folder with an old mtime so the quiet-window check passes. */
    private fun stagePdf(
        name: String,
        bytes: ByteArray,
    ) {
        val p = watchDir.resolve(name)
        Files.write(p, bytes)
        Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis() - 60_000))
    }

    @Test
    fun importsNewPdfsAndSkipsThemOnLaterScans() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                stagePdf("scan-a.pdf", PdfFixtures.embeddedTextPdf("watched one"))
                stagePdf("scan-b.pdf", PdfFixtures.embeddedTextPdf("watched two"))
                Files.write(watchDir.resolve("notes.txt"), byteArrayOf(1, 2, 3))

                val importer = WatchedFolderImporter(rig.session, JvmWatchedFolder(watchDir), { false })

                val first = importer.scanOnce()
                first.imported shouldBe 2
                first.failed shouldBe 0

                // Second sweep: nothing new, sources kept (delete-after-import off).
                importer.scanOnce() shouldBe WatchedFolderScan(0, 0, 0)
                Files.exists(watchDir.resolve("scan-a.pdf")) shouldBe true

                val docs =
                    rig.session.documents
                        .observeDocuments(LibraryFilter(), LibrarySort())
                        .first()
                docs.map { it.doc.fileName }.sorted() shouldBe listOf("scan-a.pdf", "scan-b.pdf")
                // Every auto-import is queued for OCR like a manual import.
                docs.all { it.doc.ocrState == "pending" || it.doc.ocrState == "done" } shouldBe true
            } finally {
                rig.close()
            }
        }

    @Test
    fun closingSessionAwaitsInFlightOcrBeforeClosingDatabase() =
        runTest {
            val ocr = BlockingOcrEngine()
            val rig = AppSessionRig.create(backgroundScope, ocr)
            var closed = false
            try {
                stagePdf("scan.pdf", PdfFixtures.imageOnlyPdf())
                WatchedFolderImporter(rig.session, JvmWatchedFolder(watchDir), { false })
                    .scanOnce()
                    .imported shouldBe 1
                ocr.started.await()

                val closing = async { rig.close() }
                yield()
                closing.isCompleted shouldBe false

                ocr.allowFinish.complete(Unit)
                closing.await()
                ocr.finished.isCompleted shouldBe true
                closed = true
            } finally {
                ocr.allowFinish.complete(Unit)
                if (!closed) rig.close()
            }
        }

    @Test
    fun freshImporterReportsExistingFileAsDuplicateViaHashDedup() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                stagePdf("scan.pdf", PdfFixtures.embeddedTextPdf("same bytes"))
                WatchedFolderImporter(rig.session, JvmWatchedFolder(watchDir), { false })
                    .scanOnce()
                    .imported shouldBe 1

                // A brand-new importer (e.g. background worker run) has no memory; hash dedup catches it.
                val scan = WatchedFolderImporter(rig.session, JvmWatchedFolder(watchDir), { false }).scanOnce()
                scan.imported shouldBe 0
                scan.duplicates shouldBe 1
            } finally {
                rig.close()
            }
        }

    @Test
    fun deleteAfterImportRemovesConsumedSources() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                stagePdf("scan.pdf", PdfFixtures.embeddedTextPdf("consume me"))
                val scan = WatchedFolderImporter(rig.session, JvmWatchedFolder(watchDir), { true }).scanOnce()
                scan.imported shouldBe 1
                Files.exists(watchDir.resolve("scan.pdf")) shouldBe false
            } finally {
                rig.close()
            }
        }

    @Test
    fun skipsFilesStillBeingWritten() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                // Fresh mtime = inside the quiet window; must not be touched this sweep.
                Files.write(watchDir.resolve("hot.pdf"), PdfFixtures.embeddedTextPdf("still writing"))
                val scan = WatchedFolderImporter(rig.session, JvmWatchedFolder(watchDir), { false }).scanOnce()
                scan shouldBe WatchedFolderScan(0, 0, 0)
            } finally {
                rig.close()
            }
        }
}

private class BlockingOcrEngine : OcrEngine {
    override val available = true
    override val name = "blocking-test"
    val started = CompletableDeferred<Unit>()
    val allowFinish = CompletableDeferred<Unit>()
    val finished = CompletableDeferred<Unit>()

    override suspend fun recognizePng(
        png: ByteArray,
        lang: String,
    ): String {
        started.complete(Unit)
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                allowFinish.await()
                finished.complete(Unit)
            }
        }
    }
}
