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
import dev.npn.core.index.WatchedFileSystem
import dev.npn.core.journal.ContentAddressedJournalWriter
import dev.npn.core.journal.HlcWatermarkAllocator
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths
import dev.npn.core.journal.PendingOp
import dev.npn.core.model.SystemProperty
import dev.npn.core.pathrules.StoragePathRuleApplicator
import dev.npn.core.pdf.PdfFixtures
import dev.npn.core.util.NpnLog
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
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

/** Trashing a document moves its own file into the trash, and no other file. */
class TrashOwnFileTest {
    private val rig = IndexRig.create()
    private val files = WatchedFileSystem(rig.fs).also { it.db = rig.db }
    private val coordinator = rig.coordinator()
    private val writer =
        MetadataWriterImpl(
            coordinator,
            rig.db,
            files,
            TrashOps(files),
            StoragePathRuleApplicator(files, rig.db, { null }, { _, _ -> }),
            NpnLog.None,
        )

    @AfterTest
    fun teardown() = rig.close()

    private suspend fun trashEntries() = rig.fs.listAllUnder(NpnPaths.TRASH_DIR).filter { !it.isDirectory }

    /**
     * Another app wrote over a document's file since the last rescan (SAF has no watcher), so the file
     * at its path is not the document's. Trashing the document records the trash and leaves that file
     * where it is, for the next rescan to index as what it is.
     */
    @Test
    fun trashingADocumentWhoseFileWasReplacedLeavesThatFileWhereItIs() =
        runTest {
            rig.writePdf("inbox/invoice.pdf", "the document")
            coordinator.rescan()
            val trashed = rig.fs.sha256("inbox/invoice.pdf")
            val indexed = checkNotNull(rig.fs.stat("inbox/invoice.pdf"))
            rig.writePdf("inbox/invoice.pdf", "another file, written over it")
            Files.setLastModifiedTime(
                rig.libRoot.resolve("inbox/invoice.pdf"),
                FileTime.fromMillis(indexed.mtimeMillis + 60_000L),
            )

            writer.trashDoc(trashed)

            rig.fs.readBytes("inbox/invoice.pdf").decodeToString() shouldBe "another file, written over it"
            trashEntries().shouldBeEmpty()
            rig.db
                .documentDao()
                .getByDocId(trashed)
                .shouldNotBeNull()
                .trashed
                .shouldBeTrue()
            // The journal holds the trash: a rescan, which folds it, keeps the document in the trash
            // and indexes the other file.
            coordinator.rescan()
            val row =
                rig.db
                    .documentDao()
                    .getByDocId(trashed)
                    .shouldNotBeNull()
            row.trashed.shouldBeTrue()
            row.missing.shouldBeFalse()
            val other = rig.fs.sha256("inbox/invoice.pdf")
            rig.db
                .documentDao()
                .getByRelPath("inbox/invoice.pdf")
                ?.docId shouldBe other

            // With no copy in the trash, a restore leaves that file alone and puts the document beside it.
            writer.restoreDoc(trashed)

            rig.fs.sha256("inbox/invoice.pdf") shouldBe other
            rig.db
                .documentDao()
                .getByDocId(trashed)
                .shouldNotBeNull()
                .relPath shouldBe "inbox/invoice (restored).pdf"
        }

    /** A file with the size and mtime its row stored is the document's: trashing many hashes none. */
    @Test
    fun trashingUnchangedFilesHashesNothing() =
        runTest {
            val names = listOf("a", "b", "c")
            names.forEach { rig.writePdf("$it.pdf", "content of $it") }
            coordinator.rescan()
            val ids = names.map { rig.fs.sha256("$it.pdf") }
            files.clear()

            ids.forEach { writer.trashDoc(it) }

            files.calls("sha256").shouldBeEmpty()
            names.forEach { rig.exists("$it.pdf").shouldBeFalse() }
            trashEntries().map { it.name }.sorted() shouldBe
                names.zip(ids) { name, id -> "${name}__${id.take(NpnPaths.TRASH_HASH_PREFIX_LENGTH)}.pdf" }
            ids.forEach {
                rig.db
                    .documentDao()
                    .getByDocId(it)
                    .shouldNotBeNull()
                    .trashed
                    .shouldBeTrue()
            }
        }
}

