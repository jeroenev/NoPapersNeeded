package dev.npn.core.index

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.documentEntity
import dev.npn.core.db.fieldTypes
import dev.npn.core.db.isLive
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.pdf.PdfBoxEngine
import dev.npn.core.pdf.PdfEngine
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** A split is all or nothing: the parts of one that fails partway are taken back. */
class SplitServiceTest {
    private val rig = IndexRig.create()

    @AfterTest
    fun tearDown() = rig.close()

    /** PDFBox, except that making the part numbered [failingPart] (1-based) throws [failure]. */
    private class FailingPart(
        private val failingPart: Int,
        private val failure: Throwable,
        private val real: PdfEngine = PdfBoxEngine(),
    ) : PdfEngine by real {
        private var made = 0

        override suspend fun extractPages(
            bytes: ByteArray,
            pageIndices: List<Int>,
        ): ByteArray {
            made++
            if (made == failingPart) throw failure
            return real.extractPages(bytes, pageIndices)
        }
    }

    private suspend fun stageOriginal(): DocumentEntity {
        val bytes = PdfFixtures.embeddedTextPdf("first page", "second page")
        rig.fs.writeBytesOnce("contract.pdf", bytes)
        val row =
            documentEntity(
                rig.fs.sha256("contract.pdf"),
                "contract.pdf",
                size = bytes.size.toLong(),
                mtime = 1,
                addedAt = 1,
            )
        rig.db.documentDao().upsert(row)
        return row
    }

    private suspend fun libraryPdfs() =
        rig.fs
            .listAllIndexable()
            .filter { !it.isDirectory }
            .map { it.relPath }

    /** Move [part]'s file and row to [target], as a storage-path rule settling its metadata does. */
    private suspend fun IndexRig.movePart(
        part: ImportResult.Imported,
        target: String,
    ) {
        moveFile(part.relPath, target)
        val meta = checkNotNull(fs.stat(target))
        db.documentDao().relocate(part.docId, target, meta.name, meta.size, meta.mtimeMillis)
    }

    @Test
    fun aFailedLaterPartTakesBackTheOnesAlreadyImported() =
        runTest {
            val original = stageOriginal()
            val engine = FailingPart(2, IllegalStateException("bad page"))
            val split = SplitService(rig.fs, engine, rig.importService, rig.db.documentDao())

            val result = split.split(original.docId, listOf(0..0, 1..1))

            result.shouldBeInstanceOf<SplitService.Result.Failed>().reason shouldBe SplitFailure.NOT_COMPLETED
            libraryPdfs() shouldBe listOf("contract.pdf")
            rig.db
                .documentDao()
                .getAllLive()
                .map { it.docId } shouldBe listOf(original.docId)
            rig.deviceDb
                .ocrQueueDao()
                .observeQueue()
                .first()
                .shouldBeEmpty()
        }

    /** Parts go through the import's placement, so a deleted document's old path does not swallow one. */
    @Test
    fun aPartLandingOnThePathOfADeletedDocumentIsIndexed() =
        runTest {
            val original = stageOriginal()
            rig.db.documentDao().upsert(documentEntity("d".repeat(64), "contract (part 1).pdf", deleted = true))
            val split = SplitService(rig.fs, PdfBoxEngine(), rig.importService, rig.db.documentDao())

            val result = split.split(original.docId, listOf(0..0, 1..1))

            val parts = result.shouldBeInstanceOf<SplitService.Result.Done>().parts.map { it as ImportResult.Imported }
            parts.map { it.relPath } shouldBe listOf("contract (part 1).pdf", "contract (part 2).pdf")
            rig.db
                .documentDao()
                .getAllLive()
                .map { it.docId } shouldContainExactlyInAnyOrder parts.map { it.docId } + original.docId
        }

    /**
     * A part that metadata moved after the split (a storage-path rule) is taken back from where it
     * is now; the file another import put at the name it left is not touched.
     */
    @Test
    fun aRollBackFollowsAMovedPartAndSparesTheFileAtItsOldName() =
        runTest {
            val original = stageOriginal()
            val split = SplitService(rig.fs, PdfBoxEngine(), rig.importService, rig.db.documentDao())
            val result = split.split(original.docId, listOf(0..0, 1..1))
            val parts = result.shouldBeInstanceOf<SplitService.Result.Done>().parts
            val first = parts[0] as ImportResult.Imported
            rig.movePart(first, "acme/contract (part 1).pdf")
            rig.writePdf(first.relPath, "another document")

            split.rollBack(parts)

            libraryPdfs() shouldContainExactlyInAnyOrder listOf("contract.pdf", first.relPath)
            rig.db
                .documentDao()
                .getAllLive()
                .map { it.docId } shouldBe listOf(original.docId)
        }

