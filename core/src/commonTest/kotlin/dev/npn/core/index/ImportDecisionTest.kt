package dev.npn.core.index

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.isLive
import dev.npn.core.db.missingSentinel
import dev.npn.core.fs.FileMeta
import dev.npn.core.journal.propTest
import dev.npn.core.ocr.OcrState
import io.kotest.matchers.shouldBe
import io.kotest.property.Exhaustive
import io.kotest.property.checkAll
import io.kotest.property.exhaustive.collection
import kotlin.test.Test

/**
 * What an import decides about its bytes ([ImportDecision]), over every row their hash can have
 * (none, shown, trashed, deleted, missing; at a path or a sentinel; any OCR state), whether that
 * row's file holds them, and whether a relink superseded them.
 */
class ImportDecisionTest {
    private val hash = "h"
    private val target = "inbox/new name.pdf"
    private val stat = FileMeta(target, "new name.pdf", size = 10, mtimeMillis = 5_000, isDirectory = false)

    private val existings: List<DocumentEntity?> =
        listOf(null) +
            listOf("inbox/a.pdf", missingSentinel(hash)).flatMap { relPath ->
                OcrState.entries.flatMap { ocr ->
                    (0 until 4).map { lifecycle ->
                        DocumentEntity(
                            docId = hash,
                            relPath = relPath,
                            fileName = "a.pdf",
                            size = 10,
                            mtime = 1_000,
                            addedAt = 1,
                            title = "Kept",
                            trashed = lifecycle == 1,
                            trashedOrigPath = "inbox/a.pdf".takeIf { lifecycle == 1 },
                            trashedAt = 7L.takeIf { lifecycle == 1 },
                            deleted = lifecycle == 2,
                            missing = lifecycle == 3,
                            ocrState = ocr.column,
                        )
                    }
                }
            }

    private class Case(
        val existing: DocumentEntity?,
        val inPlace: String?,
        val successor: String,
    ) {
        override fun toString() = "existing=$existing inPlace=$inPlace successor=$successor"
    }

    private val cases =
        existings.flatMap { existing ->
            // Only a row's own path can hold its bytes in place.
            listOfNotNull(null, existing?.relPath).flatMap { inPlace ->
                listOf(hash, "successor").map { successor -> Case(existing, inPlace, successor) }
            }
        }

    @Test
    fun anImportIsADuplicateOnlyOfAShownDocumentWhoseFileHoldsItsBytes() =
        propTest {
            checkAll(Exhaustive.collection(cases)) { case ->
                val existing = case.existing
                val duplicate = ImportDecision.duplicate(existing, case.inPlace)
                if (existing != null && existing.isLive && case.inPlace != null) {
                    duplicate shouldBe ImportResult.Duplicate(hash, existing.relPath)
                } else {
                    duplicate shouldBe null
                }
            }
        }

    /** Bytes a relink superseded bring nothing back; any others are placed, as a return when a row knows them. */
    @Test
    fun bytesThatAreNoDuplicateArePlacedUnlessARelinkSupersededThem() =
        propTest {
            checkAll(Exhaustive.collection(cases)) { case ->
                val plan = ImportDecision.place(hash, case.existing, case.inPlace, case.successor)
                if (case.successor != hash) {
                    plan shouldBe ImportPlan.Superseded(case.successor)
                } else {
                    plan shouldBe ImportPlan.Place(case.existing, case.inPlace)
                }
            }
        }

    /**
     * The row an import writes is live at its target with the stat of the bytes it placed, keeps a
     * returning document's metadata and date added, and queues for OCR only text not indexed yet.
     */
    @Test
    fun thePlacedDocumentIsLiveAtItsTargetWithThePlacedStatAndKeepsWhatItHad() =
        propTest {
            checkAll(Exhaustive.collection(existings)) { existing ->
                val imported = ImportDecision.row(hash, target, existing, stat, now = 9_000)
                val row = imported.row
                check(row.isLive) { "$existing comes back hidden: $row" }
                check(row.trashedOrigPath == null && row.trashedAt == null) { "$existing keeps its trash: $row" }
                row.docId shouldBe hash
                row.relPath shouldBe target
                row.fileName shouldBe "new name.pdf"
                row.size shouldBe 10
                row.mtime shouldBe 5_000
                row.addedAt shouldBe (existing?.addedAt ?: 9_000)
                row.title shouldBe existing?.title
                val indexed =
                    existing != null &&
                        OcrState.fromColumn(existing.ocrState) in listOf(OcrState.DONE, OcrState.ADOPTED)
                imported.queueOcr shouldBe !indexed
                row.ocrState shouldBe if (indexed) existing.ocrState else OcrState.PENDING.column
                imported.reindexName shouldBe (existing != null)
            }
        }

    @Test
    fun supersededBytesAreADuplicateOnlyOfAShownSuccessorThatHoldsItsBytes() {
        val successor = DocumentEntity("s", "s.pdf", "s.pdf", size = 1, mtime = 1, addedAt = 1)
        ImportDecision.supersededDuplicate(successor, holds = true) shouldBe ImportResult.Duplicate("s", "s.pdf")
        ImportDecision.supersededDuplicate(successor, holds = false) shouldBe null
        ImportDecision.supersededDuplicate(successor.copy(trashed = true), holds = true) shouldBe null
        ImportDecision.supersededDuplicate(null, holds = false) shouldBe null
    }
}
