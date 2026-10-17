package dev.npn.core.app

import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.FsEvent
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import dev.npn.core.model.ReviewMode
import dev.npn.core.pdf.PdfFixtures
import dev.npn.core.rules.AutoMatchRule
import dev.npn.core.util.NpnLog
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.xmp.PdfBoxXmpIO
import dev.npn.core.xmp.XmpParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.test.Test

class SessionSmokeTest {
    @Test
    fun openSessionAndRescanFindsFixtures() =
        appSessionTest { rig ->
            rig.writePdf("one.pdf", PdfFixtures.embeddedTextPdf("first document"))
            rig.writePdf("two.pdf", PdfFixtures.embeddedTextPdf("second document"))

            val report = rig.session.rescan()
            report.added shouldBe 2

            val docs =
                rig.session.documents
                    .observeDocuments(LibraryFilter(), LibrarySort())
                    .first()
            docs shouldHaveSize 2
        }

    @Test
    fun importPdfBytesDedupsToDuplicate() =
        appSessionTest { rig ->
            val bytes = PdfFixtures.embeddedTextPdf("importable content here")
            val first = rig.session.importPdfBytes(bytes, "imported.pdf")
            first.shouldBeInstanceOf<ImportOutcome.Imported>()

            val second = rig.session.importPdfBytes(bytes, "imported-again.pdf")
            second.shouldBeInstanceOf<ImportOutcome.Duplicate>()
        }

    /** One canonical path per hash, however many imports of the same bytes race each other. */
    @Test
    fun concurrentImportsOfTheSameBytesImportItOnce() =
        appSessionTest { rig ->
            val bytes = PdfFixtures.embeddedTextPdf("imported from two places at once")
            val outcomes =
                Executors.newFixedThreadPool(6).asCoroutineDispatcher().use { threads ->
                    withContext(threads) {
                        (1..6).map { async { rig.session.importPdfBytes(bytes, "same.pdf") } }.awaitAll()
                    }
                }

            outcomes.filterIsInstance<ImportOutcome.Imported>() shouldHaveSize 1
            outcomes.filterIsInstance<ImportOutcome.Duplicate>() shouldHaveSize 5
            rig.fs
                .listAllIndexable()
                .filter { !it.isDirectory } shouldHaveSize 1
        }

