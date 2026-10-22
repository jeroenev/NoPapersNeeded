package dev.npn.core.index

import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import dev.npn.core.journal.FoldedState
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.journalObjects
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** What a reconcile pass of [MetadataAccumulator] reads again, and what it skips. */
class MetadataAccumulatorTest {
    private val rig = IndexRig.create()
    private val dao = rig.db.metadataProjectionDao()

    @AfterTest
    fun teardown() = rig.close()

    /**
     * A stored projection from a build whose fold differed ([MetadataAccumulator.PROJECTION_VERSION])
     * is wiped at the next pass: every source is read again and the state rebuilt from it, and the
     * open does not reuse that projection. One of this build's version is reused, and nothing read.
     */
    @Test
    fun aStoredProjectionOfAnOlderVersionIsRebuiltAtTheNextPass() =
        runTest {
            val (doc, state) = oneTaggedDocument()
            val unchanged = rig.accumulator.reconcile(state)
            unchanged.sourcesRead shouldBe 0
            // Nothing to project: no bounded projection either.
            unchanged.incremental.shouldBeFalse()
            rig.accumulator.markReady(state.maxHlc)
            rig.accumulator.reconcileOnOpen().shouldBeInstanceOf<OpenReconciliation.ProjectionCurrent>()

            olderProjectionVersion()
            val rebuilt = rig.accumulator.reconcile(state)

            rebuilt.sourcesRead shouldBe 1
            rebuilt.incremental.shouldBeFalse()
            rebuilt.state
                .shouldNotBeNull()
                .effectiveTags(doc) shouldContainExactly setOf("kept")
            dao.state().shouldNotBeNull().projectionVersion shouldBe MetadataAccumulator.PROJECTION_VERSION

            rig.accumulator.markReady(state.maxHlc)
            olderProjectionVersion()
            rig.accumulator.reconcileOnOpen().shouldBeInstanceOf<OpenReconciliation.Refolded>()
        }

    /**
     * An index from before this projection version can hold a source row of a kind this build no
     * longer knows (a storage-v1 segment's, stored as 1). The version check clears the source rows
     * before any is read, so the pass rebuilds from the journal rather than fail on that row.
     */
    @Test
    fun aSourceRowOfAKindNoLongerKnownIsShedByTheRebuild() =
        runTest {
            val (doc, state) = oneTaggedDocument()
            rig.accumulator.markReady(state.maxHlc)
            olderProjectionVersion()
            rig.db.useWriterConnection { connection ->
                connection.executeSQL(
                    "INSERT INTO metadata_sources (path, storageVersion, fingerprint, contentHash, status) " +
                        "VALUES ('.npn/journal/old-device/0000000000000001.jsonl', 1, '1,1:x', NULL, 'applied')",
                )
            }

            val rebuilt = rig.accumulator.reconcileOnOpen().shouldBeInstanceOf<OpenReconciliation.Refolded>()

            rebuilt.state.effectiveTags(doc) shouldContainExactly setOf("kept")
            dao.allSources().map { it.path } shouldContainExactly rig.fs.journalObjects().map { it.relPath }
        }

    /** A storage-v1 segment, compacted or not, is no source this build reads: it blocks writes, naming it. */
    @Test
    fun aStorageV1SegmentIsJournalDamage() =
        runTest {
            val (_, state) = oneTaggedDocument()
            for (name in listOf("0000000000000001.jsonl", "0000000000000001-2.snap.jsonl")) {
                val segment = ".npn/journal/old-device/$name"
                rig.fs.writeBytesOnce(segment, "{}\n".encodeToByteArray())

                val blocked = assertFailsWith<MetadataWriteBlocked> { rig.accumulator.reconcile(state) }

                blocked.block shouldBe WriteBlock.JOURNAL_DAMAGED
                blocked.message shouldBe "Storage-v1 journal segment is no longer read: $segment"
                rig.fs.delete(segment)
            }
            rig.accumulator.reconcile(state).sourcesRead shouldBe 0
        }

    /**
     * An applied object whose mtime moved with its bytes unchanged (a backup tool, a `touch`): its stat
     * proves nothing, so the pass reads it, and finds the events it holds already there. Nothing is
     * lost or added, and the pass after it reads nothing.
     */
    @Test
    fun aTouchedObjectLosesNothingAndIsNotReadAgain() =
        runTest {
            val (doc, state) = oneTaggedDocument()
            val objectPath = rig.fs.journalObjects().single()
            Files.setLastModifiedTime(
                rig.libRoot.resolve(objectPath.relPath),
                FileTime.fromMillis(objectPath.mtimeMillis + 10_000),
            )

            val touched = rig.accumulator.reconcile(state)

            touched.state
                .shouldNotBeNull()
                .effectiveTags(doc) shouldContainExactly setOf("kept")
            rig.accumulator.reconcile(touched.state).sourcesRead shouldBe 0
        }

    /** An applied object whose bytes changed is not this object any more: the journal is damaged. */
    @Test
    fun anAppliedObjectWhoseBytesChangedIsJournalDamage() =
        runTest {
            val (_, state) = oneTaggedDocument()
            val objectPath = rig.fs.journalObjects().single()
            Files.write(rig.libRoot.resolve(objectPath.relPath), "{}".encodeToByteArray())

            assertFailsWith<MetadataWriteBlocked> { rig.accumulator.reconcile(state) }.block shouldBe
                WriteBlock.JOURNAL_DAMAGED
        }

    /**
     * Index one document, and publish one object tagging it, which a pass then records; returns the
     * document and the state that pass folded.
     */
    private suspend fun oneTaggedDocument(): Pair<String, FoldedState> {
        rig.writePdf("a.pdf", "alpha")
        rig.rescan()
        val doc = rig.fs.sha256("a.pdf")
        rig.clock.advance()
        rig.writer().commit(listOf(PendingOp.tagAdd(doc, "kept")))
        val recorded = rig.accumulator.reconcile(rig.accumulator.loadState())
        recorded.sourcesRead shouldBe 1
        return doc to recorded.state.shouldNotBeNull()
    }

    /** Mark the stored projection as built by a build whose fold had the version before this one's. */
    private suspend fun olderProjectionVersion() {
        val stored = dao.state().shouldNotBeNull()
        dao.upsertState(stored.copy(projectionVersion = MetadataAccumulator.PROJECTION_VERSION - 1))
    }
}
