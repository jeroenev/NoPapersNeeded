package dev.npn.core.app

import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.trashEntries
import dev.npn.core.fs.withNameToken
import dev.npn.core.index.IndexRig
import dev.npn.core.index.JournalCoordinator
import dev.npn.core.index.WatchedFileSystem
import dev.npn.core.journal.EventDecoderRegistry
import dev.npn.core.journal.FileStat
import dev.npn.core.journal.JournalOp
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.Test

/** Trashing a document moves its own file into the trash, and no other file. */
class TrashOwnFileTest {
    private val rig = IndexRig.create()
    private val files = WatchedFileSystem(rig.fs).also { it.db = rig.db }
    private val coordinator = rig.coordinator()
    private val lifecycle =
        rig.docLifecycle(coordinator, files)

    @AfterTest
    fun teardown() = rig.close()

    /**
     * Another app wrote over a document's file since the last rescan (before the watcher reported it),
     * so the file at its path is not the document's. Trashing the document records the trash and leaves that file
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

            lifecycle.trashDoc(trashed)

            rig.fs.readBytes("inbox/invoice.pdf").decodeToString() shouldBe "another file, written over it"
            rig.fs.trashEntries().shouldBeEmpty()
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
            lifecycle.restoreDoc(trashed)

            rig.fs.sha256("inbox/invoice.pdf") shouldBe other
            rig.db
                .documentDao()
                .getByDocId(trashed)
                .shouldNotBeNull()
                .relPath shouldBe withNameToken("inbox/invoice.pdf", trashed, label = "restored")
        }

    /**
     * The trash records the stat of the file it moved, which the move keeps: what tells a sync
     * tool's copy of that file, not yet removed on another device, from a file put back since. A trash
     * that moved none (the file at the path was another's by then) records none.
     */
    @Test
    fun aTrashRecordsTheStatOfTheFileItMovedAndNoneWhenItMovedNone() =
        runTest {
            rig.writePdf("a.pdf", "moved")
            rig.writePdf("b.pdf", "left")
            coordinator.rescan()
            val moved = rig.fs.sha256("a.pdf")
            val left = rig.fs.sha256("b.pdf")
            val stat = checkNotNull(rig.fs.stat("a.pdf"))
            val indexed = checkNotNull(rig.fs.stat("b.pdf"))
            rig.writePdf("b.pdf", "another file, written over it")
            Files.setLastModifiedTime(rig.libRoot.resolve("b.pdf"), FileTime.fromMillis(indexed.mtimeMillis + 60_000L))

            lifecycle.trashDoc(moved)
            lifecycle.trashDoc(left)

            rig.fs
                .trashEntries()
                .single()
                .mtimeMillis shouldBe stat.mtimeMillis
            trashOf(moved) shouldBe JournalOp.DocTrash("a.pdf", FileStat(stat.size, stat.mtimeMillis))
            trashOf(left) shouldBe JournalOp.DocTrash("b.pdf")
        }

    private suspend fun trashOf(docId: String): JournalOp =
        rig.db
            .metadataProjectionDao()
            .eventsForDocument(docId)
            .single { it.operation == JournalOp.DocTrash.WIRE }
            .let { EventDecoderRegistry.decodeStored(it.canonicalJson).op }

    /** A file with the size and mtime its row stored is the document's: trashing many hashes none. */
    @Test
    fun trashingUnchangedFilesHashesNothing() =
        runTest {
            val names = listOf("a", "b", "c")
            names.forEach { rig.writePdf("$it.pdf", "content of $it") }
            coordinator.rescan()
            val ids = names.map { rig.fs.sha256("$it.pdf") }
            files.clear()

            ids.forEach { lifecycle.trashDoc(it) }

            files.calls("sha256").shouldBeEmpty()
            names.forEach { rig.exists("$it.pdf").shouldBeFalse() }
            rig.fs
                .trashEntries()
                .map { it.name }
                .sorted() shouldBe
                names.zip(ids) { name, id ->
                    "${name}__${id.take(NpnPaths.TRASH_HASH_PREFIX_LENGTH)}-${rig.clock.now}.pdf"
                }
            ids.forEach {
                rig.db
                    .documentDao()
                    .getByDocId(it)
                    .shouldNotBeNull()
                    .trashed
                    .shouldBeTrue()
            }
        }

    /**
     * A trashed document's file copied back with the mtime its trash took it with (a file manager's
     * copy, a backup restored) is the copy a sync tool has yet to take away as far as any device can
     * tell: it stays in the trash for good, however long rescans see it, flagged for the user, and a
     * restore takes that very file where it is.
     */
    @Test
    fun aFileBackWithItsTrashsStatStaysInTheTrashUntilARestoreTakesItInPlace() =
        runTest {
            rig.writePdf("inbox/a.pdf", "put back with its mtime")
            coordinator.rescan()
            val doc = rig.fs.sha256("inbox/a.pdf")
            lifecycle.trashDoc(doc)
            val entry = rig.fs.trashEntries().single()
            Files.copy(
                rig.libRoot.resolve(entry.relPath),
                rig.libRoot.resolve("inbox/a.pdf"),
                StandardCopyOption.COPY_ATTRIBUTES,
            )

            repeat(3) {
                coordinator.rescan().filesBack shouldBe setOf(doc)
                rig.clock.advance(JournalCoordinator.RESTORE_SETTLE_MS + 1)
            }
            rowOf(doc).trashed.shouldBeTrue()

            lifecycle.restoreDoc(doc)

            rowOf(doc).let {
                it.trashed.shouldBeFalse()
                it.relPath shouldBe "inbox/a.pdf"
            }
            rig.fs.sha256("inbox/a.pdf") shouldBe doc
            coordinator.rescan().filesBack.shouldBeEmpty()
            rig.fs.listAllUnder("inbox").map { it.relPath } shouldBe listOf("inbox/a.pdf")
        }

    /** One copied back with a new mtime cannot be a sync tool's stale copy: the next rescan restores it. */
    @Test
    fun aFileBackWithANewMtimeIsRestoredByTheNextRescan() =
        runTest {
            rig.writePdf("a.pdf", "put back with a new mtime")
            coordinator.rescan()
            val doc = rig.fs.sha256("a.pdf")
            lifecycle.trashDoc(doc)
            val entry = rig.fs.trashEntries().single()
            Files.copy(rig.libRoot.resolve(entry.relPath), rig.libRoot.resolve("a.pdf"))
            Files.setLastModifiedTime(rig.libRoot.resolve("a.pdf"), FileTime.fromMillis(entry.mtimeMillis + 60_000L))

            coordinator.rescan().filesBack.shouldBeEmpty()

            rowOf(doc).trashed.shouldBeFalse()
            coordinator.rescan()
            rowOf(doc).let {
                it.trashed.shouldBeFalse()
                it.relPath shouldBe "a.pdf"
            }
        }

    private suspend fun rowOf(docId: String) =
        rig.db
            .documentDao()
            .getByDocId(docId)
            .shouldNotBeNull()
}
