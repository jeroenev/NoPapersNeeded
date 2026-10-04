package dev.npn.core.ocr

import dev.npn.core.app.DocumentRepositoryImpl
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.OcrQueueEntity
import dev.npn.core.fs.OcrSidecarFiles
import dev.npn.core.index.MutableClock
import dev.npn.core.pdf.PdfFixtures
import dev.npn.core.work.QueueDrainer
import dev.npn.core.work.backoffMs
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class OcrPipelineTest {
    private lateinit var rig: OcrPipelineRig
    private val fs get() = rig.fs
    private val db get() = rig.db

    @BeforeTest
    fun setup() {
        rig = OcrPipelineRig()
    }

    @AfterTest
    fun teardown() {
        rig.close()
    }

    @Test
    fun embeddedTextDocUsesEmbeddedEngineWithNoOcrCalls() =
        runTest {
            val fake = FakeOcrEngine(isAvailable = true, name = "mlkit")
            val bytes = PdfFixtures.embeddedTextPdf("invoice 42 acme corporation")
            val docId = rig.stageDoc("docs/inv.pdf", bytes)

            rig.pipeline("devA", fake).processDoc(docId)

            fake.calls shouldBe 0
            db.documentDao().getByDocId(docId)!!.ocrState shouldBe OcrPipeline.OCR_DONE
            db.ftsDao().searchDocIds("invoice") shouldContainExactly listOf(docId)

            // Sidecar written once for this device+engine, engine=embedded.
            val sidecar = OcrSidecarCodec.decode(OcrSidecarFiles(fs).read(docId, "devA.embedded"))
            sidecar.engine shouldBe OcrPipeline.ENGINE_EMBEDDED
            sidecar.pages.size shouldBe 2
            db.documentDao().getByDocId(docId)!!.ocrEngine shouldBe OcrPipeline.ENGINE_EMBEDDED
        }

    @Test
    fun imageOnlyDocRunsOcrPerPage() =
        runTest {
            val fake = FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "scanned words here")
            val bytes = PdfFixtures.imageOnlyPdf()
            val docId = rig.stageDoc("scan.pdf", bytes)

            rig.pipeline("devA", fake).processDoc(docId)

            fake.calls shouldBe 1 // one page
            val sidecar = OcrSidecarCodec.decode(OcrSidecarFiles(fs).read(docId, "devA.mlkit"))
            sidecar.engine shouldBe "mlkit"
            db.documentDao().getByDocId(docId)!!.ocrState shouldBe OcrPipeline.OCR_DONE
            db.documentDao().getByDocId(docId)!!.ocrEngine shouldBe "mlkit"
            db.ftsDao().searchDocIds("scanned") shouldContainExactly listOf(docId)
        }

    @Test
    fun sidecarRecordsTheLanguagesTheEngineActuallyRead() =
        runTest {
            // Asked for Dutch and English, but only the English model is installed.
            val fake = FakeOcrEngine(isAvailable = true, name = "tesseract", installed = { "eng" })
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())

            rig.pipeline("devA", fake, config = OcrConfig(lang = "nld+eng")).processDoc(docId)

            fake.lastLang shouldBe "eng"
            OcrSidecarCodec.decode(OcrSidecarFiles(fs).read(docId, "devA.tesseract")).lang shouldBe "eng"
        }

    @Test
    fun secondDeviceAdoptsSidecarWithoutOcr() =
        runTest {
            // Device A OCRs an image-only doc.
            val fakeA = FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "alpha bravo")
            val bytes = PdfFixtures.imageOnlyPdf()
            val docId = rig.stageDoc("scan.pdf", bytes)
            rig.pipeline("devA", fakeA).processDoc(docId)
            fakeA.calls shouldBeGreaterThan 0

            // Device B: fresh DB, SAME library dir (so the sidecar is visible).
            val dbB = rig.openSecondIndex()
            val deviceB = rig.openSecondDevice()
            try {
                dbB.documentDao().upsert(
                    DocumentEntity(
                        docId = docId,
                        relPath = "scan.pdf",
                        fileName = "scan.pdf",
                        size = bytes.size.toLong(),
                        mtime = 1000,
                        addedAt = 1000,
                    ),
                )
                deviceB.ocrQueueDao().upsert(OcrQueueEntity(docId, "pending", updatedAt = 1))

                val fakeB = FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "should-not-run")
                rig.pipeline("devB", fakeB, dbB, deviceB.ocrQueueDao()).processDoc(docId)

                fakeB.calls shouldBe 0
                dbB.documentDao().getByDocId(docId)!!.ocrState shouldBe OcrPipeline.OCR_ADOPTED
                dbB.ftsDao().searchDocIds("alpha") shouldContainExactly listOf(docId)
            } finally {
                dbB.close()
                deviceB.close()
            }
        }

    @Test
    fun processNextDrivesQueueStateToDone() =
        runTest {
            val fake = FakeOcrEngine(isAvailable = true, name = "mlkit")
            val docId = rig.stageDoc("docs/inv.pdf", PdfFixtures.embeddedTextPdf("invoice 42 acme corporation"))

            rig.pipeline("devA", fake).processNext() shouldBe true
            val entry =
                rig.deviceDb
                    .ocrQueueDao()
                    .observeQueue()
                    .first()
                    .first { it.docId == docId }
            entry.state shouldBe OcrPipeline.STATE_DONE
        }

    @Test
    fun failureIncrementsAttemptsAndFailsAfterMax() =
        runTest {
            val throwing = ThrowingOcrEngine()
            val bytes = PdfFixtures.imageOnlyPdf()
            val docId = rig.stageDoc("scan.pdf", bytes)
            val clock = MutableClock()
            val pipe = rig.pipeline("devA", throwing, clock = clock)

            // attempt 1 -> pending (attempts=1)
            pipe.processDoc(docId)
            var entry =
                rig.deviceDb
                    .ocrQueueDao()
                    .observeQueue()
                    .first()
                    .first { it.docId == docId }
            entry.attempts shouldBe 1
            entry.state shouldBe OcrPipeline.STATE_PENDING
            entry.lastError shouldBe "ocr failed: boom"
            entry.nextAttemptAt shouldBe clock.now + backoffMs(1)
            // A failed page is a failed run: nothing is published for it, blank or otherwise.
            OcrSidecarFiles(fs).listForHash(docId) shouldBe emptyList()

            // Not retried at once: the row waits out its backoff before the next drain takes it.
            pipe.processNext() shouldBe false
            throwing.calls shouldBe 1
            clock.advance(backoffMs(1))
            pipe.processNext() shouldBe true
            entry =
                rig.deviceDb
                    .ocrQueueDao()
                    .observeQueue()
                    .first()
                    .first { it.docId == docId }
            entry.attempts shouldBe 2
            entry.state shouldBe OcrPipeline.STATE_PENDING

            pipe.processNext() shouldBe false
            clock.advance(backoffMs(2))
            pipe.processNext() shouldBe true
            entry =
                rig.deviceDb
                    .ocrQueueDao()
                    .observeQueue()
                    .first()
                    .first { it.docId == docId }
            entry.attempts shouldBe 3
            entry.state shouldBe OcrPipeline.STATE_FAILED
            db.documentDao().getByDocId(docId)!!.ocrState shouldBe OcrPipeline.OCR_FAILED
        }

    @Test
    fun invalidPdfFailsGracefully() =
        runTest {
            val fake = FakeOcrEngine(isAvailable = true, name = "mlkit")
            fs.writeBytesOnce("broken.pdf", "not a pdf".encodeToByteArray())
            val docId = fs.sha256("broken.pdf")
            db.documentDao().upsert(
                DocumentEntity(
                    docId = docId,
                    relPath = "broken.pdf",
                    fileName = "broken.pdf",
                    size = 10,
                    mtime = 1,
                    addedAt = 1,
                ),
            )
            rig.deviceDb.ocrQueueDao().upsert(OcrQueueEntity(docId, "pending", updatedAt = 1))

            rig.pipeline("devA", fake).processDoc(docId)
            val entry =
                rig.deviceDb
                    .ocrQueueDao()
                    .observeQueue()
                    .first()
                    .first { it.docId == docId }
            entry.attempts shouldBe 1
            entry.state shouldBe OcrPipeline.STATE_PENDING // < maxAttempts
        }

    @Test
    fun reconcileAdoptsForeignSidecarAndClearsQueueWhenNoBetterEngine() =
        runTest {
            val bytes = PdfFixtures.imageOnlyPdf()
            val docId = rig.stageDoc("scan.pdf", bytes)
            // Foreign sidecar with the SAME rank as this device's engine: adopt, no upgrade run.
            rig.writeForeignSidecar(docId, dev = "otherdev", engine = "tesseract", text = "foreign charlie text")

            rig.pipeline("devA", FakeOcrEngine(isAvailable = true, name = "tesseract")).reconcileSidecars()

            db.documentDao().getByDocId(docId)!!.ocrState shouldBe OcrPipeline.OCR_ADOPTED
            db.documentDao().getByDocId(docId)!!.ocrEngine shouldBe "tesseract"
            db.ftsDao().searchDocIds("charlie") shouldContainExactly listOf(docId)
            rig.deviceDb
                .ocrQueueDao()
                .observeQueue()
                .first()
                .none { it.docId == docId } shouldBe true
        }

    @Test
    fun reconcileQueuesUpgradeWhenOwnEngineOutranksStoredVersions() =
        runTest {
            val bytes = PdfFixtures.imageOnlyPdf()
            val docId = rig.stageDoc("scan.pdf", bytes)
            rig.writeForeignSidecar(docId, dev = "otherdev", engine = "tesseract", text = "foreign charlie text")

            // mlkit outranks tesseract: adopt the foreign text now, but queue an own OCR run.
            rig.pipeline("devA", FakeOcrEngine(isAvailable = true, name = "mlkit")).reconcileSidecars()

            db.documentDao().getByDocId(docId)!!.ocrState shouldBe OcrPipeline.OCR_ADOPTED
            db.ftsDao().searchDocIds("charlie") shouldContainExactly listOf(docId)
            val entry =
                rig.deviceDb
                    .ocrQueueDao()
                    .observeQueue()
                    .first()
                    .first { it.docId == docId }
            entry.state shouldBe OcrPipeline.STATE_PENDING
        }

    @Test
    fun processDocUpgradesToOwnBetterEngineAndIndexesIt() =
        runTest {
            val bytes = PdfFixtures.imageOnlyPdf()
            val docId = rig.stageDoc("scan.pdf", bytes)
            rig.writeForeignSidecar(docId, dev = "otherdev", engine = "tesseract", text = "foreign charlie text")

            val fake = FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "upgraded delta text")
            rig.pipeline("devA", fake).processDoc(docId)

            fake.calls shouldBeGreaterThan 0
            // Both versions co-exist on disk; the better one is indexed.
            OcrSidecarCodec.decode(OcrSidecarFiles(fs).read(docId, "devA.mlkit")).engine shouldBe "mlkit"
            OcrSidecarCodec.decode(OcrSidecarFiles(fs).read(docId, "otherdev")).engine shouldBe "tesseract"
            db.documentDao().getByDocId(docId)!!.ocrEngine shouldBe "mlkit"
            db.documentDao().getByDocId(docId)!!.ocrState shouldBe OcrPipeline.OCR_DONE
            db.ftsDao().searchDocIds("delta") shouldContainExactly listOf(docId)
            db.ftsDao().searchDocIds("charlie") shouldBe emptyList()
        }

    @Test
    fun selectionPrefersHighestRankedEngineAcrossSidecars() =
        runTest {
            val bytes = PdfFixtures.imageOnlyPdf()
            val docId = rig.stageDoc("scan.pdf", bytes)
            rig.writeForeignSidecar(docId, dev = "deskdev", engine = "tesseract", text = "tesseract words")
            rig.writeForeignSidecar(docId, dev = "phonedev", engine = "mlkit", text = "mlkit words")
            rig.writeForeignSidecar(docId, dev = "olddev", engine = "mixed", text = "legacy mixed words")

            // Own engine does not outrank mlkit -> pure adoption of the best stored version.
            rig.pipeline("devA", FakeOcrEngine(isAvailable = true, name = "mlkit")).processDoc(docId)

            db.documentDao().getByDocId(docId)!!.ocrEngine shouldBe "mlkit"
            db.ftsDao().searchDocIds("mlkit") shouldContainExactly listOf(docId)
            db.ftsDao().searchDocIds("tesseract") shouldBe emptyList()
            db.ftsDao().searchDocIds("legacy") shouldBe emptyList()
        }

    @Test
    fun overridePinsSearchToChosenEngineAndClearingRestoresBest() =
        runTest {
            val bytes = PdfFixtures.imageOnlyPdf()
            val docId = rig.stageDoc("scan.pdf", bytes)
            rig.writeForeignSidecar(docId, dev = "deskdev", engine = "tesseract", text = "tesseract words")
            rig.writeForeignSidecar(docId, dev = "phonedev", engine = "mlkit", text = "mlkit words")
            val pipe = rig.pipeline("devA", FakeOcrEngine(isAvailable = false, name = "mlkit"))
            pipe.processDoc(docId)
            db.documentDao().getByDocId(docId)!!.ocrEngine shouldBe "mlkit"

            // Simulate the folded override (the Indexer projects SystemProperty.OCR_ENGINE here).
            setOverride(docId, "tesseract")
            pipe.reselect(docId)
            db.documentDao().getByDocId(docId)!!.ocrEngine shouldBe "tesseract"
            db.ftsDao().searchDocIds("tesseract") shouldContainExactly listOf(docId)
            db.ftsDao().searchDocIds("mlkit") shouldBe emptyList()

            // Clearing the override re-selects the best-ranked version.
            setOverride(docId, null)
            pipe.reselect(docId)
            db.documentDao().getByDocId(docId)!!.ocrEngine shouldBe "mlkit"
            db.ftsDao().searchDocIds("mlkit") shouldContainExactly listOf(docId)
        }

    @Test
    fun overrideForMissingEngineFallsBackToBestVersion() =
        runTest {
            val bytes = PdfFixtures.imageOnlyPdf()
            val docId = rig.stageDoc("scan.pdf", bytes)
            rig.writeForeignSidecar(docId, dev = "deskdev", engine = "tesseract", text = "tesseract words")
            setOverride(docId, "ai") // no ai sidecar exists (yet)

            rig.pipeline("devA", FakeOcrEngine(isAvailable = false, name = "mlkit")).processDoc(docId)

            db.documentDao().getByDocId(docId)!!.ocrEngine shouldBe "tesseract"
            db.ftsDao().searchDocIds("tesseract") shouldContainExactly listOf(docId)
        }

    @Test
    fun ownEmbeddedSidecarBlocksPointlessUpgradeRuns() =
        runTest {
            val bytes = PdfFixtures.imageOnlyPdf()
            val docId = rig.stageDoc("scan.pdf", bytes)
            rig.writeForeignSidecar(docId, dev = "otherdev", engine = "tesseract", text = "foreign charlie text")
            // This device already established every page has a text layer: engine choice is moot.
            val own =
                OcrSidecar(
                    doc = docId,
                    dev = "devA",
                    engine = "embedded",
                    lang = "eng",
                    createdAt = 1,
                    pages = listOf("own embedded text"),
                )
            OcrSidecarFiles(fs).writeOnce(docId, "devA.embedded", OcrSidecarCodec.encode(own))

            val fake = FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "should-not-run")
            val pipe = rig.pipeline("devA", fake)
            pipe.reconcileSidecars()
            pipe.processDoc(docId)

            fake.calls shouldBe 0
            db.documentDao().getByDocId(docId)!!.ocrEngine shouldBe "tesseract"
            rig.deviceDb
                .ocrQueueDao()
                .observeQueue()
                .first()
                .none { it.docId == docId && it.state == OcrPipeline.STATE_PENDING } shouldBe true
        }

    /**
     * End-to-end guard for the read seam: the pipeline stores whatever engine label a sidecar
     * carries, including one this build has no [OcrEngineId] for, and [DocumentRepositoryImpl]
     * must surface that as [OcrEngineRef.Unknown] rather than "no engine". Collapsing the two
     * would make an indexed document read as un-indexed and an active pin read as unpinned.
     */
    @Test
    fun unknownEngineSurvivesTheReadSeamAsAnUnknownRefNotAsAbsent() =
        runTest {
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            // The only stored version carries a label this build does not know.
            rig.writeForeignSidecar(docId, dev = "olddev", engine = "mixed", text = "legacy mixed words")

            rig.pipeline("devA", FakeOcrEngine(isAvailable = false, name = "mlkit")).processDoc(docId)
            db.documentDao().getByDocId(docId)!!.ocrEngine shouldBe "mixed"

            val repo = DocumentRepositoryImpl(db)
            repo
                .observeDoc(docId)
                .first()!!
                .doc.ocrEngine shouldBe OcrEngineRef.Unknown("mixed")

            // A pin on that same unknown engine stays visible and keeps its label, so re-applying
            // it writes back the identical column value instead of silently clearing the pin.
            setOverride(docId, "mixed")
            val pinned = repo.observeDoc(docId).first()!!.doc
            pinned.ocrEngineOverride shouldBe OcrEngineRef.Unknown("mixed")
            pinned.ocrEngineOverride?.label shouldBe "mixed"
        }

    /** Simulate the journal fold projecting a SystemProperty.OCR_ENGINE override onto the row. */
    private suspend fun setOverride(
        docId: String,
        engine: String?,
    ) {
        val doc = db.documentDao().getByDocId(docId)!!
        db.documentDao().upsert(doc.copy(ocrEngineOverride = engine))
    }

    @Test
    fun workerDrainsQueue() =
        runTest {
            val fake = FakeOcrEngine(isAvailable = true, name = "mlkit")
            rig.stageDoc("a.pdf", PdfFixtures.embeddedTextPdf("invoice 42 acme corporation"))
            rig.stageDoc("b.pdf", PdfFixtures.embeddedTextPdf("another invoice document here"))

            val p = rig.pipeline("devA", fake)
            val worker = QueueDrainer(this) { p.processNext() }
            try {
                worker.drain() shouldBe 2
                rig.deviceDb
                    .ocrQueueDao()
                    .observeQueue()
                    .first()
                    .all { it.state == OcrPipeline.STATE_DONE } shouldBe true
            } finally {
                worker.close()
            }
        }
}

private class ThrowingOcrEngine : OcrEngine {
    override val name: String = "mlkit"

    var calls = 0
        private set

    override suspend fun available(): Boolean = true

    override suspend fun recognizePng(
        png: ByteArray,
        lang: String,
    ): String {
        calls++
        throw RuntimeException("boom")
    }
}
