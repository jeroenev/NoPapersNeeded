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
            val old = trashWithMtime("x/old.pdf", "old", mtime = 1_000)
            val odd = root.resolve(NpnPaths.TRASH_DIR).resolve("a\\b__00000000.pdf")
            Files.write(odd, "odd".encodeToByteArray())
            Files.setLastModifiedTime(odd, FileTime.fromMillis(1000))

            trash.listTrash().map { it.relPath } shouldBe listOf(old.entry)
            trash.purgeOlderThan(cutoffMillis = 500_000, documents = mapOf(old.id to 1_000L))
            fs.exists(old.entry).shouldBeFalse()
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

    /** An entry goes once its document was trashed (or deleted for good) before the cutoff, and not before. */
    @Test
    fun purgeOlderThanRemovesOldEntries() =
        runTest {
            val old = trashWithMtime("x/old.pdf", "old bytes", mtime = 1_000)
            val recent = trashWithMtime("y/recent.pdf", "recent bytes", mtime = 1_000)

            trash.purgeOlderThan(
                cutoffMillis = 500_000,
                documents = mapOf(old.id to 1_000L, recent.id to 900_000L),
            )

            trash.listTrash().map { it.relPath } shouldBe listOf(recent.entry)
            fs.exists(old.entry).shouldBeFalse()
        }

    @Test
    fun purgeMeasuresATrashedDocumentFromWhenItWasTrashed() =
        runTest {
            // Both PDFs are old; moving into trash keeps their mtime.
            val recent = trashWithMtime("x/recent.pdf", "recent bytes", mtime = 1_000)
            val longAgo = trashWithMtime("y/longago.pdf", "long ago bytes", mtime = 1_000)

            trash.purgeOlderThan(
                cutoffMillis = 500_000,
                documents = mapOf(recent.id to 900_000L, longAgo.id to 100_000L),
            )

            fs.exists(recent.entry).shouldBeTrue()
            fs.exists(longAgo.entry).shouldBeFalse()
        }

    @Test
    fun purgeKeepsAnEntryWhoseDocumentIsNotTrashedYet() =
        runTest {
            // The move into trash runs before its doc-trash commit: the row is still live.
            val moving = trashWithMtime("x/moving.pdf", "moving bytes", mtime = 1_000)

            trash.purgeOlderThan(cutoffMillis = 500_000, documents = mapOf(moving.id to null))

            fs.exists(moving.entry).shouldBeTrue()
        }

    /**
     * An entry no document owns, however old its mtime, is kept: nothing says when it was trashed. It
     * may be another device's trash whose event has not arrived, or a document a crash stranded here.
     */
    @Test
    fun purgeKeepsAnEntryWithNoDocument() =
        runTest {
            val orphan = trashWithMtime("x/orphan.pdf", "orphan bytes", mtime = 1_000)
            val stray = root.resolve(NpnPaths.TRASH_DIR).resolve("stray.pdf")
            Files.write(stray, "stray".encodeToByteArray())
            Files.setLastModifiedTime(stray, FileTime.fromMillis(1_000))
            val owned = trashWithMtime("x/owned.pdf", "owned bytes", mtime = 1_000)

            trash.purgeOlderThan(cutoffMillis = 500_000, documents = mapOf(owned.id to 100_000L))

            fs.exists(orphan.entry).shouldBeTrue()
            Files.exists(stray).shouldBeTrue()
            fs.exists(owned.entry).shouldBeFalse()
        }

    /**
     * Every document whose hash shares the prefix in an entry's name decides for it: one not in trash
     * keeps it, and otherwise the latest trash time does. A prefix clash only ever keeps an entry
     * longer.
     */
    @Test
    fun purgeKeepsAnEntryWhileAnyDocumentSharingItsPrefixKeepsIt() =
        runTest {
            val entry = trashWithMtime("x/old.pdf", CLASHING_FIRST, mtime = 1_000)
            val clash = sha256Bytes(CLASHING_SECOND.encodeToByteArray())
            entry.id.take(NpnPaths.TRASH_HASH_PREFIX_LENGTH) shouldBe clash.take(NpnPaths.TRASH_HASH_PREFIX_LENGTH)
            val trashedLongAgo = entry.id to 100_000L

            trash.purgeOlderThan(500_000, mapOf(trashedLongAgo, clash to null))
            fs.exists(entry.entry).shouldBeTrue()
            trash.purgeOlderThan(500_000, mapOf(trashedLongAgo, clash to 900_000L))
            fs.exists(entry.entry).shouldBeTrue()
            trash.purgeOlderThan(500_000, mapOf(trashedLongAgo, clash to 200_000L))
            fs.exists(entry.entry).shouldBeFalse()
        }

    /**
     * A prefix is 32 bits: a document unknown here can share it with one long since purged. The entry
     * that collides holds the unknown document's only copy and stays however old the colliding
     * document is, while the colliding document's own entry goes.
     */
    @Test
    fun purgeKeepsAnEntryACollidingDocumentDoesNotOwn() =
        runTest {
            val colliding = trashWithMtime("x/colliding.pdf", CLASHING_FIRST, mtime = 1_000)
            // Another file's bytes under a name the old document's entry could carry.
            fs.writeBytesOnce("y/unknown.pdf", CLASHING_SECOND.encodeToByteArray())
            val entry = trash.moveToTrash("y/unknown.pdf", colliding.id)
            Files.setLastModifiedTime(root.resolve(entry), FileTime.fromMillis(1_000))

            trash.purgeOlderThan(500_000, mapOf(colliding.id to 100_000L))

            fs.exists(entry).shouldBeTrue()
            fs.exists(colliding.entry).shouldBeFalse()
        }

    @Test
    fun trashedNamesCarryTheirHashPrefix() {
        NpnPaths.trashHashPrefix("doc__deadbeef.pdf") shouldBe "deadbeef"
        NpnPaths.trashHashPrefix("doc__deadbeef-2.pdf") shouldBe "deadbeef"
        NpnPaths.trashHashPrefix("a__b.c__deadbeef.pdf") shouldBe "deadbeef"
        NpnPaths.trashHashPrefix("noext__deadbeef-1") shouldBe "deadbeef"
        NpnPaths.trashHashPrefix("doc.pdf") shouldBe null
    }

    /** A trashed file's entry, and the id whose hash prefix its name carries. */
    private class Trashed(
        val entry: String,
        val id: String,
    )

    /** Trash [relPath], holding [content], under a name carrying its real hash, then age the entry. */
    private suspend fun trashWithMtime(
        relPath: String,
        content: String,
        mtime: Long,
    ): Trashed {
        val bytes = content.encodeToByteArray()
        fs.writeBytesOnce(relPath, bytes)
        val id = sha256Bytes(bytes)
        val trashed = trash.moveToTrash(relPath, id)
        Files.setLastModifiedTime(root.resolve(trashed), FileTime.fromMillis(mtime))
        return Trashed(trashed, id)
    }

    /** Two documents whose hashes share their first 8 hex (ground; the pair TrashLifecycleTest clashes). */
    private companion object {
        const val CLASHING_FIRST = "trashed document 35434"
        const val CLASHING_SECOND = "trashed document 102888"
    }
}
