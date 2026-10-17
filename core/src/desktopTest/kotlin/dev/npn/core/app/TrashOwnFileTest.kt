package dev.npn.core.app

import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.trashEntries
import dev.npn.core.index.IndexRig
import dev.npn.core.index.WatchedFileSystem
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.Test

/** Trashing a document moves its own file into the trash, and no other file. */
class TrashOwnFileTest {
    private val rig = IndexRig.create()
    private val files = WatchedFileSystem(rig.fs).also { it.db = rig.db }
    private val coordinator = rig.coordinator()
    private val writer =
        rig.metadataWriter(coordinator, files)

    @AfterTest
    fun teardown() = rig.close()

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
}