/**
 * Trash and restore read the document's row and check its file under the writer lock, in the same
 * step as the file move and the journal write: a rescan holding the lock while they wait for it
 * cannot change the document under them.
 */
class LifecycleBehindARescanTest {
    private val rig = IndexRig.create()
    private val journal = PausingJournal(rig.fs)
    private val waiting = ParkedSignal()
    private val coordinator =
        JournalCoordinator(
            rig.writer(),
            rig.indexer,
            MetadataAccumulator(journal, rig.db, installationId = "test-installation"),
            dispatcher = waiting,
        )
    private val files = WatchedFileSystem(rig.fs).also { it.db = rig.db }
    private val writer =
        MetadataWriterImpl(
            coordinator,
            rig.db,
            files,
            TrashOps(files),
            StoragePathRuleApplicator(files, rig.db, { null }, { _, _ -> }),
            NpnLog.None,
        )

    @AfterTest
    fun teardown() = rig.close()

    private suspend fun trashEntries() = rig.fs.listAllUnder(NpnPaths.TRASH_DIR).filter { !it.isDirectory }

    /**
     * The file was renamed outside the app, and the rescan that finds it holds the lock while the
     * trash waits: the trash takes the file from where the rescan found it, and records that path.
     */
    @Test
    fun aTrashWaitingBehindARescanThatFindsTheFileRenamedTrashesItWhereItIsNow() =
        runTest {
            rig.writePdf("inbox/a.pdf", "alpha")
            coordinator.rescan()
            val id = rig.fs.sha256("inbox/a.pdf")

            behindARescan({ writer.trashDoc(id) }) { rig.moveFile("inbox/a.pdf", "archive/a.pdf") }

            rig.exists("archive/a.pdf").shouldBeFalse()
            trashEntries().map { rig.fs.sha256(it.relPath) } shouldBe listOf(id)
            val row =
                rig.db
                    .documentDao()
                    .getByDocId(id)
                    .shouldNotBeNull()
            row.trashed.shouldBeTrue()
            row.trashedOrigPath shouldBe "archive/a.pdf"
            coordinator.rescan()
            rig.db
                .documentDao()
                .getByDocId(id)
                .shouldNotBeNull()
                .trashed
                .shouldBeTrue()
        }

    /**
     * Another device restored the document and renamed it; the rescan that brings both here holds
     * the lock while a restore waits. The document is no longer in the trash, so the restore does
     * nothing: it neither points the row at the old path nor journals the file back there.
     */
    @Test
    fun aRestoreWaitingBehindARescanThatFindsTheDocumentRestoredElsewhereDoesNothing() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            coordinator.rescan()
            val id = rig.fs.sha256("a.pdf")
            writer.trashDoc(id)
            val entry = trashEntries().single().relPath

            behindARescan({ writer.restoreDoc(id) }) {
                rig
                    .writer("other-installation")
                    .commit(
                        listOf(PendingOp.docRestore(id), PendingOp.propSet(id, SystemProperty.STORAGE_PATH, "c.pdf")),
                    )
                rig.moveFile(entry, "c.pdf")
            }

