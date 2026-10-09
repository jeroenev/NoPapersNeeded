package dev.npn.core.index

import dev.npn.core.fs.sha256Bytes
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * What an import takes as proof that a live document's file still holds the bytes it brings: the
 * size and mtime the row stores, the evidence a rescan takes to skip hashing, and the file's hash
 * only once that stat has drifted. The check runs under the writer lock, so it must stay cheap.
 */
class ImportDuplicateTest {
    private val rig = IndexRig.create()
    private val watched = WatchedFileSystem(rig.fs).also { it.db = rig.db }
    private val imports =
        ImportService(watched, rig.db, rig.deviceDb.ocrQueueDao(), rig.clock, fieldTypes = { emptyMap() })

    @AfterTest
    fun teardown() = rig.close()

    @Test
    fun theBytesOfAnUnchangedDocumentAreADuplicateWithoutHashingItsFile() =
        runTest {
            val id = indexed("a.pdf", "alpha")

            imports.importBytes("alpha".encodeToByteArray(), "copy.pdf") shouldBe ImportResult.Duplicate(id, "a.pdf")

            watched.calls("sha256") shouldNotContain "sha256 a.pdf"
        }

    /** Other bytes of the same size written over the file: its mtime moved, so it is hashed, and holds no copy. */
    @Test
    fun aFileOverwrittenWithBytesOfTheSameSizeIsHashedAndIsNoDuplicate() =
        runTest {
            val id = indexed("a.pdf", "alpha")
            rig.writePdf("a.pdf", "bravo")
            Files.setLastModifiedTime(rig.libRoot.resolve("a.pdf"), FileTime.fromMillis(LATER))

            val imported =
                imports.importBytes("alpha".encodeToByteArray(), "a.pdf").shouldBeInstanceOf<ImportResult.Imported>()

            watched.calls("sha256") shouldContain "sha256 a.pdf"
            imported.docId shouldBe id
            rig.fs.sha256(imported.relPath) shouldBe id
            rig.fs.sha256("a.pdf") shouldBe sha256Bytes("bravo".encodeToByteArray())
        }

    /** A file of another size cannot hold the bytes, which takes no hash to know. */
    @Test
    fun aFileOfAnotherSizeIsNoDuplicateAndIsNotHashed() =
        runTest {
            val id = indexed("a.pdf", "alpha")
            rig.writePdf("a.pdf", "a longer document")

            val imported =
                imports.importBytes("alpha".encodeToByteArray(), "a.pdf").shouldBeInstanceOf<ImportResult.Imported>()

            watched.calls("sha256") shouldNotContain "sha256 a.pdf"
            imported.docId shouldBe id
            rig.fs.sha256(imported.relPath) shouldBe id
        }

    /** Index a PDF of [content] at [relPath] and forget the calls that took; returns its document id. */
    private suspend fun indexed(
        relPath: String,
        content: String,
    ): String {
        rig.writePdf(relPath, content)
        rig.rescan()
        watched.clear()
        return rig.fs.sha256(relPath)
    }

    private companion object {
        const val LATER = 4_000_000_000_000L
    }
}