    /** Taking a part back never deletes bytes that are not the part's, wherever its row points. */
    @Test
    fun aRollBackKeepsAFileThatNoLongerHoldsThePart() =
        runTest {
            val original = stageOriginal()
            val split = SplitService(rig.fs, PdfBoxEngine(), rig.importService, rig.db.documentDao())
            val result = split.split(original.docId, listOf(0..0, 1..1))
            val parts = result.shouldBeInstanceOf<SplitService.Result.Done>().parts
            val first = parts[0] as ImportResult.Imported
            rig.deleteFile(first.relPath)
            rig.writePdf(first.relPath, "replaced in place")

            split.rollBack(parts)

            libraryPdfs() shouldContainExactlyInAnyOrder listOf("contract.pdf", first.relPath)
            rig.db.documentDao().getByDocId(first.docId) shouldBe null
        }

    /**
     * A part whose bytes were a document gone from the library brought that document back: it was
     * the user's before the split, so a split that fails later leaves it, as it leaves a duplicate.
     */
    @Test
    fun aRollBackLeavesADocumentAPartBroughtBack() =
        runTest {
            rig.writePdf("contract (part 1).pdf", "first page")
            rig.rescan()
            val doc = rig.fs.sha256("contract (part 1).pdf")
            rig.deleteFile("contract (part 1).pdf")
            rig.rescan()
            val part =
                rig.importService
                    .importBytes("first page".encodeToByteArray(), "contract (part 1).pdf", "")
                    .shouldBeInstanceOf<ImportResult.Imported>()
            part.broughtBack shouldBe true

            SplitService(rig.fs, PdfBoxEngine(), rig.importService, rig.db.documentDao()).rollBack(listOf(part))

            rig.db
                .documentDao()
                .getByDocId(doc)
                ?.isLive shouldBe true
            rig.fs.sha256(part.relPath) shouldBe doc
        }

    @Test
    fun aCancelledSplitIsCancelledNotFailedAndTakesBackItsParts() =
        runTest {
            val original = stageOriginal()
            val engine = FailingPart(2, CancellationException("closing"))
            val split = SplitService(rig.fs, engine, rig.importService, rig.db.documentDao())

            assertFailsWith<CancellationException> { split.split(original.docId, listOf(0..0, 1..1)) }

            libraryPdfs() shouldBe listOf("contract.pdf")
        }

    /**
     * A split over the rig's library whose filesystem cancels [job] right after it places a file
     * whose name ends with [partName]: the split's caller going away while that part is imported.
     */
    private fun cancellingOnPlacement(
        partName: String,
        job: () -> Job,
    ): SplitService {
        val fs =
            object : LibraryFileSystem by rig.fs {
                override suspend fun move(
                    fromRelPath: String,
                    toRelPath: String,
                ) {
                    rig.fs.move(fromRelPath, toRelPath)
                    if (toRelPath.endsWith(partName)) job().cancel()
                }
            }
        val imports =
            ImportService(
                fs,
                rig.db,
                rig.deviceDb.ocrQueueDao(),
                rig.clock,
                fieldTypes = { rig.db.fieldDefDao().fieldTypes() },
            )
        return SplitService(fs, PdfBoxEngine(), imports, rig.db.documentDao())
    }

    /** A part placed while the split was cancelled is handed back to it, so its rollback takes that part too. */
    @Test
    fun aSplitCancelledWhileAPartIsPlacedTakesThatPartBack() =
        runTest {
            val original = stageOriginal()
            lateinit var job: Job
            val split = cancellingOnPlacement("(part 1).pdf") { job }

            job = launch { split.split(original.docId, listOf(0..0, 1..1)) }
            job.join()

            libraryPdfs() shouldBe listOf("contract.pdf")
            rig.db
                .documentDao()
                .getAllLive()
                .map { it.docId } shouldBe listOf(original.docId)
            rig.deviceDb
                .ocrQueueDao()
                .observeQueue()
                .first()
                .shouldBeEmpty()
        }

    /** Cancelled while its last part is placed, the split still returns every part, for its caller to take back. */
    @Test
    fun aSplitCancelledWhileItsLastPartIsPlacedReturnsThatPart() =
        runTest {
            val original = stageOriginal()
            lateinit var job: Job
            val split = cancellingOnPlacement("(part 2).pdf") { job }
            var result: SplitService.Result? = null

            job = launch { result = split.split(original.docId, listOf(0..0, 1..1)) }
            job.join()

            val parts = result.shouldBeInstanceOf<SplitService.Result.Done>().parts
            parts shouldHaveSize 2
            split.rollBack(parts)
            libraryPdfs() shouldBe listOf("contract.pdf")
        }
}
