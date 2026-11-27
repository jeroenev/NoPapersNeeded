package dev.npn.core.fs

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.Test

class TrashOpsTest {
    private val root: Path = Files.createTempDirectory("npn-trash-test")
    private val fs = JvmLibraryFileSystem(root, PathExternalIo)
    private val trash = TrashOps(fs)

    @AfterTest
    fun teardown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun moveToTrashAndRestoreRoundtrip() =
        runTest {
            fs.writeBytesOnce("inbox/doc.pdf", "content".encodeToByteArray())
            val trashRel = moveToTrash("inbox/doc.pdf", "deadbeefcafebabe", 1_000)
            trashRel shouldBe ".npn/trash/doc__deadbeef-1000.pdf"
            fs.exists("inbox/doc.pdf").shouldBeFalse()
            fs.exists(trashRel).shouldBeTrue()

            val restored = restore(trashRel, "inbox/doc.pdf")
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
            val kept = moveToTrash("a/doc.pdf", id, 1_000)
            val changed = moveToTrash("b/doc.pdf", id, 2_000)
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

    /** Two trashes whose name would collide (same millisecond) take consecutive episodes. */
    @Test
    fun moveToTrashKeepsEpisodesDistinctWhenAMillisecondCollides() =
        runTest {
            fs.writeBytesOnce("a/doc.pdf", "1".encodeToByteArray())
            fs.writeBytesOnce("b/doc.pdf", "2".encodeToByteArray())
            // Same name + same hash prefix in the same millisecond -> the episode counts up.
            val first = moveToTrash("a/doc.pdf", "deadbeefcafebabe", 1_000)
            val second = moveToTrash("b/doc.pdf", "deadbeefcafebabe", 1_000)
            first shouldBe ".npn/trash/doc__deadbeef-1000.pdf"
            second shouldBe ".npn/trash/doc__deadbeef-1001.pdf"
            trash.listTrash() shouldHaveSize 2
        }

    @Test
    fun aMoveIntoTheTrashThatALeftTemporaryFileIsNotATrashEntry() =
        runTest {
            fs.writeBytesOnce("inbox/doc.pdf", "content".encodeToByteArray())
            val trashed = moveToTrash("inbox/doc.pdf", "deadbeefcafebabe", 1_000)
            fs.writeBytesOnce(
                "${NpnPaths.TRASH_DIR}/${NpnPaths.Temp.WRITE_PREFIX}interrupted${NpnPaths.Temp.PART}",
                "partial".encodeToByteArray(),
            )

            trash.listTrash().map { it.relPath } shouldBe listOf(trashed)
        }

    /** A name the library cannot address is no trash entry: listing and purging pass over it. */
    @Test
    fun aTrashedNameWithABackslashIsPassedOver() =
        runTest {
            assumeTrue(File.separatorChar == '/')
            val old = trashWithMtime("x/old.pdf", "old", mtime = 1_000, episodeMillis = 1_000)
            val odd = root.resolve(NpnPaths.TRASH_DIR).resolve("a\\b__00000000.pdf")
            Files.write(odd, "odd".encodeToByteArray())
            Files.setLastModifiedTime(odd, FileTime.fromMillis(1000))

            trash.listTrash().map { it.relPath } shouldBe listOf(old.entry)
            purge(cutoffMillis = 500_000, documents = mapOf(old.id to 1_000L))
            fs.exists(old.entry).shouldBeFalse()
            Files.exists(odd).shouldBeTrue()
        }

    @Test
    fun restoreUniquifiesWhenTargetTaken() =
        runTest {
            fs.writeBytesOnce("inbox/doc.pdf", "orig".encodeToByteArray())
            val trashRel = moveToTrash("inbox/doc.pdf", "deadbeefcafebabe", 1_000)
            // A new file now occupies the original path.
            fs.writeBytesOnce("inbox/doc.pdf", "new".encodeToByteArray())
            val restored = restore(trashRel, "inbox/doc.pdf", docId = "deadbeefcafebabe")
            restored shouldBe "inbox/doc (restored, dead).pdf"
            fs.readBytes(restored).decodeToString() shouldBe "orig"
            fs.readBytes("inbox/doc.pdf").decodeToString() shouldBe "new"
        }

    /** An entry goes once its document was trashed (or deleted for good) before the cutoff, and not before. */
    @Test
    fun purgeOlderThanRemovesOldEntries() =
        runTest {
            val old = trashWithMtime("x/old.pdf", "old bytes", mtime = 1_000, episodeMillis = 100_000)
            val recent = trashWithMtime("y/recent.pdf", "recent bytes", mtime = 1_000, episodeMillis = 900_000)

            purge(
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
            val recent = trashWithMtime("x/recent.pdf", "recent bytes", mtime = 1_000, episodeMillis = 1_000)
            val longAgo = trashWithMtime("y/longago.pdf", "long ago bytes", mtime = 1_000, episodeMillis = 1_000)

            purge(
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
            val moving = trashWithMtime("x/moving.pdf", "moving bytes", mtime = 1_000, episodeMillis = 1_000)

            purge(cutoffMillis = 500_000, documents = mapOf(moving.id to null))

            fs.exists(moving.entry).shouldBeTrue()
        }

    /**
     * A restore and a trash again make a new episode, whose entry a synced library can receive
     * before its events. A journal still reading the old, long-past trash must not take the new
     * episode's entry — the document's only copy — for the old one's.
     */
    @Test
    fun purgeKeepsAnEntryOfANewerEpisodeThanItsJournalShows() =
        runTest {
            val reTrashed =
                trashWithMtime("x/doc.pdf", "restored and trashed again", mtime = 1_000, episodeMillis = 900_000)

            // What a journal holding only the first trash (long past) says about it.
            purge(cutoffMillis = 500_000, documents = mapOf(reTrashed.id to 100_000L))

            fs.exists(reTrashed.entry).shouldBeTrue()
        }

    /**
     * An entry no document owns, however old its mtime, is kept: nothing says when it was trashed. It
     * may be another device's trash whose event has not arrived, or a document a crash stranded here.
     */
    @Test
    fun purgeKeepsAnEntryWithNoDocument() =
        runTest {
            val orphan = trashWithMtime("x/orphan.pdf", "orphan bytes", mtime = 1_000, episodeMillis = 1_000)
            val stray = root.resolve(NpnPaths.TRASH_DIR).resolve("stray.pdf")
            Files.write(stray, "stray".encodeToByteArray())
            Files.setLastModifiedTime(stray, FileTime.fromMillis(1_000))
            val owned = trashWithMtime("x/owned.pdf", "owned bytes", mtime = 1_000, episodeMillis = 1_000)

            purge(cutoffMillis = 500_000, documents = mapOf(owned.id to 100_000L))

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
            val entry = trashWithMtime("x/old.pdf", CLASHING_FIRST, mtime = 1_000, episodeMillis = 100_000)
            val clash = sha256Bytes(CLASHING_SECOND.encodeToByteArray())
            entry.id.take(NpnPaths.TRASH_HASH_PREFIX_LENGTH) shouldBe clash.take(NpnPaths.TRASH_HASH_PREFIX_LENGTH)
            val trashedLongAgo = entry.id to 100_000L

            purge(500_000, mapOf(trashedLongAgo, clash to null))
            fs.exists(entry.entry).shouldBeTrue()
            purge(500_000, mapOf(trashedLongAgo, clash to 900_000L))
            fs.exists(entry.entry).shouldBeTrue()
            purge(500_000, mapOf(trashedLongAgo, clash to 200_000L))
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
            val colliding = trashWithMtime("x/colliding.pdf", CLASHING_FIRST, mtime = 1_000, episodeMillis = 100_000)
            // Another file's bytes under a name the old document's entry could carry.
            fs.writeBytesOnce("y/unknown.pdf", CLASHING_SECOND.encodeToByteArray())
            val entry = moveToTrash("y/unknown.pdf", colliding.id, 100_000)
            Files.setLastModifiedTime(root.resolve(entry), FileTime.fromMillis(1_000))

            purge(500_000, mapOf(colliding.id to 100_000L))

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

    /**
     * The journal has a document in the trash, but the index's map of trash times, which a lagging
     * row can stretch, says it left long ago: the entry still goes only by the journal's fact.
     */
    @Test
    fun purgeKeepsAnEntryTheJournalDoesNotHaveInTheTrash() =
        runTest {
            val entry = trashWithMtime("x/kept.pdf", "kept bytes", mtime = 1_000, episodeMillis = 1_000)

            trash.purgeOlderThan(500_000, mapOf(entry.id to 100_000L), DocumentFacts.None)

            fs.exists(entry.entry).shouldBeTrue()
        }

    /** [TrashOps.purgeOlderThan] by a journal that has in the trash each of [documents] with a time. */
    private suspend fun purge(
        cutoffMillis: Long,
        documents: Map<String, Long?>,
    ) = trash.purgeOlderThan(cutoffMillis, documents, trashedIn(documents))

    /** A journal that has in the trash each of [documents] mapped to a time, and nothing else. */
    private fun trashedIn(documents: Map<String, Long?>): DocumentFacts =
        object : DocumentFacts {
            override suspend fun isDeleted(docId: String) = false

            override suspend fun isTrashed(docId: String) = documents[docId] != null

            override suspend fun successorOf(docId: String) = docId
        }

    /** Move [relPath] into the trash as the session's trash does, returning the entry. */
    private suspend fun moveToTrash(
        relPath: String,
        sha256: String,
        episodeMillis: Long,
    ): String = trash.trashTarget(relPath, sha256, episodeMillis).also { fs.move(relPath, it) }

    /** Move [trashRelPath], of [docId], back to [toRelPath], or beside it when a file is there, returning where. */
    private suspend fun restore(
        trashRelPath: String,
        toRelPath: String,
        docId: String = "0".repeat(64),
    ): String = trash.restoreTarget(toRelPath, docId, fs::exists).also { fs.move(trashRelPath, it) }

    /** A trashed file's entry, and the id whose hash prefix its name carries. */
    private class Trashed(
        val entry: String,
        val id: String,
    )

    /**
     * Trash [relPath], holding [content], under a name carrying its real hash and [episodeMillis],
     * then age the entry.
     */
    private suspend fun trashWithMtime(
        relPath: String,
        content: String,
        mtime: Long,
        episodeMillis: Long,
    ): Trashed {
        val bytes = content.encodeToByteArray()
        fs.writeBytesOnce(relPath, bytes)
        val id = sha256Bytes(bytes)
        val trashed = moveToTrash(relPath, id, episodeMillis)
        Files.setLastModifiedTime(root.resolve(trashed), FileTime.fromMillis(mtime))
        return Trashed(trashed, id)
    }

    /** Two documents whose hashes share their first 8 hex (ground; the pair TrashEntryTest clashes). */
    private companion object {
        const val CLASHING_FIRST = "trashed document 35434"
        const val CLASHING_SECOND = "trashed document 102888"
    }
}
