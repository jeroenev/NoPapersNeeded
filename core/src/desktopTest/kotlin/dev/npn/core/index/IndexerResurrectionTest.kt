package dev.npn.core.index

import dev.npn.core.journal.PendingOp
import dev.npn.core.ocr.OcrState
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

/** A rescan that finds a trashed or deleted document's own bytes in place brings that document back. */
class IndexerResurrectionTest {
    private val rig = IndexRig.create()

    @AfterTest fun teardown() {
        rig.close()
    }

    @Test
    fun trashResurrectionPreferredFile() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            rig.rescan()
            val docId =
                rig.db
                    .documentDao()
                    .rowAtPath("a.pdf")
                    .shouldNotBeNull()
                    .docId

            // Journal says trashed with origPath a.pdf, but the file is still present on disk.
            val writer = rig.writer("dev-main")
            rig.clock.advance()
            writer.commit(listOf(PendingOp.docTrash(docId, "a.pdf")))

            val resurrected = ArrayList<Resurrected>()
            rig.rescan { resurrected += it }
            resurrected.map { it.docId } shouldContainExactly listOf(docId)
            val row =
                rig.db
                    .documentDao()
                    .getByDocId(docId)
                    .shouldNotBeNull()
            row.trashed.shouldBeFalse()
            row.missing.shouldBeFalse()
        }

    /**
     * A deleted document whose own bytes come back is that document returning, not a new one: the
     * row keeps the date added and the OCR bookkeeping a fresh document would not have.
     */
    @Test
    fun deletedResurrectionKeepsTheRowsBookkeeping() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            rig.rescan()
            val docId =
                rig.db
                    .documentDao()
                    .rowAtPath("a.pdf")
                    .shouldNotBeNull()
                    .docId
            rig.db.documentDao().updateOcrState(docId, OcrState.DONE.column, pageCount = 3, engine = "tesseract")
            val addedAt =
                rig.db
                    .documentDao()
                    .getByDocId(docId)
                    .shouldNotBeNull()
                    .addedAt

            // Deleted for good with its file gone; then a sync tool puts the very bytes back.
            val writer = rig.writer("dev-main")
            rig.clock.advance(50)
            writer.commit(listOf(PendingOp.docDelete(docId)))
            Files.delete(rig.libRoot.resolve("a.pdf"))
            rig.rescan()
            rig.writePdf("a.pdf", "alpha")

            val resurrected = ArrayList<Resurrected>()
            rig.rescan { resurrected += it }

            resurrected.map { it.docId } shouldContainExactly listOf(docId)
            resurrected.single().wasDeleted.shouldBeTrue()
            val returned =
                rig.db
                    .documentDao()
                    .getByDocId(docId)
                    .shouldNotBeNull()
            returned.deleted.shouldBeFalse()
            returned.missing.shouldBeFalse()
            returned.addedAt shouldBe addedAt
            returned.ocrState shouldBe OcrState.DONE.column
            returned.pageCount shouldBe 3
        }
}
