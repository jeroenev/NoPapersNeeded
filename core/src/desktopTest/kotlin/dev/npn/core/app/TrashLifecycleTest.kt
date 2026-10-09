package dev.npn.core.app

import dev.npn.core.db.isLive
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.TrashOps
import dev.npn.core.fs.VerifiedPublicationFailedException
import dev.npn.core.index.IndexRig
import dev.npn.core.index.MetadataAccumulator
import dev.npn.core.index.MutableClock
import dev.npn.core.journal.ContentAddressedJournalWriter
import dev.npn.core.journal.HlcWatermarkAllocator
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths
import dev.npn.core.pathrules.StoragePathRuleApplicator
import dev.npn.core.pdf.PdfFixtures
import dev.npn.core.util.NpnLog
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TrashLifecycleTest {
    @Test
    fun trashMovesFileAndSetsFlag() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.fs.sha256("a.pdf")

                rig.session.docLifecycle.trashDoc(id)

                rig.exists("a.pdf").shouldBeFalse()
                val doc = rig.db.documentDao().getByDocId(id)!!
                doc.trashed.shouldBeTrue()
                // A file now exists under .npn/trash/.
                val trash =
                    rig.fs.listAll().filter {
                        !it.isDirectory && it.relPath.startsWith("${NpnPaths.TRASH_DIR}/")
                    }
                trash.isNotEmpty().shouldBeTrue()
            } finally {
                rig.close()
            }
        }

    @Test
    fun restoreBringsFileBack() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("docs/a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.fs.sha256("docs/a.pdf")

                rig.session.docLifecycle.trashDoc(id)
                rig.exists("docs/a.pdf").shouldBeFalse()

                rig.session.docLifecycle.restoreDoc(id)
                rig.exists("docs/a.pdf").shouldBeTrue()
                rig.db
                    .documentDao()
                    .getByDocId(id)!!
                    .trashed
                    .shouldBeFalse()
            } finally {
                rig.close()
            }
        }

    @Test
    fun permanentDeleteRemovesFileAndStaysHiddenAfterRescan() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.fs.sha256("a.pdf")

                rig.session.docLifecycle.deleteDocPermanently(id)
                rig.exists("a.pdf").shouldBeFalse()
                rig.db
                    .documentDao()
                    .getByDocId(id)!!
                    .deleted
                    .shouldBeTrue()

                // After a rescan the tombstone keeps it hidden.
                rig.session.rescan()
                rig.db
                    .documentDao()
                    .getByDocId(id)!!
                    .deleted
                    .shouldBeTrue()
            } finally {
                rig.close()
            }
        }

    @Test
    fun renameFileUpdatesPathSameDocId() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.fs.sha256("a.pdf")

                rig.session.docLifecycle.renameFile(id, "renamed.pdf")
                rig.exists("renamed.pdf").shouldBeTrue()
                rig.exists("a.pdf").shouldBeFalse()
                rig.db
                    .documentDao()
                    .getByDocId(id)!!
                    .relPath shouldBe "renamed.pdf"

                // Survives rescan with the same docId.
                rig.session.rescan()
                rig.db
                    .documentDao()
                    .getByDocId(id)!!
                    .relPath shouldBe "renamed.pdf"
            } finally {
                rig.close()
            }
        }

    @Test
    fun moveFileUpdatesPathSameDocId() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.fs.sha256("a.pdf")

                rig.session.docLifecycle.moveFile(id, "archive")
                rig.exists("archive/a.pdf").shouldBeTrue()
                rig.db
                    .documentDao()
                    .getByDocId(id)!!
                    .relPath shouldBe "archive/a.pdf"

                rig.session.rescan()
                rig.db
                    .documentDao()
                    .getByDocId(id)!!
                    .relPath shouldBe "archive/a.pdf"
            } finally {
                rig.close()
            }
        }

    /**
     * A trashed document gives its old path up to a rename: the renamed document takes it, and the
     * trashed one stays in the trash as it was, and comes back beside it rather than over it.
     */
    @Test
    fun aRenameOntoATrashedDocumentsPathTakesItAndTheRestoreGoesBesideIt() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("inbox/invoice.pdf", PdfFixtures.embeddedTextPdf("trashed"))
                rig.writePdf("inbox/q.pdf", PdfFixtures.embeddedTextPdf("renamed"))
                rig.session.rescan()
                val trashed = rig.docId("inbox/invoice.pdf")
                val renamed = rig.docId("inbox/q.pdf")
                rig.session.docLifecycle.trashDoc(trashed)

                rig.session.docLifecycle.renameFile(renamed, "invoice.pdf")

                rig.exists("inbox/q.pdf").shouldBeFalse()
                rig.fs.sha256("inbox/invoice.pdf") shouldBe renamed
                rig.db
                    .documentDao()
                    .getByDocId(renamed)
                    .shouldNotBeNull()
                    .relPath shouldBe "inbox/invoice.pdf"
                val hidden =
                    rig.db
                        .documentDao()
                        .getByDocId(trashed)
                        .shouldNotBeNull()
                hidden.trashed.shouldBeTrue()
                hidden.missing.shouldBeFalse()
                hidden.trashedOrigPath shouldBe "inbox/invoice.pdf"
                rig.session.rescan()
                rig.fs.sha256("inbox/invoice.pdf") shouldBe renamed

                rig.session.docLifecycle.restoreDoc(trashed)

                rig.fs.sha256("inbox/invoice.pdf") shouldBe renamed
                rig.fs.sha256("inbox/invoice (restored).pdf") shouldBe trashed
                rig.session.rescan()
                val restored =
                    rig.db
                        .documentDao()
                        .getByDocId(trashed)
                        .shouldNotBeNull()
                restored.isLive.shouldBeTrue()
                restored.relPath shouldBe "inbox/invoice (restored).pdf"
                rig.db
                    .documentDao()
                    .getByDocId(renamed)
                    .shouldNotBeNull()
                    .relPath shouldBe "inbox/invoice.pdf"
            } finally {
                rig.close()
            }
        }

    /** Nor does a restore with no copy left in the trash take the path another document holds. */
    @Test
    fun aRestoreWithNoTrashCopyLeavesTheDocumentAtItsOldPathAlone() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("inbox/invoice.pdf", PdfFixtures.embeddedTextPdf("trashed"))
                rig.writePdf("inbox/q.pdf", PdfFixtures.embeddedTextPdf("renamed"))
                rig.session.rescan()
                val trashed = rig.docId("inbox/invoice.pdf")
                val renamed = rig.docId("inbox/q.pdf")
                rig.session.docLifecycle.trashDoc(trashed)
                rig.fs
                    .listAllUnder(NpnPaths.TRASH_DIR)
                    .filter { !it.isDirectory }
                    .forEach { rig.fs.delete(it.relPath) }
                rig.session.docLifecycle.renameFile(renamed, "invoice.pdf")

                rig.session.docLifecycle.restoreDoc(trashed)

                rig.fs.sha256("inbox/invoice.pdf") shouldBe renamed
                rig.db
                    .documentDao()
                    .getByDocId(renamed)
                    .shouldNotBeNull()
                    .relPath shouldBe "inbox/invoice.pdf"
                val restored =
                    rig.db
                        .documentDao()
                        .getByDocId(trashed)
                        .shouldNotBeNull()
                restored.trashed.shouldBeFalse()
                restored.relPath shouldBe "inbox/invoice (restored).pdf"
            } finally {
                rig.close()
            }
        }

    @Test
    fun trashingWhileWritesAreBlockedLeavesThePdfWhereItWas() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")
                blockMetadataWrites(rig)

                assertFailsWith<IllegalStateException> { rig.session.docLifecycle.trashDoc(id) }

                rig.exists("a.pdf").shouldBeTrue()
                rig.db
                    .documentDao()
                    .getByDocId(id)
                    ?.trashed shouldBe false
            } finally {
                rig.close()
            }
        }

    @Test
    fun deletingWhileWritesAreBlockedKeepsTheFile() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")
                blockMetadataWrites(rig)

                assertFailsWith<IllegalStateException> { rig.session.docLifecycle.deleteDocPermanently(id) }

                rig.exists("a.pdf").shouldBeTrue()
                rig.db
                    .documentDao()
                    .getByDocId(id)
                    ?.deleted shouldBe false
            } finally {
                rig.close()
            }
        }

    @Test
    fun restoringWhileWritesAreBlockedLeavesThePdfInTheTrash() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")
                rig.session.docLifecycle.trashDoc(id)
                blockMetadataWrites(rig)

                assertFailsWith<IllegalStateException> { rig.session.docLifecycle.restoreDoc(id) }

                rig.exists("a.pdf").shouldBeFalse()
                trashEntries(rig.fs) shouldHaveSize 1
                rig.db
                    .documentDao()
                    .getByDocId(id)
                    ?.trashed shouldBe true
            } finally {
                rig.close()
            }
        }

    /**
     * A trash whose commit fails leaves nothing behind: the PDF is back where it was and so is its
     * row, so the document neither vanishes from the library nor shows in Trash with no file.
     */
    @Test
    fun aTrashWhoseCommitFailsLeavesThePdfAndTheRowAsTheyWere() =
        withLifecycle { lifecycle ->
            val rig = lifecycle.rig
            rig.writePdf("a.pdf", "alpha")
            lifecycle.coordinator.rescan()
            val id = rig.fs.sha256("a.pdf")
            val before = rig.db.documentDao().getByDocId(id)

            lifecycle.failures = 1
            assertFailsWith<VerifiedPublicationFailedException> { lifecycle.writer.trashDoc(id) }

            rig.exists("a.pdf").shouldBeTrue()
            trashEntries(rig.fs).shouldBeEmpty()
            rig.db.documentDao().getByDocId(id) shouldBe before

            lifecycle.writer.trashDoc(id)
            rig.exists("a.pdf").shouldBeFalse()
            rig.db
                .documentDao()
                .getByDocId(id)
                .shouldNotBeNull()
                .trashed
                .shouldBeTrue()
        }

    /** A restore whose commit fails after the move puts the PDF back, as a failed trash does. */
    @Test
    fun aRestoreWhoseCommitFailsPutsThePdfBackInTheTrash() =
        withLifecycle { lifecycle ->
            val rig = lifecycle.rig
            rig.writePdf("a.pdf", "alpha")
            lifecycle.coordinator.rescan()
            val id = rig.fs.sha256("a.pdf")
            lifecycle.writer.trashDoc(id)
            val before = rig.db.documentDao().getByDocId(id)

            lifecycle.failures = 1
            assertFailsWith<VerifiedPublicationFailedException> { lifecycle.writer.restoreDoc(id) }

            rig.exists("a.pdf").shouldBeFalse()
            trashEntries(rig.fs) shouldHaveSize 1
            rig.db.documentDao().getByDocId(id) shouldBe before

            lifecycle.writer.restoreDoc(id)
            rig.exists("a.pdf").shouldBeTrue()
            trashEntries(rig.fs).shouldBeEmpty()
            rig.db
                .documentDao()
                .getByDocId(id)
                .shouldNotBeNull()
                .isLive
                .shouldBeTrue()
        }

    @Test
    fun aDeleteWhoseCommitFailsLeavesTheFileAndTheRowAsTheyWere() =
        withLifecycle { lifecycle ->
            val rig = lifecycle.rig
            rig.writePdf("a.pdf", "alpha")
            lifecycle.coordinator.rescan()
            val id = rig.fs.sha256("a.pdf")
            val before = rig.db.documentDao().getByDocId(id)

            lifecycle.failures = 1
            assertFailsWith<VerifiedPublicationFailedException> { lifecycle.writer.deleteDocPermanently(id) }

            rig.exists("a.pdf").shouldBeTrue()
            rig.db.documentDao().getByDocId(id) shouldBe before

            lifecycle.writer.deleteDocPermanently(id)
            rig.exists("a.pdf").shouldBeFalse()
            rig.db
                .documentDao()
                .getByDocId(id)
                .shouldNotBeNull()
                .deleted
                .shouldBeTrue()
        }

    @Test
    fun aDeleteFromTheTrashWhoseCommitFailsLeavesItInTheTrash() =
        withLifecycle { lifecycle ->
            val rig = lifecycle.rig
            rig.writePdf("a.pdf", "alpha")
            lifecycle.coordinator.rescan()
            val id = rig.fs.sha256("a.pdf")
            lifecycle.writer.trashDoc(id)
            val before = rig.db.documentDao().getByDocId(id)

            lifecycle.failures = 1
            assertFailsWith<VerifiedPublicationFailedException> { lifecycle.writer.deleteDocPermanently(id) }

            trashEntries(rig.fs) shouldHaveSize 1
            rig.db.documentDao().getByDocId(id) shouldBe before
        }

    /**
     * Once the event is published the journal says trashed, so the trash stands even when projecting
     * it fails: moving the PDF back then would leave a trashed document with its file in the library.
     */
    @Test
    fun aTrashWhoseEventIsPublishedStandsWhenProjectingItFails() =
        withLifecycle(statFailuresOfObjects = 1) { lifecycle ->
            val rig = lifecycle.rig
            rig.writePdf("a.pdf", "alpha")
            lifecycle.coordinator.rescan()
            val id = rig.fs.sha256("a.pdf")

            lifecycle.writer.trashDoc(id)

            rig.exists("a.pdf").shouldBeFalse()
            trashEntries(rig.fs) shouldHaveSize 1
            lifecycle.coordinator.rescan()
            rig.db
                .documentDao()
                .getByDocId(id)
                .shouldNotBeNull()
                .trashed
                .shouldBeTrue()
        }

    /**
     * A [MetadataWriterImpl] and its coordinator over an [IndexRig] whose next [failures] journal
     * publishes fail; with [statFailuresOfObjects], that many reads of a published object's metadata
     * fail too, which fails projecting a commit after its publish.
     */
    private class Lifecycle(
        val rig: IndexRig,
        statFailuresOfObjects: Int,
    ) {
        var failures = 0
        private var statFailures = statFailuresOfObjects

        private val journalFs =
            object : LibraryFileSystem by rig.fs {
                override suspend fun publishVerifiedObjectOnce(
                    stagingPath: String,
                    canonicalPath: String,
                    expectedHash: String,
                ) {
                    if (failures-- > 0) throw VerifiedPublicationFailedException("rename failed")
                    rig.fs.publishVerifiedObjectOnce(stagingPath, canonicalPath, expectedHash)
                }

                override suspend fun stat(relPath: String): FileMeta? {
                    if (JournalPaths.classify(relPath) is JournalPath.ObjectV2 && statFailures-- > 0) {
                        throw IOException("stat failed")
                    }
                    return rig.fs.stat(relPath)
                }
            }

        val coordinator =
            JournalCoordinator(
                ContentAddressedJournalWriter(journalFs, "test-installation", HlcWatermarkAllocator(rig.clock)),
                rig.indexer,
                MetadataAccumulator(journalFs, rig.db, installationId = "test-installation"),
            )

        val writer =
            MetadataWriterImpl(
                coordinator,
                rig.db,
                rig.fs,
                TrashOps(rig.fs),
                StoragePathRuleApplicator(rig.fs, rig.db, { null }, { _, _ -> }),
                NpnLog.None,
            )
    }

    private fun withLifecycle(
        statFailuresOfObjects: Int = 0,
        block: suspend (Lifecycle) -> Unit,
    ) = runTest {
        val rig = IndexRig.create()
        try {
            block(Lifecycle(rig, statFailuresOfObjects))
        } finally {
            rig.close()
        }
    }

    private suspend fun trashEntries(fs: LibraryFileSystem) =
        fs.listAllUnder(NpnPaths.TRASH_DIR).filter { !it.isDirectory }

    /** A journal store from a newer build makes the rescan refuse, which blocks metadata writes. */
    private suspend fun blockMetadataWrites(rig: AppSessionRig) {
        Files.createDirectories(rig.libRoot.resolve("${NpnPaths.JOURNAL_DIR}/objects-v9-00"))
        assertFailsWith<IllegalStateException> { rig.session.rescan() }
    }

    @Test
    fun autoPurgeCountsRetentionFromTheTrashNotFromTheFile() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                val now = 400 * DAY
                rig.clock.now = now - 60 * DAY
                rig.writePdf("today.pdf", PdfFixtures.embeddedTextPdf("today"))
                rig.writePdf("long-ago.pdf", PdfFixtures.embeddedTextPdf("long ago"))
                // Both PDFs were imported long ago; a move into trash keeps that mtime.
                for (name in listOf("today.pdf", "long-ago.pdf")) {
                    Files.setLastModifiedTime(rig.libRoot.resolve(name), FileTime.fromMillis(now - 90 * DAY))
                }
                rig.session.rescan()
                val today = rig.docId("today.pdf")
                val longAgo = rig.docId("long-ago.pdf")

                rig.session.docLifecycle.trashDoc(longAgo)
                rig.clock.now = now
                rig.session.docLifecycle.trashDoc(today)

                rig.session.purgeTrash(olderThanDays = 30)

                val left =
                    rig.fs
                        .listAllUnder(NpnPaths.TRASH_DIR)
                        .filter { !it.isDirectory }
                        .map { it.name }
                left shouldBe listOf("today__${today.take(NpnPaths.TRASH_HASH_PREFIX_LENGTH)}.pdf")
                rig.session.docLifecycle.restoreDoc(today)
                rig.exists("today.pdf").shouldBeTrue()
            } finally {
                rig.close()
            }
        }

    @Test
    fun aDeviceWithNoRowForATrashedDocumentKeepsItUntilItsRetentionEnds() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                val now = 400 * DAY
                rig.clock.now = now
                rig.writePdf("old.pdf", PdfFixtures.embeddedTextPdf("old"))
                Files.setLastModifiedTime(rig.libRoot.resolve("old.pdf"), FileTime.fromMillis(now - 90 * DAY))
                rig.session.rescan()
                rig.session.docLifecycle.trashDoc(rig.docId("old.pdf"))

                // A device that joins now indexes only the PDFs on disk: the trashed one has no row.
                val (second, secondDb) = rig.secondSession(backgroundScope, clock = MutableClock(now + 1))
                try {
                    second.purgeTrash(olderThanDays = 30)
                } finally {
                    second.close()
                    secondDb.close()
                }

                rig.fs
                    .listAllUnder(NpnPaths.TRASH_DIR)
                    .filter { !it.isDirectory } shouldHaveSize 1
            } finally {
                rig.close()
            }
        }

    private companion object {
        const val DAY = 24L * 60L * 60L * 1000L
    }
}