            repeat(2) { pass ->
                if (pass > 0) coordinator.rescan()
                rig.accumulator.loadState().effectiveProp(id, SystemProperty.STORAGE_PATH) shouldBe "c.pdf"
                rig.exists("a.pdf").shouldBeFalse()
                rig.fs.sha256("c.pdf") shouldBe id
                val row =
                    rig.db
                        .documentDao()
                        .getByDocId(id)
                        .shouldNotBeNull()
                row.isLive.shouldBeTrue()
                row.relPath shouldBe "c.pdf"
            }
        }

    /** A restore hashes the trash entries before it waits for the lock, and an unchanged one not again under it. */
    @Test
    fun aRestoreHashesItsTrashCopyBeforeWaitingForTheLock() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            coordinator.rescan()
            val id = rig.fs.sha256("a.pdf")
            writer.trashDoc(id)
            files.clear()
            var hashedBeforeTheLock = emptyList<String>()

            behindARescan({ writer.restoreDoc(id) }) { hashedBeforeTheLock = files.calls("sha256") }

            hashedBeforeTheLock shouldHaveSize 1
            files.calls("sha256") shouldBe hashedBeforeTheLock
            rig.fs.sha256("a.pdf") shouldBe id
        }

    /**
     * Start a rescan and stop it once it holds the writer lock; run [action] in a coroutine of its
     * own until it waits for that lock, then [meanwhile], which changes the library before the rescan
     * reads it; then let the rescan go on, and await both.
     */
    private suspend fun TestScope.behindARescan(
        action: suspend () -> Unit,
        meanwhile: suspend () -> Unit,
    ) {
        val reached = journal.pauseNextListing()
        val rescan = async { coordinator.rescan() }
        reached.await()
        val waiter = async(CoroutineName(ParkedSignal.WAITER)) { action() }
        waiting.parked.receive()
        meanwhile()
        journal.resume()
        rescan.await()
        waiter.await()
    }

    /** [fs], whose next listing of the journal waits, once [pauseNextListing] asks it to, until [resume]. */
    private class PausingJournal(
        private val fs: LibraryFileSystem,
    ) : LibraryFileSystem by fs {
        @Volatile private var pause: CompletableDeferred<Unit>? = null
        private val resumed = CompletableDeferred<Unit>()

        /** Pause the next journal listing; the result completes once it has started. */
        fun pauseNextListing(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { pause = it }

        fun resume() = resumed.complete(Unit)

        override suspend fun listAllUnder(relPath: String): List<FileMeta> {
            if (relPath == NpnPaths.JOURNAL_DIR) {
                pause?.let { reached ->
                    pause = null
                    reached.complete(Unit)
                    resumed.await()
                }
            }
            return fs.listAllUnder(relPath)
        }
    }

    /**
     * The coordinator's dispatcher, over [threads], sending to [parked] each time a coroutine named
     * [WAITER] has run there as far as it can. Dispatched there, a caller goes straight for the writer
     * lock, so the first such signal while another holds the lock says it waits for it.
     */
    private class ParkedSignal(
        private val threads: CoroutineDispatcher = Dispatchers.Default,
    ) : CoroutineDispatcher() {
        val parked = Channel<Unit>(Channel.UNLIMITED)

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) = threads.dispatch(context) {
            block.run()
            if (context[CoroutineName]?.name == WAITER) parked.trySend(Unit)
        }

        companion object {
            const val WAITER = "waiter"
        }
    }
}

/**
 * A trash entry is a document's copy only while it holds the document's bytes. The hash prefix in its
 * name narrows the search, but another document's hash can share it, and an older build could file
 * another file under a document's name.
 */
class TrashEntryTest {
    /** A trashed document: its id, and the path it was trashed from. */
    private data class Trashed(
        val id: String,
        val path: String,
    )

    private class Library(
        val rig: IndexRig,
    ) {
        val coordinator = rig.coordinator()
        val writer =
            MetadataWriterImpl(
                coordinator,
                rig.db,
                rig.fs,
                TrashOps(rig.fs),
                StoragePathRuleApplicator(rig.fs, rig.db, { null }, { _, _ -> }),
                NpnLog.None,
            )

        suspend fun trashEntries() = rig.fs.listAllUnder(NpnPaths.TRASH_DIR).filter { !it.isDirectory }

        /** Write [content] at [path], index it and trash it. */
        suspend fun trash(
            path: String,
            content: String,
        ): Trashed {
            rig.writePdf(path, content)
            coordinator.rescan()
            val id = rig.fs.sha256(path)
            writer.trashDoc(id)
            return Trashed(id, path)
        }

        /** Whether an entry in the trash holds [doc]'s bytes. */
        suspend fun inTrash(doc: Trashed) = trashEntries().any { rig.fs.sha256(it.relPath) == doc.id }

        /** Put another file's bytes in [doc]'s only trash entry, as an older build's trash could. */
        suspend fun misfile(doc: Trashed): String {
            val entry = trashEntries().single().relPath
            rig.deleteFile(entry)
            rig.writePdf(entry, "another file")
            inTrash(doc).shouldBeFalse()
            return entry
        }
    }

