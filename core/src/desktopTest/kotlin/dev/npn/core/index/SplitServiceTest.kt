package dev.npn.core.index

import dev.npn.core.db.DocumentEntity
import dev.npn.core.pdf.PdfBoxEngine
import dev.npn.core.pdf.PdfEngine
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
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
            DocumentEntity(
                docId = rig.fs.sha256("contract.pdf"),
                relPath = "contract.pdf",
                fileName = "contract.pdf",
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

    @Test
    fun aFailedLaterPartTakesBackTheOnesAlreadyImported() =
        runTest {
            val original = stageOriginal()
            val engine = FailingPart(2, IllegalStateException("bad page"))
            val split = SplitService(rig.fs, engine, rig.importService, rig.db.documentDao())

            val result = split.split(original.docId, listOf(0..0, 1..1))

            result.shouldBeInstanceOf<SplitService.Result.Failed>().message shouldContain "part 2"
            libraryPdfs() shouldBe listOf("contract.pdf")
            rig.db
                .documentDao()
                .getAllLive()
                .map { it.docId } shouldBe listOf(original.docId)
            rig.db
                .ocrQueueDao()
                .observeQueue()
                .first()
                .shouldBeEmpty()
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
}
