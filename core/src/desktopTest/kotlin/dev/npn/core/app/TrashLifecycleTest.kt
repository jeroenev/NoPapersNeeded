package dev.npn.core.app

import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.TrashOps
import dev.npn.core.fs.VerifiedPublicationFailedException
import dev.npn.core.index.IndexRig
import dev.npn.core.index.MutableClock
import dev.npn.core.journal.ContentAddressedJournalWriter
import dev.npn.core.journal.HlcWatermarkAllocator
import dev.npn.core.pathrules.StoragePathRuleApplicator
import dev.npn.core.pdf.PdfFixtures
import dev.npn.core.util.NpnLog
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
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

    /** A restore whose commit fails after the move puts the PDF back, as a failed trash does. */
    @Test
    fun aRestoreWhoseCommitFailsPutsThePdfBackInTheTrash() =
        runTest {
            val rig = IndexRig.create()
            try {
                var failures = 0
                val flaky =
                    object : LibraryFileSystem by rig.fs {
                        override suspend fun publishVerifiedObjectOnce(
                            stagingPath: String,
                            canonicalPath: String,
                            expectedHash: String,
                        ) {
                            if (failures-- > 0) throw VerifiedPublicationFailedException("rename failed")
                            rig.fs.publishVerifiedObjectOnce(stagingPath, canonicalPath, expectedHash)
                        }
                    }
                val coordinator =
                    JournalCoordinator(
                        ContentAddressedJournalWriter(flaky, "test-installation", HlcWatermarkAllocator(rig.clock)),
                        rig.indexer,
                        rig.accumulator,
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
                rig.writePdf("a.pdf", "alpha")
                coordinator.rescan()
                val id = rig.fs.sha256("a.pdf")
                writer.trashDoc(id)

                failures = 1
                assertFailsWith<VerifiedPublicationFailedException> { writer.restoreDoc(id) }

                rig.exists("a.pdf").shouldBeFalse()
                trashEntries(rig.fs) shouldHaveSize 1
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
