package dev.npn.core.app

import dev.npn.core.model.ReviewMode
import dev.npn.core.pdf.PdfFixtures
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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.test.Test

class SessionSmokeTest {
    @Test
    fun openSessionAndRescanFindsFixtures() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("one.pdf", PdfFixtures.embeddedTextPdf("first document"))
                rig.writePdf("two.pdf", PdfFixtures.embeddedTextPdf("second document"))

                val report = rig.session.rescan()
                report.added shouldBe 2

                val docs =
                    rig.session.documents
                        .observeDocuments(LibraryFilter(), LibrarySort())
                        .first()
                docs shouldHaveSize 2
            } finally {
                rig.close()
            }
        }

    @Test
    fun importPdfBytesDedupsToDuplicate() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                val bytes = PdfFixtures.embeddedTextPdf("importable content here")
                val first = rig.session.importPdfBytes(bytes, "imported.pdf")
                first.shouldBeInstanceOf<ImportOutcome.Imported>()

                val second = rig.session.importPdfBytes(bytes, "imported-again.pdf")
                second.shouldBeInstanceOf<ImportOutcome.Duplicate>()
            } finally {
                rig.close()
            }
        }

    /** One canonical path per hash, however many imports of the same bytes race each other. */
    @Test
    fun concurrentImportsOfTheSameBytesImportItOnce() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
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
            } finally {
                rig.close()
            }
        }

    @Test
    fun bakedCopyBytesContainsBakedTag() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
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
            } finally {
                rig.close()
            }
        }

    @Test
    fun importImagesAsPdf() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                val img = PdfFixtures.jpegBytes()
                val outcome = rig.session.importImagesAsPdf(listOf(img), "scan.pdf")
                outcome.shouldBeInstanceOf<ImportOutcome.Imported>()
            } finally {
                rig.close()
            }
        }

    /**
     * The Android activity rescans on the app scope whenever it resumes, which includes coming back
     * from the folder picker that is about to swap the library. The environment closes the database
     * right after [LibrarySessionImpl.close], so close must not return while that rescan still runs.
     */
    @Test
    fun closeWaitsForARescanStartedOutsideTheSession() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("one.pdf", PdfFixtures.embeddedTextPdf("first document"))
                val outside = async { runCatchingCancellable { rig.session.rescan() } }
                runCurrent()

                rig.session.close()

                val result = outside.await()
                if (result.isFailure) {
                    result.exceptionOrNull().shouldBeInstanceOf<IllegalStateException>()
                }
                shouldThrow<IllegalStateException> { rig.session.rescan() }
            } finally {
                rig.close()
            }
        }

    /** Background work has no caller to tell, so a failure there must reach the log, not vanish. */
    @Test
    fun aFailingBackgroundRescanIsLogged() =
        runTest {
            val reported = CompletableDeferred<Throwable?>()
            val log =
                NpnLog { _, message, error ->
                    if (message == "Reading the trash retention failed") reported.complete(error)
                }
            val rig =
                AppSessionRig.create(backgroundScope, log = log, trashRetentionDays = { error("settings unreadable") })
            try {
                rig.session.start()

                reported.await().shouldNotBeNull()
            } finally {
                rig.close()
            }
        }

    /** Asked for without an endpoint, AI OCR changes nothing and says why, in an enum the UI words. */
    @Test
    fun aiOcrWithoutAnEndpointChangesNothingAndSaysWhy() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
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
            } finally {
                rig.close()
            }
        }

    /** A closed session stays closed: nothing restarts it, and no write reaches its database. */
    @Test
    fun closeIsFinalAndEveryLaterCallFailsFast() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
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
            } finally {
                rig.close()
            }
        }
}
