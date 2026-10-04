package dev.npn.core.index

import dev.npn.core.db.SourceFingerprint
import dev.npn.core.db.StatementLog
import dev.npn.core.db.isFullTableScan
import dev.npn.core.db.queryPlan
import dev.npn.core.journal.PendingOp
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The event table holds every metadata edit ever made. Recording one more commit, or ingesting
 * another device's, must cost the same however long that history is.
 */
class MetadataIngestCostTest {
    private val log = StatementLog()
    private lateinit var rig: IndexRig

    @BeforeTest
    fun setup() {
        rig = IndexRig.create(log)
    }

    @AfterTest
    fun teardown() {
        rig.close()
    }

    @Test
    fun commitsAndIngestsTouchOnlyTheirOwnEvents() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            rig.rescan()
            val docId = checkNotNull(rig.db.documentDao().rowAtPath("a.pdf")).docId
            val coordinator = rig.coordinator(rig.writer("dev-main"))
            coordinator.refreshState()
            repeat(HISTORY) {
                rig.clock.advance()
                coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "tag-$it")))
            }

            log.clear()
            rig.clock.advance()
            coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "local")))
            rig.clock.advance()
            rig.writer("device-two").commit(
                listOf(
                    PendingOp.tagAdd(docId, "remote-1"),
                    PendingOp.tagAdd(docId, "remote-2"),
                    PendingOp.tagAdd(docId, "remote-3"),
                ),
            )
            coordinator.reconcileMetadata()

            rig.db
                .tagDao()
                .tagsForDocument(docId)
                .filter { it.startsWith("remote") } shouldContainExactly
                listOf("remote-1", "remote-2", "remote-3")
            val eventStatements = log.statements.filter { "metadata_events" in it }.distinct()
            eventStatements.shouldNotBeEmpty()
            eventStatements
                .filter { sql ->
                    rig.db.queryPlan(sql).any { isFullTableScan(it, "metadata_events") }
                }.shouldBeEmpty()
            // One identity check per event recorded (one local, three remote), and no second lookup
            // to learn which of them were new.
            log.statements.count { it.startsWith(EVENT_BY_IDENTITY) } shouldBe 4
        }

    @Test
    fun aSourceReadAgainDropsOnlyTheEventsItNoLongerHolds() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            rig.rescan()
            val docId = checkNotNull(rig.db.documentDao().rowAtPath("a.pdf")).docId
            val dao = rig.db.metadataProjectionDao()
            val writer = rig.writer("device-two")
            rig.clock.advance()
            writer.commit(listOf(PendingOp.tagAdd(docId, "kept"), PendingOp.tagAdd(docId, "dropped")))
            rig.accumulator.reconcile()
            val events = dao.allEvents()
            events.size shouldBe 2

            // The same source, recorded again with one event fewer.
            val source = dao.allSources().single()
            dao.ingest(source.copy(fingerprint = SourceFingerprint("rewritten")), events.take(1))

            dao.allEvents() shouldContainExactly events.take(1)
        }

    private companion object {
        const val HISTORY = 30
        const val EVENT_BY_IDENTITY = "SELECT * FROM metadata_events WHERE deviceId = ? AND sequence = ?"
    }
}
