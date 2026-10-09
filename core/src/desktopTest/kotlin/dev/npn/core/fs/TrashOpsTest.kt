package dev.npn.core.fs

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class TrashOpsTest {
    private lateinit var root: Path
    private lateinit var fs: JvmLibraryFileSystem
    private lateinit var trash: TrashOps

    @BeforeTest
    fun setup() {
        root = Files.createTempDirectory("npn-trash-test")
        fs = JvmLibraryFileSystem(root, PathExternalIo)
        trash = TrashOps(fs)
    }

    @AfterTest
    fun teardown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun moveToTrashAndRestoreRoundtrip() =
        runTest {
            fs.writeBytesOnce("inbox/doc.pdf", "content".encodeToByteArray())
            val trashRel = trash.moveToTrash("inbox/doc.pdf", "deadbeefcafebabe")
            trashRel shouldBe ".npn/trash/doc__deadbeef.pdf"
            fs.exists("inbox/doc.pdf").shouldBeFalse()
            fs.exists(trashRel).shouldBeTrue()

            val restored = trash.restore(trashRel, "inbox/doc.pdf")
            restored shouldBe "inbox/doc.pdf"
            fs.readBytes("inbox/doc.pdf").decodeToString() shouldBe "content"
        }

    /** An entry hashed before is not hashed again while its stat is unchanged; one written to since is. */
    @Test
    fun copiesOfHashesOnlyTheEntriesThatChangedSinceTheyWereHashed() =
        runTest {
            val content = "content".encodeToByteArray()
            val id = sha256Bytes(content)
            fs.writeBytesOnce("a/doc.pdf", content)
            fs.writeBytesOnce("b/doc.pdf", content)
            val kept = trash.moveToTrash("a/doc.pdf", id)
            val changed = trash.moveToTrash("b/doc.pdf", id)
            val hashed = trash.hashCandidates(id)
            fs.delete(changed)
            fs.writeBytesOnce(changed, "other content".encodeToByteArray())
            val hashing = ArrayList<String>()
            val counting =
                object : LibraryFileSystem by fs {
                    override suspend fun sha256(relPath: String): String {
                        hashing += relPath
                        return fs.sha256(relPath)
                    }
                }

            TrashOps(counting).copiesOf(id, hashed) shouldBe listOf(kept)
            hashing shouldBe listOf(changed)
        }

    @Test
    fun moveToTrashAvoidsCollisionWithNumericSuffix() =
        runTest {
            fs.writeBytesOnce("a/doc.pdf", "1".encodeToByteArray())
            fs.writeBytesOnce("b/doc.pdf", "2".encodeToByteArray())
            // Same name + same hash prefix -> second must get -1.
            val first = trash.moveToTrash("a/doc.pdf", "deadbeefcafebabe")
            val second = trash.moveToTrash("b/doc.pdf", "deadbeefcafebabe")
            first shouldBe ".npn/trash/doc__deadbeef.pdf"
            second shouldBe ".npn/trash/doc__deadbeef-1.pdf"
            trash.listTrash() shouldHaveSize 2
        }

    @Test
    fun aMoveIntoTheTrashThatALeftTemporaryFileIsNotATrashEntry() =
        runTest {
            fs.writeBytesOnce("inbox/doc.pdf", "content".encodeToByteArray())
            val trashed = trash.moveToTrash("inbox/doc.pdf", "deadbeefcafebabe")
            fs.writeBytesOnce(
                "${NpnPaths.TRASH_DIR}/${NpnPaths.Temp.moveName("interrupted")}",
                "partial".encodeToByteArray(),
            )

            trash.listTrash().map { it.relPath } shouldBe listOf(trashed)
        }

    /** A name the library cannot address is no trash entry: listing and purging pass over it. */
    @Test
    fun aTrashedNameWithABackslashIsPassedOver() =
        runTest {
            assumeTrue(File.separatorChar == '/')
            fs.writeBytesOnce("x/old.pdf", "old".encodeToByteArray())
            val oldTrash = trash.moveToTrash("x/old.pdf", "0000000000000000")
            Files.setLastModifiedTime(root.resolve(oldTrash), FileTime.fromMillis(1000))
            val odd = root.resolve(NpnPaths.TRASH_DIR).resolve("a\\b__00000000.pdf")
            Files.write(odd, "odd".encodeToByteArray())
            Files.setLastModifiedTime(odd, FileTime.fromMillis(1000))

            trash.listTrash().map { it.relPath } shouldBe listOf(oldTrash)
            trash.purgeOlderThan(cutoffMillis = 500_000)
            fs.exists(oldTrash).shouldBeFalse()
            Files.exists(odd).shouldBeTrue()
        }

    @Test
    fun restoreUniquifiesWhenTargetTaken() =
        runTest {
            fs.writeBytesOnce("inbox/doc.pdf", "orig".encodeToByteArray())
            val trashRel = trash.moveToTrash("inbox/doc.pdf", "deadbeefcafebabe")
            // A new file now occupies the original path.
            fs.writeBytesOnce("inbox/doc.pdf", "new".encodeToByteArray())
            val restored = trash.restore(trashRel, "inbox/doc.pdf")
            restored shouldContain "(restored)"
            fs.readBytes(restored).decodeToString() shouldBe "orig"
            fs.readBytes("inbox/doc.pdf").decodeToString() shouldBe "new"
        }

    @Test
    fun purgeOlderThanRemovesOldEntries() =
        runTest {
            fs.writeBytesOnce("x/old.pdf", "old".encodeToByteArray())
            val oldTrash = trash.moveToTrash("x/old.pdf", "0000000000000000")
            // Force an old mtime.
            val oldPath = root.resolve(oldTrash)
            Files.setLastModifiedTime(oldPath, FileTime.fromMillis(1000))

            fs.writeBytesOnce("y/new.pdf", "new".encodeToByteArray())
            trash.moveToTrash("y/new.pdf", "1111111111111111")

            trash.purgeOlderThan(cutoffMillis = 500_000)
            trash.listTrash() shouldHaveSize 1
            fs.exists(oldTrash).shouldBeFalse()
        }

    @Test
    fun purgeMeasuresATrashedDocumentFromWhenItWasTrashed() =
        runTest {
            // Both PDFs are old; moving into trash keeps their mtime.
            val recent = trashWithMtime("x/recent.pdf", "aaaaaaaa11111111", mtime = 1_000)
            val longAgo = trashWithMtime("y/longago.pdf", "bbbbbbbb22222222", mtime = 1_000)

            trash.purgeOlderThan(
                cutoffMillis = 500_000,
                documents = mapOf("aaaaaaaa11111111" to 900_000L, "bbbbbbbb22222222" to 100_000L),
            )

            fs.exists(recent).shouldBeTrue()
            fs.exists(longAgo).shouldBeFalse()
        }

    @Test
    fun purgeKeepsAnEntryWhoseDocumentIsNotTrashedYet() =
        runTest {
            // The move into trash runs before its doc-trash commit: the row is still live.
            val moving = trashWithMtime("x/moving.pdf", "cccccccc33333333", mtime = 1_000)

            trash.purgeOlderThan(cutoffMillis = 500_000, documents = mapOf("cccccccc33333333" to null))

            fs.exists(moving).shouldBeTrue()
        }

    @Test
    fun purgeFallsBackToMtimeForAnEntryWithNoDocument() =
        runTest {
            val orphan = trashWithMtime("x/orphan.pdf", "dddddddd44444444", mtime = 1_000)
            val unrelated = trashWithMtime("x/unrelated.pdf", "eeeeeeee55555555", mtime = 1_000)

            trash.purgeOlderThan(cutoffMillis = 500_000, documents = mapOf("eeeeeeee55555555" to 900_000L))

            fs.exists(orphan).shouldBeFalse()
            fs.exists(unrelated).shouldBeTrue()
        }

    /**
     * Every document whose hash shares the prefix in an entry's name decides for it: one not in trash
     * keeps it, and otherwise the latest trash time does. A prefix clash only ever keeps an entry
     * longer, so the purge needs no hash to keep a document's copy.
     */
    @Test
    fun purgeKeepsAnEntryWhileAnyDocumentSharingItsPrefixKeepsIt() =
        runTest {
            val entry = trashWithMtime("x/old.pdf", "ffffffff00000001", mtime = 1_000)
            val trashedLongAgo = "ffffffff00000001" to 100_000L

            trash.purgeOlderThan(500_000, mapOf(trashedLongAgo, "ffffffff00000002" to null))
            fs.exists(entry).shouldBeTrue()
            trash.purgeOlderThan(500_000, mapOf(trashedLongAgo, "ffffffff00000002" to 900_000L))
            fs.exists(entry).shouldBeTrue()
            trash.purgeOlderThan(500_000, mapOf(trashedLongAgo, "ffffffff00000002" to 200_000L))
            fs.exists(entry).shouldBeFalse()
        }

    @Test
    fun trashedNamesCarryTheirHashPrefix() {
        NpnPaths.trashHashPrefix("doc__deadbeef.pdf") shouldBe "deadbeef"
        NpnPaths.trashHashPrefix("doc__deadbeef-2.pdf") shouldBe "deadbeef"
        NpnPaths.trashHashPrefix("a__b.c__deadbeef.pdf") shouldBe "deadbeef"
        NpnPaths.trashHashPrefix("noext__deadbeef-1") shouldBe "deadbeef"
        NpnPaths.trashHashPrefix("doc.pdf") shouldBe null
    }

    private suspend fun trashWithMtime(
        relPath: String,
        sha256: String,
        mtime: Long,
    ): String {
        fs.writeBytesOnce(relPath, relPath.encodeToByteArray())
        val trashed = trash.moveToTrash(relPath, sha256)
        Files.setLastModifiedTime(root.resolve(trashed), FileTime.fromMillis(mtime))
        return trashed
    }
}
