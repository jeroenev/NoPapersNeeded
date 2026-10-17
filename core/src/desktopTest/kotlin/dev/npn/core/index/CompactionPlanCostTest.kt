package dev.npn.core.index

import dev.npn.core.db.ProjectionStatus
import dev.npn.core.db.SourceStatus
import dev.npn.core.db.StatementLog
import dev.npn.core.db.isFullTableScan
import dev.npn.core.db.queryPlan
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths
import dev.npn.core.journal.PendingOp
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The compaction tick runs every 15 minutes. Deciding whether to compact reads the source table
 * only; events are loaded, with one join, just for the objects a plan chose.
 */
class CompactionPlanCostTest {
    private val log = StatementLog()
    private val rig = IndexRig.create(statements = log, files = ::WatchedFileSystem)
    private val fs = (rig.fs as WatchedFileSystem).also { it.db = rig.db }
    private val db = rig.db
    private val clock = rig.clock

    // Far enough ahead that every file written now is older than the stability window.
    private val farFuture = 4_000_000_000_000L

    @AfterTest
    fun teardown() = rig.close()

    private fun coordinator(threshold: Int): JournalCoordinator =
        rig.coordinator(compaction = rig.compaction(threshold))

    /** A library with one document and [objects] published transactions, reconciled and idle. */
    private suspend fun libraryWithObjects(
        objects: Int,
        threshold: Int,
    ): JournalCoordinator {
        rig.writePdf("a.pdf", "alpha")
        val coordinator = coordinator(threshold)
        coordinator.rescan()
        val docId = db.documentDao().requireRowAtPath("a.pdf").docId
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