    private suspend fun withLibrary(block: suspend Library.() -> Unit) {
        val rig = IndexRig.create()
        try {
            Library(rig).block()
        } finally {
            rig.close()
        }
    }

    /**
     * Run [block] over two trashed documents whose trash entries carry the same hash prefix, once in
     * each order on a fresh library: whichever entry the trash lists first, one run acts first on the
     * other document.
     */
    private fun withClashingEntries(block: suspend Library.(first: Trashed, second: Trashed) -> Unit) =
        runTest {
            for (reversed in listOf(false, true)) {
                withLibrary {
                    val docs = CLASHING.mapIndexed { i, content -> trash("doc $i.pdf", content) }
                    docs.map { it.id.take(NpnPaths.TRASH_HASH_PREFIX_LENGTH) }.distinct() shouldHaveSize 1
                    val (first, second) = if (reversed) docs.reversed() else docs
                    block(first, second)
                }
            }
        }

    @Test
    fun eachOfTwoDocumentsWhoseEntriesShareAPrefixIsRestoredFromItsOwn() =
        withClashingEntries { first, second ->
            writer.restoreDoc(first.id)

            rig.fs.sha256(first.path) shouldBe first.id
            inTrash(second).shouldBeTrue()

            writer.restoreDoc(second.id)

            rig.fs.sha256(second.path) shouldBe second.id
            trashEntries().shouldBeEmpty()
        }

    @Test
    fun deletingForGoodOneOfTwoDocumentsWhoseEntriesShareAPrefixLeavesTheOthers() =
        withClashingEntries { deleted, kept ->
            writer.deleteDocPermanently(deleted.id)

            inTrash(deleted).shouldBeFalse()
            inTrash(kept).shouldBeTrue()
            writer.restoreDoc(kept.id)
            rig.fs.sha256(kept.path) shouldBe kept.id
        }

    /**
     * The document has no copy in the trash, only an entry under its name holding another file. A
     * restore brings nothing back, as for a document whose trash entry is gone.
     */
    @Test
    fun anEntryHoldingAnotherFileIsNotRestoredAsTheDocument() =
        runTest {
            withLibrary {
                val doc = trash("inbox/invoice.pdf", "the document")
                val entry = misfile(doc)

                writer.restoreDoc(doc.id)

                rig.fs.readBytes(entry).decodeToString() shouldBe "another file"
                rig.exists(doc.path).shouldBeFalse()
                rig.db
                    .documentDao()
                    .getByDocId(doc.id)
                    .shouldNotBeNull()
                    .trashed
                    .shouldBeFalse()
            }
        }

    /** Nor is that entry deleted with the document. */
    @Test
    fun anEntryHoldingAnotherFileIsNotDeletedWithTheDocument() =
        runTest {
            withLibrary {
                val doc = trash("inbox/invoice.pdf", "the document")
                val entry = misfile(doc)

                writer.deleteDocPermanently(doc.id)

                rig.fs.readBytes(entry).decodeToString() shouldBe "another file"
                rig.db
                    .documentDao()
                    .getByDocId(doc.id)
                    .shouldNotBeNull()
                    .deleted
                    .shouldBeTrue()
            }
        }

    private companion object {
        /** Two contents whose SHA-256 hashes share their first eight hex characters (`d77f15bd`). */
        val CLASHING = listOf("trashed document 35434", "trashed document 102888")
    }
}
