package dev.npn.core.app

import dev.npn.core.model.FieldKeys
import dev.npn.core.pdf.PdfFixtures
import dev.npn.core.util.RecordingLog
import dev.npn.core.xmp.BakeRefusedException
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class BakeInPlaceTest {
    @Test
    fun aBakedDocumentKeepsItsMetadataOnTheNewHash() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val before = rig.docId("a.pdf")
                rig.session.meta.applyEdits(
                    listOf(before),
                    addTags = listOf("filed"),
                    fields = mapOf(FieldKeys.TITLE to "Water bill", FieldKeys.CORRESPONDENT to "Aquafin"),
                )

                rig.session.content.bakeInPlace(before) shouldBe BakeOutcome.Baked

                val after = rig.docId("a.pdf")
                after shouldNotBe before
                rig.db
                    .documentDao()
                    .getByDocId(after)
                    ?.relPath shouldBe "a.pdf"
                rig.db
                    .documentFieldDao()
                    .fieldsForDocs(listOf(after))
                    .associate { it.fieldKey to it.fieldValue } shouldBe
                    mapOf(FieldKeys.TITLE to "Water bill", FieldKeys.CORRESPONDENT to "Aquafin")
                rig.db
                    .tagDao()
                    .tagsForDocument(after) shouldContainExactly listOf("filed")
                rig.db
                    .documentDao()
                    .getByDocId(before)
                    ?.deleted shouldBe true
            } finally {
                rig.close()
            }
        }

    /**
     * A baked document's original bytes are an earlier version of it, which no journal event makes
     * live again: importing them adds no file and journals nothing, and finds the baked document.
     */
    @Test
    fun theOriginalBytesOfABakedDocumentAreADuplicateOfIt() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                val original = PdfFixtures.embeddedTextPdf("alpha")
                val (before, after) = baked(rig, original)
                val superseded = rig.db.documentDao().getByDocId(before)
                val objects = rig.journalObjectCount()

                rig.session.ingest.importPdfBytes(original, "a.pdf") shouldBe ImportOutcome.Duplicate(after, "a.pdf")

                libraryPdfs(rig) shouldContainExactly listOf("a.pdf")
                rig.journalObjectCount() shouldBe objects
                rig.session.rescan().added shouldBe 0
                rig.db.documentDao().getByDocId(before) shouldBe superseded
                libraryPdfs(rig) shouldContainExactly listOf("a.pdf")
            } finally {
                rig.close()
            }
        }

    /** With the baked document in the trash, the library shows nothing those bytes stand for: the import is refused. */
    @Test
    fun theOriginalBytesOfABakedDocumentInTheTrashAreRefused() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                val original = PdfFixtures.embeddedTextPdf("alpha")
                val (before, after) = baked(rig, original)
                rig.session.docLifecycle.trashDoc(after)
                val superseded = rig.db.documentDao().getByDocId(before)
                val objects = rig.journalObjectCount()

                rig.session.ingest.importPdfBytes(original, "a.pdf") shouldBe
                    ImportOutcome.Failed(ImportFailure.SUPERSEDED)

                libraryPdfs(rig).shouldBeEmpty()
                rig.journalObjectCount() shouldBe objects
                rig.session.rescan().added shouldBe 0
                rig.db.documentDao().getByDocId(before) shouldBe superseded
                rig.db
                    .documentDao()
                    .getByDocId(after)
                    ?.trashed shouldBe true
                libraryPdfs(rig).shouldBeEmpty()
            } finally {
                rig.close()
            }
        }

    /**
     * A file in the library holding a baked document's original bytes gets no row: no rescan counts
     * it as added, offers it for a relink, or rewrites the row of the document it was.
     */
    @Test
    fun aFileWithTheOriginalBytesOfABakedDocumentGetsNoRow() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                val original = PdfFixtures.embeddedTextPdf("alpha")
                val (before, _) = baked(rig, original)
                val superseded = rig.db.documentDao().getByDocId(before)
                rig.writePdf("copy.pdf", original)

                repeat(2) {
                    val report = rig.session.rescan()
                    report.added shouldBe 0
                    report.relinkCandidates.shouldBeEmpty()
                }

                rig.db.documentDao().getByDocId(before) shouldBe superseded
            } finally {
                rig.close()
            }
        }

    @Test
    fun aDocumentThatIsGoneIsNotBaked() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")
                rig.session.docLifecycle.trashDoc(id)

                rig.session.content.bakeInPlace(id) shouldBe BakeOutcome.Failed(BakeFailure.DOCUMENT_UNAVAILABLE)
            } finally {
                rig.close()
            }
        }

    /** The file at a document's path was replaced and the index has not looked yet: that file is not baked. */
    @Test
    fun aDocumentWhosePathHoldsAnotherFileIsNotBaked() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")
                val replacement = PdfFixtures.embeddedTextPdf("replacement")
                rig.fs.delete("a.pdf")
                rig.writePdf("a.pdf", replacement)

                rig.session.content.bakeInPlace(id) shouldBe BakeOutcome.Failed(BakeFailure.DOCUMENT_UNAVAILABLE)

                rig.fs.readBytes("a.pdf") shouldBe replacement
            } finally {
                rig.close()
            }
        }

    @Test
    fun aFileThatCannotCarryMetadataIsRefusedAndLeftAlone() =
        runTest {
            val log = RecordingLog()
            val rig = AppSessionRig.create(backgroundScope, log = log)
            try {
                val bytes = "not really a pdf".encodeToByteArray()
                rig.writePdf("broken.pdf", bytes)
                rig.session.rescan()
                val id = rig.docId("broken.pdf")

                rig.session.content.bakeInPlace(id) shouldBe BakeOutcome.Failed(BakeFailure.UNSUPPORTED_PDF)

                rig.docId("broken.pdf") shouldBe id
                log.entries.any { it.error is BakeRefusedException } shouldBe true
            } finally {
                rig.close()
            }
        }

    /** Bake the document [original] makes at `a.pdf`; returns its id before the bake and after. */
    private suspend fun baked(
        rig: AppSessionRig,
        original: ByteArray,
    ): Pair<String, String> {
        rig.writePdf("a.pdf", original)
        rig.session.rescan()
        val before = rig.docId("a.pdf")
        rig.session.content.bakeInPlace(before) shouldBe BakeOutcome.Baked
        return before to rig.docId("a.pdf")
    }

    private suspend fun libraryPdfs(rig: AppSessionRig) =
        rig.fs
            .listAllIndexable()
            .filter { !it.isDirectory }
            .map { it.relPath }
}
