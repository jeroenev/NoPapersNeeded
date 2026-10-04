package dev.npn.core.index

import dev.npn.core.app.JournalCoordinator
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.ProjectionStatus
import dev.npn.core.db.npnDatabase
import dev.npn.core.fs.ExternalSource
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.FsEvent
import dev.npn.core.fs.JvmLibraryFileSystem
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.PathExternalIo
import dev.npn.core.fs.VerifiedPublicationCapability
import dev.npn.core.journal.ContentAddressedJournalWriter
import dev.npn.core.journal.HlcWatermarkAllocator
import dev.npn.core.journal.PendingOp
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.ReviewMode
import dev.npn.core.model.SystemProperty
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * A [LibraryFileSystem] that delegates everything to a real [JvmLibraryFileSystem] but records
 * every [readBytes] call so tests can assert the reconcile short-circuit avoids re-reading
 * already-applied, unchanged journal sources.
 */
private class CountingFileSystem(
    private val delegate: JvmLibraryFileSystem,
) : LibraryFileSystem {
    val reads = ArrayList<String>()

    fun journalReads(): List<String> = reads.filter { it.startsWith("${NpnPaths.JOURNAL_DIR}/") }

    override val verifiedPublicationCapability: VerifiedPublicationCapability
        get() = delegate.verifiedPublicationCapability

    override suspend fun listAll(): List<FileMeta> = delegate.listAll()

    override suspend fun listAllUnder(relPath: String): List<FileMeta> = delegate.listAllUnder(relPath)

    override suspend fun exists(relPath: String): Boolean = delegate.exists(relPath)

    override suspend fun stat(relPath: String): FileMeta? = delegate.stat(relPath)

    override suspend fun readBytes(relPath: String): ByteArray {
        reads.add(relPath)
        return delegate.readBytes(relPath)
    }

    override suspend fun writeBytesOnce(
        relPath: String,
        bytes: ByteArray,
    ) = delegate.writeBytesOnce(relPath, bytes)

    override suspend fun publishVerifiedObjectOnce(
        stagingPath: String,
        canonicalPath: String,
        expectedHash: String,
    ) = delegate.publishVerifiedObjectOnce(stagingPath, canonicalPath, expectedHash)

    override suspend fun move(
        fromRelPath: String,
        toRelPath: String,
    ) = delegate.move(fromRelPath, toRelPath)

    override suspend fun delete(relPath: String) = delegate.delete(relPath)

    override suspend fun mkdirs(relPath: String) = delegate.mkdirs(relPath)

    override suspend fun sha256(relPath: String): String = delegate.sha256(relPath)

    override suspend fun importExternal(
        source: ExternalSource,
        toRelPath: String,
    ) = delegate.importExternal(source, toRelPath)

    override fun watch(): Flow<FsEvent> = emptyFlow()
}

/**
 * Exercises the two reconcile performance fixes at the accumulator + coordinator level:
 *  1. the stat-prefix short-circuit (no byte reads for already-applied unchanged sources), and
 *  2. bounded reprojection on reconcile (new events projected via refreshEvents, falling back to
 *     a full refold on mutation/removal/first-load).
 */
class ReconcilePerfTest {
    private lateinit var libRoot: Path
    private lateinit var dbDir: File
    private lateinit var delegate: JvmLibraryFileSystem
    private lateinit var fs: CountingFileSystem
    private lateinit var db: NpnDatabase
    private lateinit var clock: MutableClock
    private lateinit var indexer: Indexer
    private lateinit var accumulator: MetadataAccumulator

    @BeforeTest
    fun setup() {
        libRoot = Files.createTempDirectory("npn-reconcile-lib")
        dbDir = Files.createTempDirectory("npn-reconcile-db").toFile()
        delegate = JvmLibraryFileSystem(libRoot, PathExternalIo)
        fs = CountingFileSystem(delegate)
        db = npnDatabase(File(dbDir, "index.db"))
        clock = MutableClock(1_000L)
        indexer = Indexer(fs, db, clock)
        accumulator = MetadataAccumulator(fs, db, installationId = "test-installation")
    }

