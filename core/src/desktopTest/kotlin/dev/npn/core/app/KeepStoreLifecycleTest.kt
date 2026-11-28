package dev.npn.core.app

import dev.npn.core.fs.DeleteAuthority
import dev.npn.core.fs.DocumentFacts
import dev.npn.core.fs.KeepStore
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.trashEntries
import dev.npn.core.index.IndexRig
import dev.npn.core.index.JournalCoordinator
import dev.npn.core.journal.EventDecoderRegistry
import dev.npn.core.journal.FileStat
import dev.npn.core.journal.JournalOp
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Trash, restore and delete for good while every device reads the keep store ([KeepGate]): the
 * event first, then the file follows it into or out of the store; and what the store holds, which a
 * rescan copies back where the journal shows a restored document whose file is gone.
 */
class KeepStoreLifecycleTest {
    private val rig = IndexRig.create()
    private val coordinator = rig.coordinator()
    private val keep = KeepStore(rig.fs, "test-installation")
    private val lifecycle = rig.docLifecycle(coordinator, keepOn = true)

    @AfterTest
    fun teardown() = rig.close()

    /** A trash records its file's stat, then copies the file into the store and deletes it: no trash entry. */
    @Test
    fun aTrashKeepsItsFileInTheStoreAfterItsEvent() =
        runTest {
            val doc = indexed("inbox/a.pdf", "kept")
            val stat = rig.fs.stat("inbox/a.pdf").shouldNotBeNull()

            lifecycle.trashDoc(doc).shouldNotBeNull()

            rig.exists("inbox/a.pdf").shouldBeFalse()
            rig.fs.trashEntries().shouldBeEmpty()
            rig.fs.sha256(keep.entry(doc).shouldNotBeNull().path) shouldBe doc
            trashOf(doc) shouldBe JournalOp.DocTrash("inbox/a.pdf", FileStat(stat.size, stat.mtimeMillis))
            rowOf(doc).trashed.shouldBeTrue()
        }

    /** A restore copies the document back from the store, which keeps its copy until the purge. */
    @Test
    fun aRestoreCopiesTheDocumentBackAndTheStoreKeepsItsCopy() =
        runTest {
            val doc = indexed("inbox/a.pdf", "restored")
            lifecycle.trashDoc(doc)

            lifecycle.restoreDoc(doc)

            rig.fs.sha256("inbox/a.pdf") shouldBe doc
            keep.entry(doc).shouldNotBeNull()
            coordinator.rescan()
            rowOf(doc).let {
                it.trashed.shouldBeFalse()
                it.missing.shouldBeFalse()
                it.relPath shouldBe "inbox/a.pdf"
            }
        }

    /** A delete for good takes the store's copy with the rest of the document's bytes. */
    @Test
    fun aDeleteForGoodTakesTheStoresCopy() =
        runTest {
            val doc = indexed("a.pdf", "deleted")
            lifecycle.trashDoc(doc)

            lifecycle.deleteDocPermanently(doc) shouldBe PermanentDeleteOutcome.DELETED

            keep.entry(doc).shouldBeNull()
            rig.exists("a.pdf").shouldBeFalse()
        }

    /**
     * A restored document whose file is gone (another device's trash move, carried late, took it) is
     * copied back by the next rescan: the journal shows it, and the store holds it. One never restored
     * is not: a file the user deleted outside the app stays deleted.
     */
    @Test
    fun aRestoredDocumentsMissingFileComesBackFromTheStoreAndNoOtherDoes() =
        runTest {
            val restored = indexed("a.pdf", "restored, then taken")
            val never = indexed("b.pdf", "never trashed")
            lifecycle.trashDoc(restored)
            lifecycle.restoreDoc(restored)
            coordinator.rescan()
            keep.put("b.pdf", never, episodeMillis = 1)
            Files.delete(rig.libRoot.resolve("a.pdf"))
            Files.delete(rig.libRoot.resolve("b.pdf"))

            coordinator.rescan()

            rig.fs.sha256("a.pdf") shouldBe restored
            rowOf(restored).missing.shouldBeFalse()
            rig.exists("b.pdf").shouldBeFalse()
            rowOf(never).missing.shouldBeTrue()
        }

    /**
     * An entry of a later episode than the document's last restore belongs to a trash whose event has
     * not arrived yet: another device trashed the document again, and its file's deletion came here
     * first. Copying it back at once would leave that trash's file in the folder for good, so it
     * comes back only once it has stood the settle window with no trash in the journal.
     */
    @Test
    fun anEntryOfALaterEpisodeThanTheLastRestoreWaitsOutTheWindow() =
        runTest {
            val doc = indexed("a.pdf", "trashed again elsewhere")
            lifecycle.trashDoc(doc)
            lifecycle.restoreDoc(doc)
            coordinator.rescan()
            rig.clock.advance(MINUTE)
            keep.put("a.pdf", doc, episodeMillis = rig.clock.now)
            Files.delete(rig.libRoot.resolve("a.pdf"))

            coordinator.rescan()
            rig.exists("a.pdf").shouldBeFalse()
            rowOf(doc).missing.shouldBeTrue()

            rig.clock.advance(JournalCoordinator.RESTORE_SETTLE_MS + MINUTE)
            coordinator.rescan()
            rig.fs.sha256("a.pdf") shouldBe doc
        }

    /**
     * An entry holds the bytes its name is the hash of, one per episode: a copy of other bytes is
     * refused, with nothing left behind; and only an authority the journal holds deletes one.
     */
    @Test
    fun theStoreHoldsOnlyItsNamesBytesAndDeletesOnlyByAFact() =
        runTest {
            rig.writePdf("a.pdf", "these bytes")
            rig.writePdf("b.pdf", "other bytes")
            val doc = rig.fs.sha256("a.pdf")

            keep.put("b.pdf", doc, episodeMillis = 1).shouldBeNull()
            rig.fs
                .listAllUnder(NpnPaths.KEEP_DIR)
                .filter { !it.isDirectory }
                .shouldBeEmpty()

            keep.put("a.pdf", doc, episodeMillis = 1)
            keep.put("a.pdf", doc, episodeMillis = 1)
            keep.put("a.pdf", doc, episodeMillis = 2)
            keep.entries().map { it.sha to it.episode } shouldBe listOf(doc to 1L, doc to 2L)
            val first = keep.entriesOf(doc).first { it.episode == 1L }
            keep.reap(first, DeleteAuthority.Trash(DocumentFacts.None)).shouldBeFalse()
            keep.reap(first, DeleteAuthority.ExtraCopy("a.pdf")).shouldBeTrue()
            keep.entries().map { it.episode } shouldBe listOf(2L)
        }

    private companion object {
        const val MINUTE = 60_000L
    }

    private suspend fun indexed(
        path: String,
        content: String,
    ): String {
        rig.writePdf(path, content)
        coordinator.rescan()
        return rig.fs.sha256(path)
    }

    private suspend fun rowOf(docId: String) =
        rig.db
            .documentDao()
            .getByDocId(docId)
            .shouldNotBeNull()

    private suspend fun trashOf(docId: String): JournalOp =
        rig.db
            .metadataProjectionDao()
            .eventsForDocument(docId)
            .single { it.operation == JournalOp.DocTrash.WIRE }
            .let { EventDecoderRegistry.decodeStored(it.canonicalJson).op }
}
