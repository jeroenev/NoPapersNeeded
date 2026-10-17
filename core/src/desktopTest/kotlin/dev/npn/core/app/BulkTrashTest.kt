package dev.npn.core.app

import dev.npn.core.db.isLive
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.trashEntries
import dev.npn.core.index.IndexRig
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import java.util.Collections
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Restoring or deleting for good every document in the trash lists the trash once, before any of
 * them takes the writer lock, rather than twice per document with one of those under the lock; a
 * copy that reaches the trash after that listing is still found.
 */
class BulkTrashTest {
    private val rig = IndexRig.create()

    /** The coordinator's dispatcher: what runs on it runs under the writer lock. */
    private val underTheLock = NamedDefault()
    private val files = TrashListings(rig.fs, underTheLock)
    private val coordinator =
        rig.coordinator(dispatcher = underTheLock)
    private val writer =
        rig.metadataWriter(coordinator, files)

    @AfterTest
    fun teardown() = rig.close()

    @Test
    fun restoringEveryTrashedDocumentListsTheTrashOnceOutsideTheLock() =
        runTest {
            val docs = trashed()

            writer.restoreDocs(docs.keys.toList()) shouldBe 0

            files.listings shouldBe listOf(false)
            docs.forEach { (id, path) -> rig.fs.sha256(path) shouldBe id }
            rig.fs.trashEntries().shouldBeEmpty()
        }

    @Test
    fun deletingEveryTrashedDocumentForGoodListsTheTrashOnceOutsideTheLock() =
        runTest {
            val docs = trashed()

            writer.deleteDocsPermanently(docs.keys.toList()) shouldBe PermanentDeleteTally()

            files.listings shouldBe listOf(false)
            docs.keys.forEach {
                rig.db
                    .documentDao()
                    .getByDocId(it)
                    .shouldNotBeNull()
                    .deleted
                    .shouldBeTrue()
            }
            rig.fs.trashEntries().shouldBeEmpty()
        }

    /**
     * Emptying the trash, one of its documents is restored after the trash was listed, before its
     * turn came: that document is no longer the trash's, and stays as the restore left it. Leaving it
     * is no failure.
     */
    @Test
    fun aDocumentRestoredWhileTheTrashIsEmptiedIsLeftAlone() =
        runTest {
            val docs = trashed()
            val (first, second) = docs.keys.take(2)
            files.afterNextListing = { writer.restoreDoc(second) }

            writer.deleteDocsPermanently(listOf(first, second)) shouldBe PermanentDeleteTally()

            rig.fs.sha256(docs.getValue(second)) shouldBe second
            rig.db
                .documentDao()
                .getByDocId(second)
                .shouldNotBeNull()
                .isLive
                .shouldBeTrue()
            rig.db
                .documentDao()
                .getByDocId(first)
                .shouldNotBeNull()
                .deleted
                .shouldBeTrue()
            rig.exists(docs.getValue(first)).shouldBeFalse()
        }

    /**
     * A document's copy reaches the trash after the restore listed it, before the restore took the
     * lock, as a sync delivering another device's trash move does: the restore still finds it there
     * and brings the document back with its bytes, rather than without a file.
     */
    @Test
    fun aCopyThatReachesTheTrashAfterItWasListedIsStillRestored() =
        runTest {
            val (id, path) = trashed().entries.first()
            val entry =
                rig.fs
                    .trashEntries()
                    .single { rig.fs.sha256(it.relPath) == id }
                    .relPath
            rig.fs.move(entry, "elsewhere.bin")
            files.afterNextListing = { rig.fs.move("elsewhere.bin", entry) }

            writer.restoreDoc(id)

            rig.fs.sha256(path) shouldBe id
            rig.fs.exists(entry).shouldBeFalse()
        }

    /** Five documents in the trash, by id, each with the path it came from; the listings so far forgotten. */
    private suspend fun trashed(): Map<String, String> {
        val paths = (1..5).map { "doc $it.pdf" }
        paths.forEach { rig.writePdf(it, "content of $it") }
        coordinator.rescan()
        val docs = paths.associateBy { rig.fs.sha256(it) }
        docs.keys.forEach { writer.trashDoc(it) }
        files.listings.clear()
        return docs
    }

    /** [threads] as a dispatcher instance of its own, so a coroutine can tell it runs there. */
    private class NamedDefault(
        private val threads: CoroutineDispatcher = Dispatchers.Default,
    ) : CoroutineDispatcher() {
        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) = threads.dispatch(context, block)
    }

    /**
     * [fs], noting of each listing of the trash whether it ran on [locked], under the writer lock, and
     * running [afterNextListing] once right after the next one.
     */
    private class TrashListings(
        private val fs: LibraryFileSystem,
        private val locked: CoroutineDispatcher,
    ) : LibraryFileSystem by fs {
        val listings: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
        var afterNextListing: (suspend () -> Unit)? = null

        override suspend fun listAllUnder(relPath: String): List<FileMeta> {
            if (relPath != NpnPaths.TRASH_DIR) return fs.listAllUnder(relPath)
            listings += currentCoroutineContext()[ContinuationInterceptor] === locked
            val listed = fs.listAllUnder(relPath)
            afterNextListing?.also { afterNextListing = null }?.invoke()
            return listed
        }
    }
}