    @Test
    fun bakedCopyBytesContainsBakedTag() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("bakeable"))
            rig.session.rescan()
            val id = rig.fs.sha256("a.pdf")
            rig.session.meta.addTag(listOf(id), "receipt")

            val baked = rig.session.content.bakedCopyBytes(id)
            baked.shouldNotBeNull()

            val io = PdfBoxXmpIO()
            val xmp = io.readXmp(baked)
            xmp.shouldNotBeNull()
            val parsed = XmpParser.parse(xmp)
            parsed.tags shouldBe listOf("receipt")
        }

    @Test
    fun importImagesAsPdf() =
        appSessionTest { rig ->
            val img = PdfFixtures.jpegBytes()
            val outcome = rig.session.importImagesAsPdf(listOf(img), "scan.pdf")
            outcome.shouldBeInstanceOf<ImportOutcome.Imported>()
        }

    /**
     * The Android activity rescans on the app scope whenever it resumes, which includes coming back
     * from the folder picker that is about to swap the library. The environment closes the database
     * right after [LibrarySessionImpl.close], so close must not return while that rescan still runs.
     */
    @Test
    fun closeWaitsForARescanStartedOutsideTheSession() =
        appSessionTest { rig ->
            rig.writePdf("one.pdf", PdfFixtures.embeddedTextPdf("first document"))
            val outside = async { runCatchingCancellable { rig.session.rescan() } }
            runCurrent()

            rig.session.close()

            val result = outside.await()
            if (result.isFailure) {
                result.exceptionOrNull().shouldBeInstanceOf<IllegalStateException>()
            }
            shouldThrow<IllegalStateException> { rig.session.rescan() }
        }

    /** Background work has no caller to tell, so a failure there must reach the log, not vanish. */
    @Test
    fun aFailingBackgroundRescanIsLogged() =
        runTest {
            val reported = CompletableDeferred<Throwable?>()
            val log =
                NpnLog { _, message, error ->
                    if (message == "Trash auto-purge failed") reported.complete(error)
                }
            AppSessionRig
                .create(backgroundScope, log = log) { fs ->
                    object : LibraryFileSystem by fs {
                        override suspend fun listAllUnder(relPath: String): List<FileMeta> =
                            if (relPath == NpnPaths.TRASH_DIR) error("trash unreadable") else fs.listAllUnder(relPath)
                    }
                }.use { rig ->
                    rig.session.start()

                    reported.await().shouldNotBeNull()
                }
        }

    /** A name the library cannot address, as the watcher reports it, is passed over; watching goes on. */
    @Test
    fun anUnsafePathFromTheWatcherLeavesTheWatchRunning() =
        runTest {
            val events = Channel<FsEvent>(Channel.UNLIMITED)
            AppSessionRig
                .create(backgroundScope) { fs ->
                    object : LibraryFileSystem by fs {
                        override fun watch(): Flow<FsEvent> = events.receiveAsFlow()
                    }
                }.use { rig ->
                    rig.session.start()
                    // The start's own rescan is done, so only the watch can find what comes next.
                    rig.session.lastScanReport.first { it != null }
                    rig.writePdf("later.pdf", PdfFixtures.embeddedTextPdf("later document"))
                    val id = rig.docId("later.pdf")

                    events.send(FsEvent("A: Taxes/x.pdf"))
                    events.send(FsEvent("later.pdf"))

                    rig.session.documents
                        .observeDoc(id)
                        .first { it != null }
                        .shouldNotBeNull()
                }
        }

    /** Asked for without an endpoint, AI OCR changes nothing and says why, in an enum the UI words. */
    @Test
    fun aiOcrWithoutAnEndpointChangesNothingAndSaysWhy() =
        appSessionTest { rig ->
            rig.writePdf("one.pdf", PdfFixtures.embeddedTextPdf("first document"))
            rig.session.rescan()
            val id = rig.docId("one.pdf")

            rig.session.ocr.ocrWithAi(id) shouldBe AiOcrOutcome.NO_ENDPOINT
            rig.db
                .documentDao()
                .getByDocId(id)
                ?.ocrEngineOverride shouldBe null
            (
                rig.deviceDb
                    .ocrQueueDao()
                    .getByDocId(id)
                    ?.aiRequested == true
            ) shouldBe false

            rig.session.ocr.ocrWithAi("f".repeat(64)) shouldBe AiOcrOutcome.DOCUMENT_UNAVAILABLE
        }

    /** A closed session stays closed: nothing restarts it, and no write reaches its database. */
    @Test
    fun closeIsFinalAndEveryLaterCallFailsFast() =
        appSessionTest { rig ->
            rig.writePdf("one.pdf", PdfFixtures.embeddedTextPdf("first document"))
            rig.session.rescan()
            val id = rig.docId("one.pdf")
            rig.session.close()

            val calls: List<suspend () -> Unit> =
                listOf(
                    { rig.session.start() },
                    { rig.session.ocr.reOcr(id) },
                    { rig.session.ocr.setOcrEngine(id, "embedded") },
                    { rig.session.ocr.ocrWithAi(id) },
                    { rig.session.reviewConfig.setReviewMode(ReviewMode.UNTAGGED_ONLY) },
                    { rig.session.aiEditQueue.discardEditRun("run") },
                    { rig.session.work.drainOcr() },
                    { rig.session.content.bakeInPlace(id) },
                    { rig.session.meta.addTag(listOf(id), "tag") },
                    { rig.session.meta.removeTag(listOf(id), "tag") },
                    { rig.session.meta.setReviewed(listOf(id), true) },
                    { rig.session.meta.setField(id, FieldKeys.TITLE, "Title") },
                    { rig.session.meta.setField(listOf(id), FieldKeys.TITLE, "Title") },
                    { rig.session.meta.applyEdits(listOf(id), addTags = listOf("tag")) },
                    { rig.session.tagAdmin.renameTag("tag", "other") },
                    { rig.session.tagAdmin.mergeTag("tag", "other") },
                    { rig.session.tagAdmin.deleteTag("tag") },
                    { rig.session.tagAdmin.setTagStyle("tag", "#ff0000", null) },
                    { rig.session.docLifecycle.trashDoc(id) },
                    { rig.session.docLifecycle.restoreDoc(id) },
                    { rig.session.docLifecycle.restoreDocs(listOf(id)) },
                    { rig.session.docLifecycle.deleteDocPermanently(id) },
                    { rig.session.docLifecycle.deleteDocsPermanently(listOf(id)) },
                    { rig.session.docLifecycle.renameFile(id, "renamed.pdf") },
                    { rig.session.docLifecycle.moveFile(id, "elsewhere") },
                    {
                        rig.session.fieldRegistry.upsert(
                            FieldDef("extra", "Extra", FieldType.TEXT, false, false, 0),
                        )
                    },
                    { rig.session.fieldRegistry.remove(FieldKeys.TITLE, clearValues = true) },
                    { rig.session.fieldRegistry.reorder(listOf(FieldKeys.TITLE)) },
                    { rig.session.rules.save(AutoMatchRule(id = "", name = "rule", pattern = "first")) },
                    { rig.session.rules.setEnabled("rule", enabled = true) },
                    { rig.session.rules.delete("rule") },
                )
            for (call in calls) {
                shouldThrow<IllegalStateException> { call() }.message shouldBe "Library session is closed"
            }
            // A content read has nothing to show either, and says so the way it always does.
            rig.session.content.thumbnailPath(id) shouldBe null
            rig.session.content.pageCount(id) shouldBe null
            // The re-OCR request never reached the queue.
            (
                rig.deviceDb
                    .ocrQueueDao()
                    .getByDocId(id)
                    ?.rerun == true
            ) shouldBe false
        }

    /**
     * A delete for good removes the bytes after its tombstone, outside the writer lock. Closing the
     * session meanwhile cancels and waits for it, rather than leave it to use the databases the
     * environment closes next.
     */
    @Test
    fun closeWaitsForADeleteForGoodStillRemovingItsFile() =
        runTest {
            val trashListing = BeforeTrashListing()
            AppSessionRig.create(backgroundScope, files = trashListing::over).use { rig ->
                rig.writePdf("one.pdf", PdfFixtures.embeddedTextPdf("first document"))
                rig.session.rescan()
                val id = rig.docId("one.pdf")
                rig.session.docLifecycle.trashDoc(id)
                val listing = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                trashListing.next = {
                    listing.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
                val delete = async { runCatchingCancellable { rig.session.docLifecycle.deleteDocPermanently(id) } }
                listing.await()

                val closing = async { rig.session.close() }
                try {
                    runCurrent()
                    closing.isCompleted shouldBe false
                } finally {
                    release.complete(Unit)
                }
                closing.await()
                delete.await().exceptionOrNull().shouldBeInstanceOf<IllegalStateException>()
            }
        }
}