    @AfterTest
    fun teardown() {
        db.close()
        libRoot.toFile().deleteRecursively()
        dbDir.deleteRecursively()
    }

    private fun writePdf(
        relPath: String,
        content: String,
    ) {
        val p = libRoot.resolve(relPath)
        Files.createDirectories(p.parent)
        Files.write(p, content.encodeToByteArray())
    }

    /** A journal writer for [installation], as another replica's writes arrive. */
    private fun writer(installation: String): ContentAddressedJournalWriter =
        ContentAddressedJournalWriter(fs, installation, HlcWatermarkAllocator(clock))

    private fun coordinator(writer: ContentAddressedJournalWriter): JournalCoordinator =
        JournalCoordinator(writer, indexer, accumulator)

    /**
     * A coordinator over freshly-built engine pieces sharing the same library folder and index DB
     * — what the next app launch gets. Nothing carries over in memory, so this is the honest way
     * to test what opening an existing library costs and preserves.
     */
    private fun reopen(deviceId: String = "dev-main"): JournalCoordinator =
        JournalCoordinator(writer(deviceId), Indexer(fs, db, clock), freshAccumulator())

    private fun freshAccumulator(): MetadataAccumulator =
        MetadataAccumulator(fs, db, installationId = "test-installation")

    private suspend fun rescan(): dev.npn.core.index.ScanReport {
        val state = accumulator.reconcile().state ?: accumulator.loadState()
        val report = indexer.rescan(metadataState = state)
        accumulator.markReady(state.maxHlc)
        return report
    }

