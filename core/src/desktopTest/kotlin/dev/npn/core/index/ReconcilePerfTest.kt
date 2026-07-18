package dev.npn.core.index

import dev.npn.core.app.JournalCoordinator
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.npnDatabase
import dev.npn.core.fs.ExternalSource
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.FsEvent
import dev.npn.core.fs.JvmLibraryFileSystem
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.VerifiedPublicationCapability
import dev.npn.core.fs.VerifiedPublicationResult
import dev.npn.core.journal.HlcClock
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.SegmentWriter
import dev.npn.core.model.DocProperty
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
private class CountingFileSystem(private val delegate: JvmLibraryFileSystem) : LibraryFileSystem {
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

    override suspend fun writeBytesOnce(relPath: String, bytes: ByteArray) =
        delegate.writeBytesOnce(relPath, bytes)

    override suspend fun publishVerifiedObjectOnce(
        stagingPath: String,
        canonicalPath: String,
        expectedHash: String,
    ): VerifiedPublicationResult = delegate.publishVerifiedObjectOnce(stagingPath, canonicalPath, expectedHash)

    override suspend fun move(fromRelPath: String, toRelPath: String) = delegate.move(fromRelPath, toRelPath)
    override suspend fun delete(relPath: String) = delegate.delete(relPath)
    override suspend fun mkdirs(relPath: String) = delegate.mkdirs(relPath)
    override suspend fun sha256(relPath: String): String = delegate.sha256(relPath)
    override suspend fun importExternal(source: ExternalSource, toRelPath: String) =
        delegate.importExternal(source, toRelPath)
    override suspend fun copyOut(relPath: String, target: ExternalSource) = delegate.copyOut(relPath, target)
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
    private lateinit var fsSegmentStore: FsSegmentStore

    @BeforeTest
    fun setup() {
        libRoot = Files.createTempDirectory("npn-reconcile-lib")
        dbDir = Files.createTempDirectory("npn-reconcile-db").toFile()
        delegate = JvmLibraryFileSystem(libRoot)
        fs = CountingFileSystem(delegate)
        db = npnDatabase(File(dbDir, "index.db"))
        clock = MutableClock(1_000L)
        indexer = Indexer(fs, db, clock)
        accumulator = MetadataAccumulator(fs, db, installationId = "test-installation")
        fsSegmentStore = FsSegmentStore(fs)
    }

    @AfterTest
    fun teardown() {
        db.close()
        libRoot.toFile().deleteRecursively()
        dbDir.deleteRecursively()
    }

    private fun writePdf(relPath: String, content: String) {
        val p = libRoot.resolve(relPath)
        Files.createDirectories(p.parent)
        Files.write(p, content.encodeToByteArray())
    }

    private fun writer(deviceId: String): SegmentWriter = SegmentWriter(
        store = fsSegmentStore,
        deviceId = deviceId,
        hlcClock = HlcClock(deviceId, clock),
        localState = DbLocalJournalState(db.keyValueDao()),
    )

    private fun coordinator(writer: SegmentWriter): JournalCoordinator =
        JournalCoordinator(writer, indexer, accumulator)

    private suspend fun rescan(): dev.npn.core.index.ScanReport {
        val state = accumulator.reconcile().state
        val report = indexer.rescan(metadataState = state)
        accumulator.markReady(state.maxHlc)
        return report
    }

