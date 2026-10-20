package dev.npn.core.app

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.missingSentinel
import dev.npn.core.fs.FileMeta
import dev.npn.core.index.FileMove
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.propTest
import dev.npn.core.model.SystemProperty
import io.kotest.matchers.shouldBe
import io.kotest.property.Exhaustive
import io.kotest.property.checkAll
import io.kotest.property.exhaustive.collection
import kotlin.test.Test

/**
 * What a trash, restore or delete for good of one document decides ([LifecyclePlan]), over every
 * lifecycle its row can have and every path a trash can have recorded for it.
 */
class LifecyclePlanTest {
    private val docId = "doc"

    /** Every row: shown, trashed (from a document path, a sentinel or no path), deleted, missing. */
    private val rows =
        listOf("inbox/a.pdf", missingSentinel(docId))
            .flatMap { relPath ->
                listOf(null, "old/a.pdf", missingSentinel(docId), "notes.txt").flatMap { origPath ->
                    (0 until 4).map { lifecycle ->
                        DocumentEntity(
                            docId = docId,
                            relPath = relPath,
                            fileName = "a.pdf",
                            size = 10,
                            mtime = 1_000,
                            addedAt = 1,
                            title = "Kept",
                            trashed = lifecycle == 1,
                            trashedOrigPath = origPath.takeIf { lifecycle == 1 },
                            trashedAt = 7L.takeIf { lifecycle == 1 },
                            deleted = lifecycle == 2,
                            missing = lifecycle == 3,
                        )
                    }
                }
            }.distinct()

    @Test
    fun onlyAShownOrMissingDocumentIsTrashedAndOnlyATrashedOneRestoredOrDeleted() =
        propTest {
            checkAll(Exhaustive.collection(rows)) { row ->
                LifecyclePlan.trashes(row) shouldBe (!row.trashed && !row.deleted)
                LifecyclePlan.restores(row) shouldBe row.trashed
                LifecyclePlan.deletes(row) shouldBe row.trashed
            }
        }

    /** A trash moves the file only when its own bytes go to the trash, and records the path it left. */
    @Test
    fun aTrashRecordsWhereTheDocumentWasAndMovesItsFileOnlyWhenItIsItsOwn() =
        propTest {
            checkAll(Exhaustive.collection(rows.filter(LifecyclePlan::trashes))) { row ->
                for (trashPath in listOf(null, ".npn/trash/a.pdf")) {
                    val change = LifecyclePlan.trash(row, trashPath)
                    change.ops shouldBe listOf(PendingOp.docTrash(docId, row.relPath))
                    change.row shouldBe row.copy(trashed = true, trashedOrigPath = row.relPath, missing = false)
                    change.move shouldBe trashPath?.let { FileMove(row.relPath, it) }
                    change.readBack shouldBe true
                }
            }
        }

    /** A restore goes back where the document was trashed from, else its row's path, else the inbox. */
    @Test
    fun aRestoreGoesBackToADocumentPathOnly() =
        propTest {
            checkAll(Exhaustive.collection(rows.filter(LifecyclePlan::restores))) { row ->
                val recorded = row.trashedOrigPath ?: row.relPath
                val documentPath = recorded.endsWith(".pdf") && !recorded.startsWith(".npn/")
                LifecyclePlan.restorePath(row) shouldBe if (documentPath) recorded else "inbox/a.pdf"
            }
        }

    /**
     * A restored row is shown at its final path, with the stat of the trash entry it moved there, or
     * the one it had when none moved; it keeps its metadata, and the journal records the path.
     */
    @Test
    fun aRestoredRowIsShownAtItsPathWithTheStatOfWhatMovedThere() =
        propTest {
            checkAll(Exhaustive.collection(rows.filter(LifecyclePlan::restores))) { row ->
                val entryStat =
                    FileMeta(".npn/trash/a.pdf", "a.pdf", size = 10, mtimeMillis = 3_000, isDirectory = false)
                for ((entry, stat) in listOf(null to null, entryStat.relPath to entryStat)) {
                    val change = LifecyclePlan.restore(row, "inbox/a (restored).pdf", entry, stat)
                    change.ops shouldBe
                        listOf(
                            PendingOp.docRestore(docId),
                            PendingOp.propSet(docId, SystemProperty.STORAGE_PATH, "inbox/a (restored).pdf"),
                        )
                    change.row shouldBe
                        row.copy(
                            trashed = false,
                            trashedOrigPath = null,
                            trashedAt = null,
                            relPath = "inbox/a (restored).pdf",
                            fileName = "a (restored).pdf",
                            mtime = if (stat == null) 1_000 else 3_000,
                            missing = false,
                        )
                    change.move shouldBe entry?.let { FileMove(it, "inbox/a (restored).pdf") }
                }
            }
        }

    /** A delete for good publishes the tombstone and moves nothing: the bytes go once it stands. */
    @Test
    fun aDeleteForGoodHidesTheRowAndMovesNothing() =
        propTest {
            checkAll(Exhaustive.collection(rows.filter(LifecyclePlan::deletes))) { row ->
                val change = LifecyclePlan.delete(row)
                change.ops shouldBe listOf(PendingOp.docDelete(docId))
                change.row shouldBe row.copy(deleted = true, trashed = false, trashedAt = null, missing = false)
                change.move shouldBe null
            }
        }
}
