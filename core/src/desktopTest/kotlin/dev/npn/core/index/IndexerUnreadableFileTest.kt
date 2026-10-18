package dev.npn.core.index

import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.journal.PendingOp
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * A file the rescan listed but cannot read when it hashes it (moved away meanwhile, or unreadable)
 * fails nothing: a failed rescan blocks every write and owes a full refold.
 */
class IndexerUnreadableFileTest {
    /** Runs before each hash of a path; throws to fail that read. */
    @Volatile private var beforeHash: (String) -> Unit = {}

    private val rig =
        IndexRig.create(files = { raw ->
            object : LibraryFileSystem by raw {
                override suspend fun sha256(relPath: String): String {
                    beforeHash(relPath)
                    return raw.sha256(relPath)
                }
            }
        })

    @AfterTest fun teardown() {
        rig.close()
    }

    @Test
    fun aFileThatCannotBeReadIsLeftOutAndWritesGoOn() =
        runTest {
            rig.writePdf("a.pdf", "%PDF a %%EOF")
            rig.writePdf("locked.pdf", "%PDF locked %%EOF")
            beforeHash = { if (it == "locked.pdf") throw AccessDeniedException(it) }
            val coordinator = rig.coordinator()

            coordinator.rescan().added shouldBe 1

            val dao = rig.db.documentDao()
            dao.rowAtPath("locked.pdf").shouldBeNull()
            val id = dao.requireRowAtPath("a.pdf").docId
            coordinator.canWrite().shouldBeTrue()
            coordinator.commitAndApply(listOf(PendingOp.tagAdd(id, "kept"))).shouldBeTrue()
            rig.db.tagDao().tagsForDocument(id) shouldBe listOf("kept")

            // Once it reads, the next rescan indexes it.
            beforeHash = {}
            coordinator.rescan().added shouldBe 1
            dao.requireRowAtPath("locked.pdf")
        }

    @Test
    fun aDocumentWhoseFileCannotBeReadKeepsItsRow() =
        runTest {
            rig.writePdf("a.pdf", "%PDF a %%EOF")
            val coordinator = rig.coordinator()
            coordinator.rescan()
            val row = rig.db.documentDao().requireRowAtPath("a.pdf")
            // Touched, so the next rescan reads it again, and then it cannot.
            touch("a.pdf", row.mtime + 10_000)
            beforeHash = { if (it == "a.pdf") throw AccessDeniedException(it) }

            coordinator.rescan().missingCount shouldBe 0

            val kept = rig.db.documentDao().requireByDocId(row.docId)
            kept.relPath shouldBe "a.pdf"
            kept.mtime shouldBe row.mtime
            kept.missing shouldBe false
            coordinator.canWrite().shouldBeTrue()
        }

    /** Gone between the listing and the read: as a listing a moment later would have found it. */
    @Test
    fun aFileGoneBeforeItIsReadIsMissing() =
        runTest {
            rig.writePdf("a.pdf", "%PDF a %%EOF")
            val coordinator = rig.coordinator()
            coordinator.rescan()
            val id =
                rig.db
                    .documentDao()
                    .requireRowAtPath("a.pdf")
                    .docId
            touch(
                "a.pdf",
                rig.db
                    .documentDao()
                    .requireByDocId(id)
                    .mtime + 10_000,
            )
            beforeHash = { if (it == "a.pdf") rig.deleteFile(it) }

            coordinator.rescan().missingCount shouldBe 1

            rig.db
                .documentDao()
                .requireByDocId(id)
                .missing shouldBe true
            coordinator.canWrite().shouldBeTrue()
        }

    /** The move is checked by reading the file at its new path again, and that read can fail too. */
    @Test
    fun aMovedFileThatCannotBeReadAgainIsStillMoved() =
        runTest {
            rig.writePdf("a.pdf", "%PDF a %%EOF")
            val coordinator = rig.coordinator()
            coordinator.rescan()
            val id =
                rig.db
                    .documentDao()
                    .requireRowAtPath("a.pdf")
                    .docId
            rig.moveFile("a.pdf", "moved/a.pdf")
            var reads = 0
            beforeHash = { if (it == "moved/a.pdf" && ++reads > 1) throw AccessDeniedException(it) }

            coordinator.rescan().moved shouldBe 1

            rig.db
                .documentDao()
                .requireByDocId(id)
                .relPath shouldBe "moved/a.pdf"
            coordinator.canWrite().shouldBeTrue()
        }

    /** A copy that cannot be read is no proven copy: its row goes, the file stays. */
    @Test
    fun aDuplicateThatCannotBeReadIsKept() =
        runTest {
            rig.writePdf("a.pdf", "%PDF a %%EOF")
            val coordinator = rig.coordinator()
            coordinator.rescan()
            rig.copyFile("a.pdf", "inbox/a.pdf")
            coordinator.rescan().duplicates.map { it.extraRelPath } shouldBe listOf("inbox/a.pdf")
            val id =
                rig.db
                    .documentDao()
                    .requireRowAtPath("a.pdf")
                    .docId
            beforeHash = { if (it == "inbox/a.pdf") throw AccessDeniedException(it) }

            rig.duplicates.resolveDuplicate(id, "inbox/a.pdf")

            rig.exists("inbox/a.pdf").shouldBeTrue()
            rig.db
                .duplicateDao()
                .forDoc(id)
                .shouldBeEmpty()
        }

    private fun touch(
        relPath: String,
        mtime: Long,
    ) {
        Files.setLastModifiedTime(rig.libRoot.resolve(relPath), FileTime.fromMillis(mtime))
    }
}
