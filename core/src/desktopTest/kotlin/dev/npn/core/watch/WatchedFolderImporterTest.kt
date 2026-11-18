package dev.npn.core.watch

import dev.npn.core.app.AppSessionRig
import dev.npn.core.app.ImportOutcome
import dev.npn.core.app.ImportTally
import dev.npn.core.app.IngestFacade
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySort
import dev.npn.core.app.appSessionTest
import dev.npn.core.fs.ExternalSource
import dev.npn.core.ocr.OcrEngine
import dev.npn.core.ocr.OcrState
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.nulls.shouldNotBeNull
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
        appSessionTest { rig ->
            stagePdf("scan-a.pdf", PdfFixtures.embeddedTextPdf("watched one"))
            stagePdf("scan-b.pdf", PdfFixtures.embeddedTextPdf("watched two"))
            Files.write(watchDir.resolve("notes.txt"), byteArrayOf(1, 2, 3))

            val importer = WatchedFolderImporter(rig.session, JvmWatchedFolder(watchDir), { false })

            val first = importer.scanOnce()
            first.imported shouldBe 2
            first.failed shouldBe 0

            // Second sweep: nothing new, sources kept (delete-after-import off).
            importer.scanOnce() shouldBe ImportTally()
            Files.exists(watchDir.resolve("scan-a.pdf")) shouldBe true

            val docs =
                rig.session.documents
                    .observeDocuments(LibraryFilter(), LibrarySort())
                    .first()
            docs.map { it.doc.fileName }.sorted() shouldBe listOf("scan-a.pdf", "scan-b.pdf")
            // Every auto-import is queued for OCR like a manual import.
            docs.all { it.doc.ocrState == OcrState.PENDING || it.doc.ocrState == OcrState.DONE } shouldBe true
        }

    /**
     * The bytes of a document whose file went missing are the only copy there is: the import brings
     * the document back instead of calling them a duplicate, so deleting the source loses nothing.
     */
    @Test
    fun aMissingDocumentsBytesAreKeptWhenTheSourceIsDeletedAfterImport() =
        appSessionTest { rig ->
            val bytes = PdfFixtures.embeddedTextPdf("lost")
            rig.writePdf("lost.pdf", bytes)
            rig.session.rescan()
            val id = rig.docId("lost.pdf")
            Files.delete(rig.libRoot.resolve("lost.pdf"))
            rig.session.rescan()
            stagePdf("lost.pdf", bytes)

            val tally = WatchedFolderImporter(rig.session, JvmWatchedFolder(watchDir), { true }).scanOnce()

            tally.imported shouldBe 1
            Files.exists(watchDir.resolve("lost.pdf")) shouldBe false
            val row =
                rig.db
                    .documentDao()
                    .getByDocId(id)
                    .shouldNotBeNull()
            row.missing shouldBe false
            rig.fs.sha256(row.relPath) shouldBe id
        }

    @Test
    fun closingSessionAwaitsInFlightOcrBeforeClosingDatabase() =
        runTest {
            val ocr = BlockingOcrEngine()
            val rig = AppSessionRig.create(backgroundScope, ocr)
            var closed = false
            try {
                // The queues drain once the session has had its first rescan; start() also watches
                // the OCR queue, which is what drains an import.
                rig.session.start()
                rig.session.rescan()
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
        appSessionTest { rig ->
            stagePdf("scan.pdf", PdfFixtures.embeddedTextPdf("same bytes"))
            WatchedFolderImporter(rig.session, JvmWatchedFolder(watchDir), { false })
                .scanOnce()
                .imported shouldBe 1

            // A brand-new importer (e.g. background worker run) has no memory; hash dedup catches it.
            val scan = WatchedFolderImporter(rig.session, JvmWatchedFolder(watchDir), { false }).scanOnce()
            scan.imported shouldBe 0
            scan.duplicates shouldBe 1
        }

    @Test
    fun deleteAfterImportRemovesConsumedSources() =
        appSessionTest { rig ->
            stagePdf("scan.pdf", PdfFixtures.embeddedTextPdf("consume me"))
            val scan = WatchedFolderImporter(rig.session, JvmWatchedFolder(watchDir), { true }).scanOnce()
            scan.imported shouldBe 1
            Files.exists(watchDir.resolve("scan.pdf")) shouldBe false
        }

    /**
     * A watched folder inside the library holds the library's own files, which the rescan indexes. A
     * sweep there imports nothing and deletes nothing: with delete-after-import on, a file found to be
     * a document's would be deleted as its duplicate, and it is that document's only copy.
     */
    @Test
    fun aWatchedFolderInsideTheLibraryIsNeverSwept() =
        appSessionTest { rig ->
            val scans = rig.libRoot.resolve("scans")
            rig.writePdf("scans/scan.pdf", PdfFixtures.embeddedTextPdf("a scan the library holds"))
            Files.setLastModifiedTime(
                scans.resolve("scan.pdf"),
                FileTime.fromMillis(System.currentTimeMillis() - 60_000),
            )
            rig.session.rescan()
            val id = rig.docId("scans/scan.pdf")
            rig.writePdf("scans/new.pdf", PdfFixtures.embeddedTextPdf("a scan the rescan has not seen"))
            Files.setLastModifiedTime(
                scans.resolve("new.pdf"),
                FileTime.fromMillis(System.currentTimeMillis() - 60_000),
            )

            val importer =
                WatchedFolderImporter(
                    rig.session,
                    JvmWatchedFolder(scans),
                    deleteAfterImport = { true },
                    inLibrary = { liesInLibrary(scans.toString(), rig.libRoot.toString()) },
                )

            importer.scanOnce() shouldBe ImportTally()
            Files.exists(scans.resolve("scan.pdf")) shouldBe true
            Files.exists(scans.resolve("new.pdf")) shouldBe true
            rig.db
                .documentDao()
                .getByDocId(id)
                ?.relPath shouldBe "scans/scan.pdf"
            rig.exists("inbox/new.pdf") shouldBe false
        }

    /**
     * The scanner writes its next scan under the same name after the first was imported and before
     * the cleanup ran. That file was never imported: it stays, for the next sweep to import.
     */
    @Test
    fun deleteAfterImportLeavesAFileWrittenUnderTheSourcesNameSince() =
        appSessionTest { rig ->
            stagePdf("scan.pdf", PdfFixtures.embeddedTextPdf("first scan"))
            val next = PdfFixtures.embeddedTextPdf("the next scan, under the same name")
            val rewriting =
                object : IngestFacade by rig.session {
                    override suspend fun importPdf(
                        source: ExternalSource,
                        suggestedName: String,
                    ): ImportOutcome =
                        rig.session.importPdf(source, suggestedName).also {
                            val scan = watchDir.resolve("scan.pdf")
                            Files.write(scan, next)
                            Files.setLastModifiedTime(scan, FileTime.fromMillis(System.currentTimeMillis() - 1_000))
                        }
                }

            val scan = WatchedFolderImporter(rewriting, JvmWatchedFolder(watchDir), { true }).scanOnce()

            scan.imported shouldBe 1
            Files.readAllBytes(watchDir.resolve("scan.pdf")) shouldBe next
        }

    /**
     * The library file the import placed is deleted from outside before the cleanup ran: the source
     * is then the document's last copy, and stays.
     */
    @Test
    fun deleteAfterImportKeepsTheSourceWhenTheLibraryFileWentMeanwhile() =
        appSessionTest { rig ->
            val bytes = PdfFixtures.embeddedTextPdf("a scan deleted from the library at once")
            stagePdf("scan.pdf", bytes)
            val deleting =
                object : IngestFacade by rig.session {
                    override suspend fun importPdf(
                        source: ExternalSource,
                        suggestedName: String,
                    ): ImportOutcome =
                        rig.session.importPdf(source, suggestedName).also {
                            Files.delete(rig.libRoot.resolve((it as ImportOutcome.Imported).relPath))
                        }
                }

            val scan = WatchedFolderImporter(deleting, JvmWatchedFolder(watchDir), { true }).scanOnce()

            scan.imported shouldBe 1
            Files.readAllBytes(watchDir.resolve("scan.pdf")) shouldBe bytes
        }

    /**
     * A provider that reports no mtime (SAF's lastModified can be 0): the scanner writes its next scan
     * under the same name, of the same size, after the first was imported. Only bytes the import put
     * in the library go: that file stays, and the same importer imports it next, while a source that
     * still holds what was imported is deleted.
     */
    @Test
    fun withNoMtimeASourceGoesOnlyWhileItHoldsTheImportedBytes() =
        appSessionTest { rig ->
            val first = PdfFixtures.embeddedTextPdf("scan one")
            val next = PdfFixtures.embeddedTextPdf("scan two")
            next.size shouldBe first.size
            stagePdf("scan.pdf", first)
            stagePdf("other.pdf", PdfFixtures.embeddedTextPdf("left as it was"))
            var rewritten = false
            val rewriting =
                object : IngestFacade by rig.session {
                    override suspend fun importPdf(
                        source: ExternalSource,
                        suggestedName: String,
                    ): ImportOutcome =
                        rig.session.importPdf(source, suggestedName).also {
                            if (suggestedName == "scan.pdf" && !rewritten) {
                                rewritten = true
                                Files.write(watchDir.resolve("scan.pdf"), next)
                            }
                        }
                }
            val importer = WatchedFolderImporter(rewriting, NoMtime(JvmWatchedFolder(watchDir)), { true })

            importer.scanOnce().imported shouldBe 2

            Files.readAllBytes(watchDir.resolve("scan.pdf")) shouldBe next
            Files.exists(watchDir.resolve("other.pdf")) shouldBe false
            importer.scanOnce().imported shouldBe 1
            Files.exists(watchDir.resolve("scan.pdf")) shouldBe false
        }

    /**
     * A provider that reports no mtime, with delete-after-import off: the source stays in the folder,
     * and the importer imports it once, as one with an mtime, rather than again at every sweep.
     */
    @Test
    fun withNoMtimeASourceThatStaysIsImportedOnce() =
        appSessionTest { rig ->
            stagePdf("scan.pdf", PdfFixtures.embeddedTextPdf("stays"))
            var imports = 0
            val counting =
                object : IngestFacade by rig.session {
                    override suspend fun importPdf(
                        source: ExternalSource,
                        suggestedName: String,
                    ): ImportOutcome = rig.session.importPdf(source, suggestedName).also { imports++ }
                }
            val importer = WatchedFolderImporter(counting, NoMtime(JvmWatchedFolder(watchDir)), { false })

            importer.scanOnce().imported shouldBe 1
            importer.scanOnce() shouldBe ImportTally()

            imports shouldBe 1
            Files.exists(watchDir.resolve("scan.pdf")) shouldBe true
        }

    /** [folder] as a provider that reports no mtime lists it. */
    private class NoMtime(
        private val folder: WatchedFolder,
    ) : WatchedFolder by folder {
        override suspend fun listPdfs(): List<WatchedFolderFile> = folder.listPdfs().map { it.copy(mtimeMillis = 0) }
    }

    /**
     * A source is still the file listed while its size and mtime are; where the provider reported no
     * mtime for the listing (SAF may not), nothing proves it.
     */
    @Test
    fun aSourceIsTheFileListedWhileItsSizeAndMtimeAreUnchanged() {
        val listed = WatchedFolderFile("scan.pdf", "scan.pdf", 10, 5_000, ExternalSource(watchDir.resolve("scan.pdf")))

        listed.unchangedAt(size = 10, mtimeMillis = 5_000) shouldBe true
        listed.unchangedAt(size = 11, mtimeMillis = 5_000) shouldBe false
        listed.unchangedAt(size = 10, mtimeMillis = 6_000) shouldBe false
        listed.unchangedAt(size = 10, mtimeMillis = 0) shouldBe false
        val noMtime = listed.copy(mtimeMillis = 0)
        noMtime.unchangedAt(size = 10, mtimeMillis = 6_000) shouldBe false
        noMtime.unchangedAt(size = 10, mtimeMillis = 0) shouldBe false
    }

    @Test
    fun skipsFilesStillBeingWritten() =
        appSessionTest { rig ->
            // Fresh mtime = inside the quiet window; must not be touched this sweep.
            Files.write(watchDir.resolve("hot.pdf"), PdfFixtures.embeddedTextPdf("still writing"))
            val scan = WatchedFolderImporter(rig.session, JvmWatchedFolder(watchDir), { false }).scanOnce()
            scan shouldBe ImportTally()
        }
}

private class BlockingOcrEngine : OcrEngine {
    override val name = "blocking-test"

    override suspend fun available() = true

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
