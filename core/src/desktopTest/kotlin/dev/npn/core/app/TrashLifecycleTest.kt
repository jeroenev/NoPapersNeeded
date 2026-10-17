package dev.npn.core.app

import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.trashEntries
import dev.npn.core.index.requireByDocId
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TrashLifecycleTest {
    @Test
    fun trashMovesFileAndSetsFlag() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.fs.sha256("a.pdf")

            rig.session.docLifecycle.trashDoc(id)

            rig.exists("a.pdf").shouldBeFalse()
            val doc = rig.db.documentDao().requireByDocId(id)
            doc.trashed.shouldBeTrue()
            // A file now exists under .npn/trash/.
            val trash =
                rig.fs.listAll().filter {
                    !it.isDirectory && it.relPath.startsWith("${NpnPaths.TRASH_DIR}/")
                }
            trash.isNotEmpty().shouldBeTrue()
        }

    /** Trashing a document already in the trash does nothing, and says so: there is nothing to undo. */
    @Test
    fun trashingADocumentAlreadyInTheTrashJournalsNothing() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            rig.session.docLifecycle
                .trashDoc(id)
                .shouldNotBeNull()
            val objects = rig.journalObjectCount()

            rig.session.docLifecycle
                .trashDoc(id)
                .shouldBeNull()

            rig.journalObjectCount() shouldBe objects
            rig.fs.trashEntries() shouldHaveSize 1
        }

    /**
     * An Undo restores only what its trash did: the document restored and trashed again since (here,
     * or as well on another device) stays in the trash, with nothing written, and the later trash's
     * own Undo restores it.
     */
    @Test
    fun anUndoLeavesADocumentTrashedAgainSinceInTheTrash() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            val lifecycle = rig.session.docLifecycle
            val first = lifecycle.trashDoc(id).shouldNotBeNull()
            lifecycle.restoreDoc(id)
            val second = lifecycle.trashDoc(id).shouldNotBeNull()
            second.trashedAt shouldBe
                rig.db
                    .documentDao()
                    .getByDocId(id)
                    ?.trashedAt
            val objects = rig.journalObjectCount()

            lifecycle.undoTrash(listOf(first)) shouldBe 0

            rig.journalObjectCount() shouldBe objects
            rig.db
                .documentDao()
                .requireByDocId(id)
                .trashed
                .shouldBeTrue()
            rig.exists("a.pdf").shouldBeFalse()

            lifecycle.undoTrash(listOf(second)) shouldBe 0

            rig.db
                .documentDao()
                .requireByDocId(id)
                .trashed
                .shouldBeFalse()
            rig.exists("a.pdf").shouldBeTrue()
        }

    /** The Undo of a trash already restored, here or elsewhere, finds nothing to do. */
    @Test
    fun anUndoOfADocumentRestoredSinceWritesNothing() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            val lifecycle = rig.session.docLifecycle
            val trash = lifecycle.trashDoc(id).shouldNotBeNull()
            lifecycle.restoreDoc(id)
            val objects = rig.journalObjectCount()

            lifecycle.undoTrash(listOf(trash)) shouldBe 0

            rig.journalObjectCount() shouldBe objects
            rig.exists("a.pdf").shouldBeTrue()
        }

    @Test
    fun restoreBringsFileBack() =
        appSessionTest { rig ->
            rig.writePdf("docs/a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.fs.sha256("docs/a.pdf")

            rig.session.docLifecycle.trashDoc(id)
            rig.exists("docs/a.pdf").shouldBeFalse()

            rig.session.docLifecycle.restoreDoc(id)
            rig.exists("docs/a.pdf").shouldBeTrue()
            rig.db
                .documentDao()
                .requireByDocId(id)
                .trashed
                .shouldBeFalse()
        }

    @Test
    fun permanentDeleteRemovesFileAndStaysHiddenAfterRescan() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.fs.sha256("a.pdf")
            rig.session.docLifecycle.trashDoc(id)

            rig.session.docLifecycle.deleteDocPermanently(id) shouldBe PermanentDeleteOutcome.DELETED
            rig.exists("a.pdf").shouldBeFalse()
            rig.fs.trashEntries().shouldBeEmpty()
            rig.db
                .documentDao()
                .requireByDocId(id)
                .deleted
                .shouldBeTrue()

            // After a rescan the tombstone keeps it hidden.
            rig.session.rescan()
            rig.db
                .documentDao()
                .requireByDocId(id)
                .deleted
                .shouldBeTrue()
        }

    @Test
    fun trashingWhileWritesAreBlockedLeavesThePdfWhereItWas() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            rig.blockMetadataWrites()

            assertFailsWith<IllegalStateException> { rig.session.docLifecycle.trashDoc(id) }

            rig.exists("a.pdf").shouldBeTrue()
            rig.db
                .documentDao()
                .getByDocId(id)
                ?.trashed shouldBe false
        }

    @Test
    fun deletingWhileWritesAreBlockedKeepsTheFile() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            rig.session.docLifecycle.trashDoc(id)
            rig.blockMetadataWrites()

            assertFailsWith<IllegalStateException> { rig.session.docLifecycle.deleteDocPermanently(id) }

            rig.fs.trashEntries() shouldHaveSize 1
            rig.db
                .documentDao()
                .getByDocId(id)
                ?.deleted shouldBe false
        }

    @Test
    fun restoringWhileWritesAreBlockedLeavesThePdfInTheTrash() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            rig.session.docLifecycle.trashDoc(id)
            rig.blockMetadataWrites()

            assertFailsWith<IllegalStateException> { rig.session.docLifecycle.restoreDoc(id) }

            rig.exists("a.pdf").shouldBeFalse()
            rig.fs.trashEntries() shouldHaveSize 1
            rig.db
                .documentDao()
                .getByDocId(id)
                ?.trashed shouldBe true
        }
}
