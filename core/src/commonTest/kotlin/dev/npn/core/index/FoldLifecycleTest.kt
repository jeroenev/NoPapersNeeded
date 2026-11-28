package dev.npn.core.index

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.missingSentinel
import dev.npn.core.journal.FileStat
import dev.npn.core.journal.propTest
import io.kotest.matchers.shouldBe
import io.kotest.property.Exhaustive
import io.kotest.property.checkAll
import io.kotest.property.exhaustive.collection
import kotlin.test.Test

/**
 * How a fold projects a document's lifecycle ([FoldLifecycle]), over every combination of what the
 * journal says, what the row holds, the kind of fold and what a rescan found at the row's path and
 * at the path the document was trashed from.
 */
class FoldLifecycleTest {
    private val doc = "doc"
    private val origPath = "inbox/a.pdf"

    private class Case(
        val row: DocumentEntity,
        val journal: JournalLifecycle,
        val preserved: Boolean,
        val contentAt: Map<String, String>?,
        val originHeld: Boolean,
    ) {
        override fun toString() =
            "row(${row.relPath}, trashed=${row.trashed}, deleted=${row.deleted}, missing=${row.missing}) " +
                "journal(superseded=${journal.superseded}, trashed=${journal.trashed}, deleted=${journal.deleted}) " +
                "preserved=$preserved contentAt=$contentAt originHeld=$originHeld"
    }

    private val flags = listOf(false, true)

    /** Every row the document can have: at its origin, elsewhere or at its sentinel, with any flags. */
    private val rows =
        listOf(origPath, "other/b.pdf", missingSentinel(doc)).flatMap { relPath ->
            (0 until 8).map { bits ->
                DocumentEntity(
                    docId = doc,
                    relPath = relPath,
                    fileName = relPath.substringAfterLast('/'),
                    size = 1,
                    mtime = 1,
                    trashed = bits and 1 != 0,
                    deleted = bits and 2 != 0,
                    missing = bits and 4 != 0,
                )
            }
        }

    /** Everything the journal can say: superseded or not, shown, trashed or deleted. */
    private val journals =
        flags.flatMap { superseded ->
            listOf(null, "trashed", "deleted").map { hiddenBy ->
                JournalLifecycle(
                    superseded = superseded,
                    trashed = hiddenBy == "trashed",
                    deleted = hiddenBy == "deleted",
                    origPath = origPath.takeIf { hiddenBy == "trashed" },
                    hiddenAtMillis = if (hiddenBy != null) 500 else 0,
                )
            }
        }

    /**
     * Every kind of fold for [row]: a commit's, preserving or not, which knows no listing; and a
     * rescan's (which preserves nothing), finding nothing, the document's bytes at its row's path or
     * at its origin, or another document's at its row's path.
     */
    private fun folds(row: DocumentEntity): List<Pair<Boolean, Map<String, String>?>> =
        listOf(
            false to null,
            true to null,
            false to emptyMap(),
            false to mapOf(row.relPath to doc),
            false to mapOf(origPath to doc),
            false to mapOf(row.relPath to "another"),
        )

    private val cases =
        rows.flatMap { row ->
            journals.flatMap { journal ->
                folds(row).flatMap { (preserved, contentAt) ->
                    flags.map { originHeld -> Case(row, journal, preserved, contentAt, originHeld) }
                }
            }
        }

    private fun resolve(case: Case) =
        FoldLifecycle.resolve(doc, case.row, case.journal, case.preserved, case.contentAt) {
            it == case.row.relPath || (it == origPath && case.originHeld)
        }

    /** Only a rescan that knows the document's own bytes are at its path, or at its free origin, resurrects. */
    @Test
    fun aDocumentIsResurrectedOnlyByItsOwnBytesFoundByARescanWhileTheJournalHidesIt() =
        propTest {
            checkAll(Exhaustive.collection(cases)) { case ->
                val projected = resolve(case)
                val journal = case.journal
                val hidden = journal.trashed || journal.deleted || journal.superseded
                val contentAt = case.contentAt
                val ownAtRow = contentAt?.get(case.row.relPath) == doc
                val ownAtFreeOrigin =
                    journal.origPath != null && contentAt?.get(origPath) == doc &&
                        !(origPath == case.row.relPath || case.originHeld)
                val expected = hidden && !journal.superseded && (ownAtRow || ownAtFreeOrigin)
                check((projected.resurrected != null) == expected) { "$case: resurrected ${projected.resurrected}" }
                projected.resurrected?.let {
                    it shouldBe Resurrected(doc, projected.relPath, hiddenAtMillis = 500)
                    check(!projected.trashed && !projected.deleted && !projected.missing) { "$case: still hidden" }
                    projected.relPath shouldBe (if (ownAtRow) case.row.relPath else origPath)
                }
            }
        }

    /** Without a resurrection the row stays where it is, its flags the journal's, or the row's when preserved. */
    @Test
    fun otherwiseTheFlagsAreTheJournalsOrThePreservedRowsAndASupersededDocumentIsDeleted() =
        propTest {
            checkAll(Exhaustive.collection(cases)) { case ->
                val projected = resolve(case)
                if (projected.resurrected != null) return@checkAll
                val row = case.row
                val journal = case.journal
                projected.relPath shouldBe row.relPath
                projected.fileName shouldBe row.fileName
                val shown = !projected.trashed && !projected.deleted
                projected.missing shouldBe (row.missing || (shown && row.relPath == missingSentinel(doc)))
                projected.origPath shouldBe journal.origPath
                if (case.preserved) {
                    projected.trashed shouldBe row.trashed
                    projected.deleted shouldBe (row.deleted || journal.superseded)
                } else {
                    projected.trashed shouldBe journal.trashed
                    projected.deleted shouldBe (journal.deleted || journal.superseded)
                }
            }
        }

    /**
     * A trash that recorded its file's stat decides a return by it: the bytes back with that stat
     * (mtimes up to 2 s apart) stay in the trash, reported as back; with another, they are restored at
     * once. A delete for good, and a trash with no stat, wait as before.
     */
    @Test
    fun aRecordedTrashStatDecidesAReturn() {
        val row = DocumentEntity(doc, origPath, "a.pdf", size = 10, mtime = 5_000)
        val recorded = FileStat(10, 5_000)

        fun resolve(
            found: FileStat,
            trashStat: FileStat? = recorded,
            deleted: Boolean = false,
        ) = FoldLifecycle.resolve(
            doc,
            row.copy(trashed = !deleted, deleted = deleted),
            JournalLifecycle(false, !deleted, deleted, origPath, 500, trashStat),
            preserved = false,
            contentAt = mapOf(origPath to doc),
            statAt = { found.takeIf { _ -> it == origPath } },
        ) { it == origPath }

        listOf(FileStat(10, 5_000), FileStat(10, 7_000), FileStat(10, 3_000)).forEach { same ->
            val kept = resolve(same)
            kept.trashed shouldBe true
            kept.resurrected shouldBe null
            kept.fileBack shouldBe origPath
        }
        listOf(FileStat(10, 7_001), FileStat(11, 5_000)).forEach { other ->
            val back = resolve(other)
            back.fileBack shouldBe null
            back.resurrected shouldBe Resurrected(doc, origPath, 500)
        }
        resolve(FileStat(10, 5_000), trashStat = null).resurrected shouldBe Resurrected(doc, origPath, 500)
        resolve(FileStat(10, 5_000), deleted = true).resurrected shouldBe Resurrected(doc, origPath, 500)
    }
}