/** Deleting a document for good removes its own bytes and nothing else. */
class PermanentDeleteTest {
    /**
     * A trashed document whose trash copy is gone gave its old path up: the file there now is another
     * one, not yet indexed. Deleting the document for good deletes the document, not that file.
     */
    @Test
    fun deletingForGoodADocumentWithNoTrashCopyLeavesAnotherFileAtItsOldPath() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("inbox/invoice.pdf", PdfFixtures.embeddedTextPdf("trashed"))
                rig.session.rescan()
                val trashed = rig.docId("inbox/invoice.pdf")
                rig.session.docLifecycle.trashDoc(trashed)
                rig.fs
                    .listAllUnder(NpnPaths.TRASH_DIR)
                    .filter { !it.isDirectory }
                    .forEach { rig.fs.delete(it.relPath) }
                val newcomer = PdfFixtures.embeddedTextPdf("newcomer")
                rig.writePdf("inbox/invoice.pdf", newcomer)

                rig.session.docLifecycle.deleteDocPermanently(trashed)

                rig.fs.readBytes("inbox/invoice.pdf") shouldBe newcomer
                rig.db
                    .documentDao()
                    .getByDocId(trashed)
                    .shouldNotBeNull()
                    .deleted
                    .shouldBeTrue()
                // The journal holds the deletion: a rescan, which folds it, keeps the document gone
                // and indexes the newcomer.
                rig.session.rescan()
                rig.db
                    .documentDao()
                    .getByDocId(trashed)
                    .shouldNotBeNull()
                    .deleted
                    .shouldBeTrue()
                rig.db
                    .documentDao()
                    .getByRelPath("inbox/invoice.pdf")
                    ?.docId shouldBe rig.fs.sha256("inbox/invoice.pdf")
            } finally {
                rig.close()
            }
        }
}