    @Test
    fun secondReconcileDoesNotRereadUnchangedAppliedSources() =
        runTest {
            writePdf("a.pdf", "alpha")
            rescan()
            val docId = db.documentDao().rowAtPath("a.pdf")!!.docId

            // A committed tag publishes a journal object; project it through the coordinator.
            val writer = writer("dev-main")
            val coordinator = coordinator(writer)
            coordinator.refreshState()
            clock.advance()
            coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "invoice")))

            // First reconcile after the commit reads the newly-written object at most once.
            coordinator.reconcileMetadata()
            fs.reads.clear()

            // Second reconcile with nothing changed must not read any journal bytes at all.
            coordinator.reconcileMetadata()
            fs.journalReads() shouldContainExactly emptyList()

            // Tag still projected (no regression from the skip).
            db.tagDao().tagsForDocument(docId) shouldContainExactly listOf("invoice")
        }

    @Test
    fun mutatedV1SourceIsRereadAndShrinkStillFails() =
        runTest {
            writePdf("a.pdf", "alpha")
            rescan()
            val docId = db.documentDao().rowAtPath("a.pdf")!!.docId

            // An older build's v1 segment, as libraries still hold them.
            clock.advance()
            val segment = LegacySegments(fs, "dev-main", clock).write(listOf(PendingOp.tagAdd(docId, "invoice")))
            val coordinator = coordinator(writer("dev-main"))
            coordinator.refreshState()
            coordinator.reconcileMetadata()

            val original = fs.readBytes(segment)
            fs.reads.clear()

            // Grow the file (append padding) so size+mtime change -> it must be re-read, and the
            // padded content still folds to the same tag.
            val grown = original + "\n".repeat(64).encodeToByteArray()
            Files.write(libRoot.resolve(segment), grown)
            clock.advance()
            coordinator.reconcileMetadata()
            fs.journalReads().contains(segment).shouldBeTrue()
            db.tagDao().tagsForDocument(docId) shouldContainExactly listOf("invoice")

            // Now shrink it below the recorded size -> the existing shrink guard must still latch.
            Files.write(libRoot.resolve(segment), "x".encodeToByteArray())
            clock.advance()
            assertFailsWith<MetadataWriteBlocked> { coordinator.reconcileMetadata() }
            db.metadataProjectionDao().state()!!.migrationState shouldBe ProjectionStatus.FAILED
        }

    @Test
    fun incrementalReconcileMatchesFullRefreshProjection() =
        runTest {
            writePdf("a.pdf", "alpha")
            writePdf("b.pdf", "bravo")
            rescan()
            val a = db.documentDao().rowAtPath("a.pdf")!!.docId
            val b = db.documentDao().rowAtPath("b.pdf")!!.docId

            val writer = writer("dev-main")
            val coordinator = coordinator(writer)
            coordinator.refreshState()

            // Commit some metadata locally (recorded directly, no reconcile read needed).
            clock.advance()
            coordinator.commitAndApply(
                listOf(
                    PendingOp.tagAdd(a, "receipt"),
                    PendingOp.fieldSet(a, FieldKeys.CORRESPONDENT, "acme"),
                ),
            )

            // A SECOND device publishes an object that reconcile will discover as a brand-new source.
            val remote = writer("device-two")
            clock.advance()
            remote.commit(
                listOf(
                    PendingOp.tagAdd(b, "urgent"),
                    PendingOp.propSet(b, SystemProperty.REVIEWED, "true"),
                ),
            )

            // Reconcile via the bounded (incremental) path.
            coordinator.reconcileMetadata()

            val incrementalTagsA = db.tagDao().tagsForDocument(a)
            val incrementalTagsB = db.tagDao().tagsForDocument(b)
            val incrementalB = db.documentDao().getByDocId(b)!!

            incrementalTagsA shouldContainExactly listOf("receipt")
            incrementalTagsB shouldContainExactly listOf("urgent")
            incrementalB.reviewed shouldBe true

            // Now force a full refold from the DB and assert the projection is identical.
            val fullState = accumulator.loadState()
            indexer.refreshState(fullState)

            db.tagDao().tagsForDocument(a) shouldContainExactly incrementalTagsA
            db.tagDao().tagsForDocument(b) shouldContainExactly incrementalTagsB
            val fullB = db.documentDao().getByDocId(b)!!
            fullB.reviewed shouldBe incrementalB.reviewed
            db.documentDao().getByDocId(a)!!.correspondent shouldBe "acme"
        }

    /**
     * Seed a library with one document carrying a tag and a synced setting, leaving the projection
     * in the ready state a clean shutdown would. Returns the document id.
     */
    private suspend fun seedReadyLibrary(): String {
        writePdf("a.pdf", "alpha")
        rescan()
        val docId = db.documentDao().rowAtPath("a.pdf")!!.docId
        val coordinator = coordinator(writer("dev-main"))
        coordinator.refreshState()
        clock.advance()
        coordinator.commitAndApply(
            listOf(
                PendingOp.tagAdd(docId, "invoice"),
                PendingOp.settingSet(ReviewMode.WIRE_KEY, ReviewMode.UNTAGGED_ONLY.wire),
                PendingOp.settingSet("someLaterSetting", "on"),
            ),
        )
        return docId
    }

    @Test
    fun openWithUnchangedJournalReusesTheStoredProjection() =
        runTest {
            seedReadyLibrary()

            // Nothing touched the journal since the last session marked the projection ready, so the
            // open path must recognise the stored projection as current rather than refolding.
            freshAccumulator().reconcileOnOpen().shouldBeInstanceOf<OpenReconciliation.ProjectionCurrent>()
        }

    @Test
    fun openOnAFreshIndexRefolds() =
        runTest {
            writePdf("a.pdf", "alpha")

            // No projection state at all: nothing to reuse, so it must fold.
            freshAccumulator().reconcileOnOpen().shouldBeInstanceOf<OpenReconciliation.Refolded>()
        }

    @Test
    fun openAfterAnotherDeviceWroteRefolds() =
        runTest {
            val docId = seedReadyLibrary()

            clock.advance()
            writer("device-two").commit(listOf(PendingOp.tagAdd(docId, "urgent")))

            val outcome = freshAccumulator().reconcileOnOpen()
            outcome.shouldBeInstanceOf<OpenReconciliation.Refolded>()
            // And the refold really did pick the remote event up.
            outcome.state.effectiveTags(docId) shouldContain "urgent"
        }

    @Test
    fun reusedProjectionKeepsTagsPropsAndSyncedSettings() =
        runTest {
            val docId = seedReadyLibrary()
            val expectedHlc = db.metadataProjectionDao().state()!!

            val reopened = reopen()
            reopened.refreshState()

            // The document projection survived untouched...
            db.tagDao().tagsForDocument(docId) shouldContainExactly listOf("invoice")
            // ...the synced settings were seeded without folding the whole journal, every one of
            // them rather than a list of known keys...
            reopened.settingFlow(ReviewMode.WIRE_KEY).first() shouldBe ReviewMode.UNTAGGED_ONLY.wire
            reopened.settingFlow("someLaterSetting").first() shouldBe "on"
            // ...and the ready marker (with its watermark) was left as the last session wrote it.
            val after = db.metadataProjectionDao().state()!!
            after.migrationState shouldBe ProjectionStatus.READY
            after.sourceMaxPhysical shouldBe expectedHlc.sourceMaxPhysical
            after.sourceSetFingerprint shouldBe expectedHlc.sourceSetFingerprint
        }

    /**
     * The whole point of leaving the fold unmaterialized is that writes still work. A tag-remove
     * is the sharpest case: building it needs the OR-set dots currently observed for (tag, doc),
     * which only exist in the folded state, so this fails outright if laziness is wrong.
     */
    @Test
    fun writeAfterAReusedOpenFoldsOnDemandAndSeesLiveDots() =
        runTest {
            val docId = seedReadyLibrary()

            val reopened = reopen()
            reopened.refreshState()
            clock.advance()

            reopened.commitBuilt { state -> state.tagRemovalOps(listOf(docId), "invoice") }

            db.tagDao().tagsForDocument(docId) shouldContainExactly emptyList()
        }

    /** A reused open must leave the next one reusable too, not silently poison the fingerprint. */
    @Test
    fun reusedOpenStaysReusableOnTheFollowingOpen() =
        runTest {
            seedReadyLibrary()

            reopen().refreshState()
            freshAccumulator().reconcileOnOpen().shouldBeInstanceOf<OpenReconciliation.ProjectionCurrent>()

            reopen().refreshState()
            freshAccumulator().reconcileOnOpen().shouldBeInstanceOf<OpenReconciliation.ProjectionCurrent>()
        }

    /**
     * The periodic metadata tick runs every 15 minutes for the life of the session. When nothing
     * changed it must not replay the journal — that would make an idle app pay boot cost forever.
     */
    @Test
    fun idleMetadataReconcileReadsNothingAndFoldsNothing() =
        runTest {
            seedReadyLibrary()

            val reopened = reopen()
            reopened.refreshState()
            fs.reads.clear()

            reopened.reconcileMetadata()

            fs.journalReads() shouldContainExactly emptyList()
            freshAccumulator().reconcile(currentState = null).state shouldBe null
        }

    @Test
    fun removedSourceTriggersFullReprojectionAndValidation() =
        runTest {
            writePdf("a.pdf", "alpha")
            rescan()
            val docId = db.documentDao().rowAtPath("a.pdf")!!.docId

            clock.advance()
            val segment = LegacySegments(fs, "dev-main", clock).write(listOf(PendingOp.tagAdd(docId, "invoice")))
            val coordinator = coordinator(writer("dev-main"))
            coordinator.refreshState()
            coordinator.reconcileMetadata()

            // Deleting a previously-applied v1 source with no validated replacement must latch the
            // write-block via validatePublishedObjects (unchanged behaviour under the new path).
            Files.delete(libRoot.resolve(segment))
            clock.advance()
            assertFailsWith<MetadataWriteBlocked> { coordinator.reconcileMetadata() }
            db.metadataProjectionDao().state()!!.migrationState shouldBe ProjectionStatus.FAILED
        }
}
