package dev.npn.core.index

import dev.npn.core.fs.DocumentPaths
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.journal.PendingOp
import dev.npn.core.model.SystemProperty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DocumentPathReplicationTest {
    @Test
    fun atomicClaimRefusesReplacementAndCleanupLeavesRecreatedSourceAlone() =
        indexTest { rig ->
            rig.writePdf("old.pdf", "document")
            rig.writePdf("occupied.pdf", "unrelated")
            assertFailsWith<java.nio.file.FileAlreadyExistsException> {
                rig.fs.claimDocument("old.pdf", "occupied.pdf")
            }
            rig.fs.readBytes("occupied.pdf").decodeToString() shouldBe "unrelated"
            val hash = rig.fs.sha256("old.pdf")
            val racing =
                object : LibraryFileSystem by rig.fs {
                    override suspend fun claimDocument(
                        fromRelPath: String,
                        toRelPath: String,
                    ) {
                        rig.fs.claimDocument(fromRelPath, toRelPath)
                        if (fromRelPath == "old.pdf") rig.writePdf("old.pdf", "replacement")
                    }
                }
            DocumentPaths(racing).reconcile(hash, "old.pdf", "new.pdf") shouldBe "new.pdf"
            rig.fs.sha256("new.pdf") shouldBe hash
            rig.fs.readBytes("old.pdf").decodeToString() shouldBe "replacement"
        }

    @Test
    fun relocationMovesTheFileAfterOneHashWithoutACopy() =
        indexTest { rig ->
            rig.writePdf("old.pdf", "document")
            val hash = rig.fs.sha256("old.pdf")
            val watched = WatchedFileSystem(rig.fs).also { it.db = rig.db }

            DocumentPaths(watched).reconcile(hash, "old.pdf", "archive/new.pdf") shouldBe "archive/new.pdf"

            watched.calls("sha256").size shouldBe 1
            watched.calls("copy") shouldBe emptyList()
            watched.calls("publish") shouldBe emptyList()
            rig.fs.sha256("archive/new.pdf") shouldBe hash
            rig.exists("old.pdf") shouldBe false
            rig.fs.listAllUnder("").filter { it.name.startsWith("npn-recovery-") } shouldBe emptyList()
        }

    @Test
    fun replicasConvergeOnWinningRenameAndFreshIndexReplaysIntent() =
        runTest {
            val a = IndexRig.create()
            val b = IndexRig.create()
            val rebuilt = IndexRig.create()
            try {
                for (rig in listOf(a, b)) {
                    rig.writePdf("old.pdf", "document")
                    rig.rescan()
                }
                val hash = a.fs.sha256("old.pdf")
                a
                    .coordinator(
                        a.writer("a"),
                    ).commitAndApply(listOf(PendingOp.propSet(hash, SystemProperty.STORAGE_PATH, "a.pdf")))
                b.clock.advance(100)
                b
                    .coordinator(
                        b.writer("b"),
                    ).commitAndApply(listOf(PendingOp.propSet(hash, SystemProperty.STORAGE_PATH, "b.pdf")))
                copyJournal(a, b)
                copyJournal(b, a)
                a.rescan()
                b.rescan()
                a.db
                    .documentDao()
                    .requireByDocId(hash)
                    .relPath shouldBe "b.pdf"
                b.db
                    .documentDao()
                    .requireByDocId(hash)
                    .relPath shouldBe "b.pdf"
                a.exists("a.pdf") shouldBe false
                copyJournal(a, rebuilt)
                rebuilt.writePdf("old.pdf", "document")
                rebuilt.writePdf("b.pdf", "document")
                rebuilt.rescan()
                rebuilt.db
                    .documentDao()
                    .requireByDocId(hash)
                    .relPath shouldBe "b.pdf"
                rebuilt.fs.sha256("b.pdf") shouldBe hash
                rebuilt.exists("old.pdf") shouldBe false
            } finally {
                a.close()
                b.close()
                rebuilt.close()
            }
        }

    @Test
    fun deletedCollisionOwnerDoesNotBlockMoveOrCorruptItsMetadata() =
        indexTest { rig ->
            rig.writePdf("old.pdf", "document")
            rig.writePdf("new.pdf", "blocker")
            rig.rescan()
            val hash = rig.fs.sha256("old.pdf")
            val blocker = rig.fs.sha256("new.pdf")
            val coordinator = rig.coordinator(rig.writer("remote"))
            coordinator.commitAndApply(listOf(PendingOp.propSet(hash, SystemProperty.STORAGE_PATH, "new.pdf")))
            rig.deleteFile("new.pdf")
            rig.writer("other").commit(listOf(PendingOp.propSet(blocker, SystemProperty.REVIEWED, "true")))
            rig.rescan().pathConflicts shouldBe emptyList()
            rig.fs.sha256("new.pdf") shouldBe hash
            rig.db
                .documentDao()
                .requireByDocId(blocker)
                .missing shouldBe true
            rig.db
                .documentDao()
                .requireByDocId(blocker)
                .reviewed shouldBe true
            rig.db
                .documentDao()
                .requireByDocId(hash)
                .relPath shouldBe "new.pdf"
        }

    @Test
    fun blockedParentDoesNotPreventMetadataProjection() =
        indexTest { rig ->
            rig.writePdf("old.pdf", "document")
            rig.fs.writeBytesOnce("archive", "not a directory".encodeToByteArray())
            rig.rescan()
            val hash = rig.fs.sha256("old.pdf")
            rig.coordinator(rig.writer("remote")).commitAndApply(
                listOf(
                    PendingOp.propSet(hash, SystemProperty.STORAGE_PATH, "archive/new.pdf"),
                    PendingOp.propSet(hash, SystemProperty.REVIEWED, "true"),
                ),
            )
            rig.db
                .documentDao()
                .requireByDocId(hash)
                .reviewed shouldBe true
            rig.rescan().pathConflicts.size shouldBe 1
            rig.exists("old.pdf") shouldBe true
            rig.deleteFile("archive")
            rig.rescan().pathConflicts shouldBe emptyList()
            rig.fs.sha256("archive/new.pdf") shouldBe hash
        }

    @Test
    fun aSourceReplacedBeforeTheClaimIsPutBackAndNothingMoves() =
        indexTest { rig ->
            rig.writePdf("old.pdf", "document")
            val hash = rig.fs.sha256("old.pdf")
            val racing =
                object : LibraryFileSystem by rig.fs {
                    override suspend fun claimDocument(
                        fromRelPath: String,
                        toRelPath: String,
                    ) {
                        if (fromRelPath == "old.pdf") rig.writePdf("old.pdf", "replacement")
                        rig.fs.claimDocument(fromRelPath, toRelPath)
                    }
                }
            // The claimed bytes are not the document's, so they go back where they were.
            DocumentPaths(racing).reconcile(hash, "old.pdf", "new.pdf") shouldBe "old.pdf"
            rig.exists("new.pdf") shouldBe false
            rig.fs.readBytes("old.pdf").decodeToString() shouldBe "replacement"
            rig.fs.listAllUnder("").filter { it.name.startsWith(NpnPaths.Temp.RECOVERY_PREFIX) } shouldBe emptyList()
        }

    @Test
    fun aMoveThatFailsPutsTheSourceBack() =
        indexTest { rig ->
            rig.writePdf("old.pdf", "document")
            val hash = rig.fs.sha256("old.pdf")
            val failing =
                object : LibraryFileSystem by rig.fs {
                    override suspend fun move(
                        fromRelPath: String,
                        toRelPath: String,
                    ): Unit = error("refused")

                    override suspend fun copyOnce(
                        fromRelPath: String,
                        toRelPath: String,
                    ): Unit = error("refused")
                }
            assertFailsWith<IllegalStateException> { DocumentPaths(failing).reconcile(hash, "old.pdf", "new.pdf") }
            rig.fs.sha256("old.pdf") shouldBe hash
            rig.exists("new.pdf") shouldBe false
            rig.fs.listAllUnder("").filter { it.name.startsWith(NpnPaths.Temp.RECOVERY_PREFIX) } shouldBe emptyList()
        }

    @Test
    fun aTargetTheFileCannotBeMovedToGetsAVerifiedCopy() =
        indexTest { rig ->
            rig.writePdf("old.pdf", "document")
            val hash = rig.fs.sha256("old.pdf")
            // As across volumes: neither a link nor a rename reaches the target.
            val otherVolume =
                object : LibraryFileSystem by rig.fs {
                    override suspend fun move(
                        fromRelPath: String,
                        toRelPath: String,
                    ): Unit = throw java.nio.file.FileSystemException(fromRelPath, toRelPath, "EXDEV")
                }
            DocumentPaths(otherVolume).reconcile(hash, "old.pdf", "archive/new.pdf") shouldBe "archive/new.pdf"
            rig.fs.sha256("archive/new.pdf") shouldBe hash
            rig.exists("old.pdf") shouldBe false
            rig.fs.listAllUnder("").filter { it.name.startsWith(NpnPaths.Temp.RECOVERY_PREFIX) } shouldBe emptyList()
        }

    private suspend fun copyJournal(
        from: IndexRig,
        to: IndexRig,
    ) {
        for (entry in from.fs.listAllUnder("")) {
            if (!entry.isDirectory && entry.relPath.startsWith(".npn/journal/") && !to.exists(entry.relPath)) {
                to.fs.writeBytesOnce(entry.relPath, from.fs.readBytes(entry.relPath))
            }
        }
    }

    @Test
    fun journalArrivingBeforePdfIsAppliedWhenContentArrives() =
        indexTest { rig ->
            rig.writePdf("old.pdf", "document")
            val hash = rig.fs.sha256("old.pdf")
            rig.deleteFile("old.pdf")
            rig.coordinator(rig.writer("remote")).commitAndApply(
                listOf(PendingOp.propSet(hash, SystemProperty.STORAGE_PATH, "archive/new.pdf")),
            )
            rig.rescan()
            rig.writePdf("old.pdf", "document")
            rig.rescan()
            rig.db
                .documentDao()
                .requireByDocId(hash)
                .relPath shouldBe "archive/new.pdf"
            rig.fs.sha256("archive/new.pdf") shouldBe hash
            rig.exists("old.pdf") shouldBe false
            rig.rescan().pathConflicts shouldBe emptyList()
        }

    @Test
    fun conflictingDestinationIsPreservedAndIntentRetriesAfterCollisionClears() =
        indexTest { rig ->
            rig.writePdf("old.pdf", "document")
            rig.writePdf("new.pdf", "unrelated")
            rig.rescan()
            val hash = rig.fs.sha256("old.pdf")
            rig.coordinator(rig.writer("remote")).commitAndApply(
                listOf(PendingOp.propSet(hash, SystemProperty.STORAGE_PATH, "new.pdf")),
            )
            rig.rescan().pathConflicts shouldBe listOf(PathConflict(hash, "old.pdf", "new.pdf"))
            rig.fs.readBytes("new.pdf").decodeToString() shouldBe "unrelated"
            rig.moveFile("new.pdf", "other.pdf")
            rig.rescan()
            rig.fs.sha256("new.pdf") shouldBe hash
            rig.exists("old.pdf") shouldBe false
        }

    @Test
    fun anInterruptedClaimRetriesAndALateOldCopyIsRemoved() =
        indexTest { rig ->
            rig.writePdf("old.pdf", "document")
            rig.rescan()
            val hash = rig.fs.sha256("old.pdf")
            val failing =
                object : LibraryFileSystem by rig.fs {
                    override suspend fun claimDocument(
                        fromRelPath: String,
                        toRelPath: String,
                    ) {
                        if (fromRelPath == "old.pdf") error("interrupted")
                        rig.fs.claimDocument(fromRelPath, toRelPath)
                    }
                }
            // A claim that fails moves nothing: the source stays where it was.
            assertFailsWith<IllegalStateException> { DocumentPaths(failing).reconcile(hash, "old.pdf", "new.pdf") }
            rig.fs.sha256("old.pdf") shouldBe hash
            rig.exists("new.pdf") shouldBe false
            rig.coordinator(rig.writer("remote")).commitAndApply(
                listOf(PendingOp.propSet(hash, SystemProperty.STORAGE_PATH, "new.pdf")),
            )
            rig.exists("old.pdf") shouldBe false
            rig.copyFile("new.pdf", "old.pdf")
            rig.rescan().duplicates shouldBe emptyList()
            rig.exists("old.pdf") shouldBe false
        }

    @Test
    fun unsafeAndInternalJournalPathsNeverMovePdf() =
        indexTest { rig ->
            rig.writePdf("old.pdf", "document")
            rig.rescan()
            val hash = rig.fs.sha256("old.pdf")
            val coordinator = rig.coordinator(rig.writer("remote"))
            for (path in listOf("../escape.pdf", ".npn/journal/overwrite.pdf", "hidden/.file.pdf")) {
                coordinator.commitAndApply(listOf(PendingOp.propSet(hash, SystemProperty.STORAGE_PATH, path)))
                rig.exists("old.pdf") shouldBe true
                rig.db
                    .documentDao()
                    .requireByDocId(hash)
                    .relPath shouldBe "old.pdf"
            }
        }
}
