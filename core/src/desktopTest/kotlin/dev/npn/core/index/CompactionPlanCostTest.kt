package dev.npn.core.index

import dev.npn.core.app.JournalCoordinator
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.ProjectionStatus
import dev.npn.core.db.SourceStatus
import dev.npn.core.db.StatementLog
import dev.npn.core.db.isFullTableScan
import dev.npn.core.db.queryPlan
import dev.npn.core.db.recordingDatabase
import dev.npn.core.fs.JvmLibraryFileSystem
import dev.npn.core.fs.PathExternalIo
import dev.npn.core.journal.ContentAddressedJournalWriter
import dev.npn.core.journal.HlcWatermarkAllocator
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.SnapshotCompactor
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The compaction tick runs every 15 minutes. Deciding whether to compact reads the source table
 * only; events are loaded, with one join, just for the objects a plan chose.
 */
class CompactionPlanCostTest {
    private val log = StatementLog()
    private lateinit var libRoot: Path
    private lateinit var dbDir: File
    private lateinit var fs: WatchedFileSystem
    private lateinit var db: NpnDatabase
    private val clock = MutableClock(1_000L)
    private lateinit var indexer: Indexer
    private lateinit var accumulator: MetadataAccumulator

    // Far enough ahead that every file written now is older than the stability window.
    private val farFuture = 4_000_000_000_000L

    @BeforeTest
    fun setup() {
        libRoot = Files.createTempDirectory("npn-compact-cost-lib")
        dbDir = Files.createTempDirectory("npn-compact-cost-db").toFile()
        fs = WatchedFileSystem(JvmLibraryFileSystem(libRoot, PathExternalIo))
        db = recordingDatabase(File(dbDir, "index.db"), log)
        fs.db = db
        indexer = Indexer(fs, db, clock)
        accumulator = MetadataAccumulator(fs, db, installationId = "test-installation")
    }

    @AfterTest
    fun teardown() {
        db.close()
        libRoot.toFile().deleteRecursively()
        dbDir.deleteRecursively()
    }

    private fun coordinator(threshold: Int): JournalCoordinator =
        JournalCoordinator(
            indexer = indexer,
            accumulator = accumulator,
            writer =
                ContentAddressedJournalWriter(
                    fs = fs,
                    installationId = "test-installation",
                    allocator = HlcWatermarkAllocator(clock),
                ),
            compactor = SnapshotCompactor(fs, "test-installation"),
            snapshots = SnapshotSupport(fs, db.metadataProjectionDao()),
            compactionThreshold = threshold,
            compactionStabilityWindowMs = 1_000L,
        )

    /** A library with one document and [objects] published transactions, reconciled and idle. */
    private suspend fun libraryWithObjects(
        objects: Int,
        threshold: Int,
    ): JournalCoordinator {
        Files.write(libRoot.resolve("a.pdf"), "alpha".encodeToByteArray())
        val coordinator = coordinator(threshold)
        coordinator.rescan()
        val docId = checkNotNull(db.documentDao().rowAtPath("a.pdf")).docId
        repeat(objects) { i ->
            clock.advance()
            coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "tag-$i")))
        }
        coordinator.reconcileMetadata()
        fs.clear()
        log.clear()
        return coordinator
    }

    @Test
    fun aTickBelowTheThresholdReadsNoEventAndNoFile() =
        runTest {
            val coordinator = libraryWithObjects(objects = 10, threshold = 64)

            coordinator.compact(nowMillis = farFuture).shouldBeNull()

            log.statements.filter { "metadata_events" in it || "metadata_source_events" in it }.shouldBeEmpty()
            fs.calls("stat").shouldBeEmpty()
            fs.calls("read").shouldBeEmpty()
        }

    @Test
    fun aCompactionLoadsTheEventsOfItsChoiceInOneJoin() =
        runTest {
            val coordinator = libraryWithObjects(objects = 6, threshold = 3)

            coordinator.compact(nowMillis = farFuture).shouldNotBeNull()

            // Recording the snapshot checks each of its six events once; nothing looks them up
            // one by one before that.
            log.statements.count { it.startsWith(EVENT_BY_IDENTITY) } shouldBe 6
            log.statements.count { it.startsWith(SOURCE_EVENTS_BY_PATH) } shouldBe 1
            val joins = log.statements.filter { it.startsWith(EVENTS_OF_SOURCES) }
            joins.size shouldBe 1
            db.queryPlan(joins.single()).filter { isFullTableScan(it, "metadata_events") }.shouldBeEmpty()
            fs
                .calls("stat")
                .count { JournalPaths.classify(it.substringAfter(' ')) is JournalPath.ObjectV2 } shouldBe 6
            db.metadataProjectionDao().state()?.migrationState shouldBe ProjectionStatus.READY
            db
                .metadataProjectionDao()
                .allSources()
                .count { it.status == SourceStatus.APPLIED } shouldBe 7
        }

    private companion object {
        const val EVENT_BY_IDENTITY = "SELECT * FROM metadata_events WHERE deviceId = ? AND sequence = ?"
        const val SOURCE_EVENTS_BY_PATH = "SELECT * FROM metadata_source_events WHERE sourcePath = ?"
        const val EVENTS_OF_SOURCES = "SELECT se.sourcePath"
    }
}
