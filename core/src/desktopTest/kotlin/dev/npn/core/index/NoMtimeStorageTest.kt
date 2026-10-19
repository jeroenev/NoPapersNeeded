package dev.npn.core.index

import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.sha256Bytes
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Storage that reports no mtimes (a SAF provider giving 0 for every file): an unchanged size proves
 * nothing there, so a file replaced by other bytes of the same size is hashed rather than kept as
 * the document it replaced (`FileMeta.hasStoredStat`).
 */
class NoMtimeStorageTest {
    private val rig =
        IndexRig.create(files = { raw ->
            object : LibraryFileSystem by raw {
                override suspend fun stat(relPath: String): FileMeta? = raw.stat(relPath)?.copy(mtimeMillis = 0)

                override suspend fun listAllIndexable(): List<FileMeta> =
                    raw.listAllIndexable().map { it.copy(mtimeMillis = 0) }

                override suspend fun listAllUnder(relPath: String): List<FileMeta> =
                    raw.listAllUnder(relPath).map { it.copy(mtimeMillis = 0) }
            }
        }).also {
            // A file's age is the clock's distance from its mtime, 0 here: the files have long settled.
            it.clock.now = NOW
        }

    @AfterTest
    fun teardown() = rig.close()

    @Test
    fun aRescanHashesAFileReplacedByBytesOfTheSameSize() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            rig.rescan()
            val alpha = rig.fs.sha256("a.pdf")

            rig.writePdf("a.pdf", "bravo")
            rig.rescan()

            val dao = rig.db.documentDao()
            dao.getByRelPath("a.pdf")?.docId shouldBe sha256Bytes("bravo".encodeToByteArray())
            dao.requireByDocId(alpha).missing shouldBe true
        }

    @Test
    fun anImportIsNoDuplicateOfADocumentWhoseFileWasReplacedByBytesOfTheSameSize() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            rig.rescan()
            val alpha = rig.fs.sha256("a.pdf")
            rig.writePdf("a.pdf", "bravo")

            val imported =
                rig.importService
                    .importBytes("alpha".encodeToByteArray(), "a.pdf")
                    .shouldBeInstanceOf<ImportResult.Imported>()

            imported.docId shouldBe alpha
            rig.fs.sha256(imported.relPath) shouldBe alpha
        }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
