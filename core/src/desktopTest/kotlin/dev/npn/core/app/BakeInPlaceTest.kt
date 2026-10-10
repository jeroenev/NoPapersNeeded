package dev.npn.core.app

import dev.npn.core.db.searchDocIds
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.model.FieldKeys
import dev.npn.core.pathrules.StoragePathRule
import dev.npn.core.pdf.PdfFixtures
import dev.npn.core.util.RecordingLog
import dev.npn.core.xmp.BakeRefusedException
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
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
     * An edit to a baked document projects it with what it inherited, as a rescan does: marking it
     * reviewed keeps its title, correspondent and tag in the index and in search.
     */
    @Test
    fun anEditToABakedDocumentKeepsWhatItInherited() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                val (before, after) = bakeFiled(rig)

                rig.session.meta.setReviewed(listOf(after), true)

                fieldsOf(rig, after) shouldBe FILED
                rig.db
                    .tagDao()
                    .tagsForDocument(after) shouldContainExactly listOf("filed")
                rig.db.ftsDao().searchDocIds("water") shouldContain after
                // Projected with it, the original stays superseded, as a rescan leaves it.
                val superseded = rig.db.documentDao().getByDocId(before)
                superseded?.deleted shouldBe true
                rig.session.rescan()
                rig.db.documentDao().getByDocId(before) shouldBe superseded
            } finally {
                rig.close()
            }
        }

    /** The storage-path rule after an edit to a baked document files it by what it inherited. */
    @Test
    fun anEditToABakedDocumentFilesItByWhatItInherited() =
        runTest {
            val rule = StoragePathRule("{correspondent}/{title}.pdf", enabled = true)
            val rig = AppSessionRig.create(backgroundScope, storagePath = rule)
            try {
                val filed = "Aquafin/Water bill.pdf"
                val (_, after) = bakeFiled(rig)
                rig.docId(filed) shouldBe after

                rig.session.meta.addTag(listOf(after), "x")

                rig.db
                    .documentDao()
                    .getByDocId(after)
                    ?.relPath shouldBe filed
                rig.docId(filed) shouldBe after
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

    /**
     * Another document's bytes replace the file while the bake waits for the writer lock (a sync
     * tool): the bake reads them, finds they are not the document's and refuses, with nothing
     * written or relinked.
     */
    @Test
    fun aFileReplacedBeforeTheBakeReadsItIsNotBaked() = replacedDuringABake(Replacing.Moment.BEFORE_READ)

    /**
     * Another document's bytes replace the file while the bake rewrites its copy: the bake leaves
     * them where they are and removes its own rewrite, rather than deleting them to put it in place.
     */
    @Test
    fun aFileReplacedWhileItIsBakedIsLeftAlone() = replacedDuringABake(Replacing.Moment.AFTER_REWRITE)

    private fun replacedDuringABake(moment: Replacing.Moment) =
        runTest {
            val other = PdfFixtures.embeddedTextPdf("another document, written over the one being baked")
            val replacing = Replacing("a.pdf", other, moment)
            val rig = AppSessionRig.create(backgroundScope, files = replacing::over)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                rig.session.work.drainOcr()
                val id = rig.docId("a.pdf")
                val objects = rig.journalObjectCount()
                replacing.armed = true

                rig.session.content.bakeInPlace(id) shouldBe BakeOutcome.Failed(BakeFailure.DOCUMENT_UNAVAILABLE)

                rig.fs.readBytes("a.pdf") shouldBe other
                rig.journalObjectCount() shouldBe objects
                rig.exists(NpnPaths.Temp.bakePath("a.pdf")) shouldBe false
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

    /**
     * Give the document at `a.pdf` the [FILED] fields and the tag `filed`, then bake it where they
     * filed it; returns its id before the bake and after.
     */
    private suspend fun bakeFiled(rig: AppSessionRig): Pair<String, String> {
        rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
        rig.session.rescan()
        val before = rig.docId("a.pdf")
        rig.session.meta.applyEdits(listOf(before), addTags = listOf("filed"), fields = FILED)
        val path = checkNotNull(rig.db.documentDao().getByDocId(before)).relPath
        rig.session.content.bakeInPlace(before) shouldBe BakeOutcome.Baked
        return before to rig.docId(path)
    }

    private suspend fun fieldsOf(
        rig: AppSessionRig,
        docId: String,
    ) = rig.db
        .documentFieldDao()
        .fieldsForDocs(listOf(docId))
        .associate { it.fieldKey to it.fieldValue }

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

    /**
     * A library filesystem over which, once [armed], [bytes] replace the file at [path] once, at
     * [moment] of a bake: as another app or a sync tool would write over it.
     */
    private class Replacing(
        private val path: String,
        private val bytes: ByteArray,
        private val moment: Moment,
    ) {
        enum class Moment {
            /** Right before the file at [path] is read. */
            BEFORE_READ,

            /** Right after the bake wrote its rewritten copy beside it. */
            AFTER_REWRITE,
        }

        @Volatile var armed = false

        fun over(fs: LibraryFileSystem): LibraryFileSystem =
            object : LibraryFileSystem by fs {
                override suspend fun readBytes(relPath: String): ByteArray {
                    if (moment == Moment.BEFORE_READ && relPath == path) replace(fs)
                    return fs.readBytes(relPath)
                }

                override suspend fun writeBytesOnce(
                    relPath: String,
                    bytes: ByteArray,
                ) {
                    fs.writeBytesOnce(relPath, bytes)
                    if (moment == Moment.AFTER_REWRITE && relPath == NpnPaths.Temp.bakePath(path)) replace(fs)
                }
            }

        private suspend fun replace(fs: LibraryFileSystem) {
            if (!armed) return
            armed = false
            fs.delete(path)
            fs.writeBytesOnce(path, bytes)
        }
    }

    private companion object {
        val FILED = mapOf(FieldKeys.TITLE to "Water bill", FieldKeys.CORRESPONDENT to "Aquafin")
    }
}
