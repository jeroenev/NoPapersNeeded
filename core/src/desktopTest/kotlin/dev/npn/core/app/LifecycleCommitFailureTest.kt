package dev.npn.core.app

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.isLive
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.TrashOps
import dev.npn.core.fs.VerifiedPublicationFailedException
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.fs.trashEntries
import dev.npn.core.index.IndexRig
import dev.npn.core.index.JournalCoordinator
import dev.npn.core.index.MetadataAccumulator
import dev.npn.core.index.MutableClock
import dev.npn.core.index.WatchedFileSystem
import dev.npn.core.index.indexTest
import dev.npn.core.index.requireByDocId
import dev.npn.core.journal.ContentAddressedJournalWriter
import dev.npn.core.journal.HlcWatermarkAllocator
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths
import dev.npn.core.journal.PendingOp
import dev.npn.core.model.SystemProperty
import dev.npn.core.pathrules.StoragePathRuleApplicator
import dev.npn.core.pdf.PdfFixtures
import dev.npn.core.util.NpnLog
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.Collections
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** A trash, restore or delete whose journal commit fails leaves the file and the row as they were. */
class LifecycleCommitFailureTest {
    /**
     * A trash whose commit fails leaves nothing behind: the PDF is back where it was and so is its
     * row, so the document neither vanishes from the library nor shows in Trash with no file.
     */
    @Test
    fun aTrashWhoseCommitFailsLeavesThePdfAndTheRowAsTheyWere() =
        withLifecycle { lifecycle ->
            val rig = lifecycle.rig
            rig.writePdf("a.pdf", "alpha")
            lifecycle.coordinator.rescan()
            val id = rig.fs.sha256("a.pdf")
            val before = rig.db.documentDao().getByDocId(id)

            lifecycle.failures = 1
            assertFailsWith<VerifiedPublicationFailedException> { lifecycle.writer.trashDoc(id) }

            rig.exists("a.pdf").shouldBeTrue()
            rig.fs.trashEntries().shouldBeEmpty()
            rig.db.documentDao().getByDocId(id) shouldBe before

            lifecycle.writer.trashDoc(id)
            rig.exists("a.pdf").shouldBeFalse()
            rig.db
                .documentDao()
                .getByDocId(id)
                .shouldNotBeNull()
                .trashed
                .shouldBeTrue()
        }

    /** A restore whose commit fails after the move puts the PDF back, as a failed trash does. */
    @Test
    fun aRestoreWhoseCommitFailsPutsThePdfBackInTheTrash() =
        withLifecycle { lifecycle ->
            val rig = lifecycle.rig
            rig.writePdf("a.pdf", "alpha")
            lifecycle.coordinator.rescan()
            val id = rig.fs.sha256("a.pdf")
            lifecycle.writer.trashDoc(id)
            val before = rig.db.documentDao().getByDocId(id)

            lifecycle.failures = 1
            assertFailsWith<VerifiedPublicationFailedException> { lifecycle.writer.restoreDoc(id) }

            rig.exists("a.pdf").shouldBeFalse()
            rig.fs.trashEntries() shouldHaveSize 1
            rig.db.documentDao().getByDocId(id) shouldBe before

            lifecycle.writer.restoreDoc(id)
            rig.exists("a.pdf").shouldBeTrue()
            rig.fs.trashEntries().shouldBeEmpty()
            rig.db
                .documentDao()
                .getByDocId(id)
                .shouldNotBeNull()
                .isLive
                .shouldBeTrue()
        }

    /** A delete whose commit fails leaves the document in the trash, file and row, for a retry. */
    @Test
    fun aDeleteWhoseCommitFailsLeavesTheFileAndTheRowAsTheyWere() =
        withLifecycle { lifecycle ->
            val rig = lifecycle.rig
            rig.writePdf("a.pdf", "alpha")
            lifecycle.coordinator.rescan()
            val id = rig.fs.sha256("a.pdf")
            lifecycle.writer.trashDoc(id)
            val before = rig.db.documentDao().getByDocId(id)

            lifecycle.failures = 1
            assertFailsWith<VerifiedPublicationFailedException> { lifecycle.writer.deleteDocPermanently(id) }

            rig.fs.trashEntries() shouldHaveSize 1
            rig.db.documentDao().getByDocId(id) shouldBe before

            lifecycle.writer.deleteDocPermanently(id)
            rig.fs.trashEntries().shouldBeEmpty()
            rig.db
                .documentDao()
                .getByDocId(id)
                .shouldNotBeNull()
                .deleted
                .shouldBeTrue()
        }

    /**
     * Once the event is published the journal says trashed, so the trash stands even when projecting
     * it fails: moving the PDF back then would leave a trashed document with its file in the library.
     */
    @Test
    fun aTrashWhoseEventIsPublishedStandsWhenProjectingItFails() =
        withLifecycle(statFailuresOfObjects = 1) { lifecycle ->
            val rig = lifecycle.rig
            rig.writePdf("a.pdf", "alpha")
            lifecycle.coordinator.rescan()
            val id = rig.fs.sha256("a.pdf")

            lifecycle.writer.trashDoc(id)

            rig.exists("a.pdf").shouldBeFalse()
            rig.fs.trashEntries() shouldHaveSize 1
            lifecycle.coordinator.rescan()
            rig.db
                .documentDao()
                .getByDocId(id)
                .shouldNotBeNull()
                .trashed
                .shouldBeTrue()
        }

    /**
     * A [MetadataWriterImpl] and its coordinator over an [IndexRig] whose next [failures] journal
     * publishes fail; with [statFailuresOfObjects], that many reads of a published object's metadata
     * fail too, which fails projecting a commit after its publish.
     */
    private class Lifecycle(
        val rig: IndexRig,
        statFailuresOfObjects: Int,
    ) {
        var failures = 0
        private var statFailures = statFailuresOfObjects

        private val journalFs =
            object : LibraryFileSystem by rig.fs {
                override suspend fun publishVerifiedObjectOnce(
                    stagingPath: String,
                    canonicalPath: String,
                    expectedHash: String,
                ) {
                    if (failures-- > 0) throw VerifiedPublicationFailedException("rename failed")
                    rig.fs.publishVerifiedObjectOnce(stagingPath, canonicalPath, expectedHash)
                }

                override suspend fun stat(relPath: String): FileMeta? {
                    if (JournalPaths.classify(relPath) is JournalPath.ObjectV2 && statFailures-- > 0) {
                        throw IOException("stat failed")
                    }
                    return rig.fs.stat(relPath)
                }
            }

        val coordinator =
            JournalCoordinator(
                ContentAddressedJournalWriter(journalFs, "test-installation", HlcWatermarkAllocator(rig.clock)),
                rig.indexer,
                MetadataAccumulator(journalFs, rig.db, installationId = "test-installation"),
            )

        val writer =
            rig.metadataWriter(coordinator)
    }

    private fun withLifecycle(
        statFailuresOfObjects: Int = 0,
        block: suspend (Lifecycle) -> Unit,
    ) = indexTest { block(Lifecycle(it, statFailuresOfObjects)) }
}