    @Test
    fun secondReconcileDoesNotRereadUnchangedAppliedSources() = runTest {
        writePdf("a.pdf", "alpha")
        rescan()
        val docId = db.documentDao().getByPath("a.pdf")!!.docId

        // A committed tag produces a v1 segment; project it through the coordinator.
        val writer = writer("dev-main")
        val coordinator = coordinator(writer)
        coordinator.refreshState()
        clock.advance()
        coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "invoice")))

        // First reconcile after the commit reads the newly-written segment once.
        coordinator.reconcileMetadata()
        fs.reads.clear()

        // Second reconcile with nothing changed must not read any journal bytes at all.
        coordinator.reconcileMetadata()
        fs.journalReads() shouldContainExactly emptyList()

        // Tag still projected (no regression from the skip).
        db.tagDao().tagsForDocument(docId) shouldContainExactly listOf("invoice")
    }

    @Test
    fun mutatedV1SourceIsRereadAndShrinkStillFails() = runTest {
        writePdf("a.pdf", "alpha")
        rescan()
        val docId = db.documentDao().getByPath("a.pdf")!!.docId

        val writer = writer("dev-main")
        val coordinator = coordinator(writer)
        coordinator.refreshState()
        clock.advance()
        coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "invoice")))
        coordinator.reconcileMetadata()

        val segment = fs.listAllUnder(NpnPaths.JOURNAL_DIR)
            .single { NpnPaths.journalPath(it.relPath) is NpnPaths.JournalPath.Legacy }
        val original = fs.readBytes(segment.relPath)
        fs.reads.clear()

        // Grow the file (append padding) so size+mtime change -> it must be re-read, and the
        // padded content still folds to the same tag.
        val grown = original + "\n".repeat(64).encodeToByteArray()
        Files.write(libRoot.resolve(segment.relPath), grown)
        clock.advance()
        coordinator.reconcileMetadata()
        fs.journalReads().contains(segment.relPath).shouldBeTrue()
        db.tagDao().tagsForDocument(docId) shouldContainExactly listOf("invoice")

        // Now shrink it below the recorded size -> the existing shrink guard must still latch.
        Files.write(libRoot.resolve(segment.relPath), "x".encodeToByteArray())
        clock.advance()
        assertFailsWith<MetadataWriteBlocked> { coordinator.reconcileMetadata() }
        db.metadataProjectionDao().state()!!.migrationState shouldBe MetadataAccumulator.MIGRATION_FAILED
    }

    @Test
    fun incrementalReconcileMatchesFullRefreshProjection() = runTest {
        writePdf("a.pdf", "alpha")
        writePdf("b.pdf", "bravo")
        rescan()
        val a = db.documentDao().getByPath("a.pdf")!!.docId
        val b = db.documentDao().getByPath("b.pdf")!!.docId

        val writer = writer("dev-main")
        val coordinator = coordinator(writer)
        coordinator.refreshState()

        // Commit some metadata locally (recorded directly, no reconcile read needed).
        clock.advance()
        coordinator.commitAndApply(
            listOf(
                PendingOp.tagAdd(a, "receipt"),
                PendingOp.propSet(a, DocProperty.CORRESPONDENT, "acme"),
            ),
        )

        // A SECOND device writes a segment that reconcile will discover as a brand-new source.
        val remote = writer("device-two")
        clock.advance()
        remote.commit(
            listOf(
                PendingOp.tagAdd(b, "urgent"),
                PendingOp.propSet(b, DocProperty.REVIEWED, "true"),
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

    @Test
    fun removedSourceTriggersFullReprojectionAndValidation() = runTest {
        writePdf("a.pdf", "alpha")
        rescan()
        val docId = db.documentDao().getByPath("a.pdf")!!.docId

        val writer = writer("dev-main")
        val coordinator = coordinator(writer)
        coordinator.refreshState()
        clock.advance()
        coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "invoice")))
        coordinator.reconcileMetadata()

        val segment = fs.listAllUnder(NpnPaths.JOURNAL_DIR)
            .single { NpnPaths.journalPath(it.relPath) is NpnPaths.JournalPath.Legacy }

        // Deleting a previously-applied source with no validated replacement must latch the
        // write-block via validatePublishedObjects (unchanged behaviour under the new path).
        Files.delete(libRoot.resolve(segment.relPath))
        clock.advance()
        assertFailsWith<MetadataWriteBlocked> { coordinator.reconcileMetadata() }
        db.metadataProjectionDao().state()!!.migrationState shouldBe MetadataAccumulator.MIGRATION_FAILED
    }
}
