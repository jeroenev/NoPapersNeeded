// checkAll's per-test PropTestConfig overload (iteration counts here are tuned per property) is
// still marked experimental by kotest.
@file:OptIn(ExperimentalKotest::class)

package dev.npn.core.journal

import dev.npn.core.fs.ExternalSource
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.FsEvent
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.VerifiedPublicationCapability
import dev.npn.core.fs.sha256Bytes
import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** Minimal FS stub for compactor unit tests that only exercise pure planning/survival logic. */
private object NoPublishFs : LibraryFileSystem {
    override val verifiedPublicationCapability = VerifiedPublicationCapability.ATOMIC_CREATE_NO_REPLACE

    override suspend fun listAll(): List<FileMeta> = emptyList()

    override suspend fun listAllUnder(relPath: String): List<FileMeta> = emptyList()

    override suspend fun exists(relPath: String): Boolean = false

    override suspend fun stat(relPath: String): FileMeta? = null

    override suspend fun readBytes(relPath: String): ByteArray = ByteArray(0)

    override suspend fun writeBytesOnce(
        relPath: String,
        bytes: ByteArray,
    ) = Unit

    override suspend fun publishVerifiedObjectOnce(
        stagingPath: String,
        canonicalPath: String,
        expectedHash: String,
    ) = Unit

    override suspend fun move(
        fromRelPath: String,
        toRelPath: String,
    ) = Unit

    override suspend fun delete(relPath: String) = Unit

    override suspend fun mkdirs(relPath: String) = Unit

    override suspend fun sha256(relPath: String): String = ""

    override suspend fun importExternal(
        source: ExternalSource,
        toRelPath: String,
    ) = Unit

    override fun watch(): Flow<FsEvent> = emptyFlow()
}

/**
 * Compaction equivalence: a random multi-device history, split into per-transaction objects, then
 * compacted into one snapshot, must fold to exactly the same observable state as the originals —
 * both against the objects it superseded AND against any FUTURE objects unknown at compaction time.
 */
class SnapshotCompactionPropertyTest {
    private val iterations = 300

    private fun universe(events: List<JournalEvent>): Pair<Set<String>, Set<String>> {
        val docs = HashSet<String>()
        val tags = HashSet<String>()
        for (e in events) {
            e.doc?.let { docs.add(it) }
            when (val op = e.op) {
                is JournalOp.Relink -> {
                    docs.add(op.newDoc)
                }

                is JournalOp.TagAdd -> {
                    tags.add(op.tag)
                }

                is JournalOp.TagRemove -> {
                    tags.add(op.tag)
                }

                is JournalOp.TagRename -> {
                    tags.add(op.from)
                    tags.add(op.to)
                }

                is JournalOp.TagMerge -> {
                    tags.add(op.from)
                    tags.add(op.to)
                }

                is JournalOp.TagStyle -> {
                    tags.add(op.tag)
                }

                else -> {}
            }
        }
        return docs to tags
    }

    /** Group events into per-transaction "objects" (1..3 events each) as candidates. */
    private fun asCandidates(
        events: List<JournalEvent>,
        rng: Random,
    ): List<CompactionCandidate> {
        val candidates = ArrayList<CompactionCandidate>()
        var i = 0
        while (i < events.size) {
            val size = 1 + rng.nextInt(3)
            val slice = events.subList(i, minOf(i + size, events.size)).toList()
            val bytes = JournalCodec.encodeLines(slice).encodeToByteArray()
            candidates.add(
                CompactionCandidate(
                    relPath = "objects-v2-xx/${sha256Bytes(bytes)}.jsonl",
                    contentHash = sha256Bytes(bytes),
                    events = slice,
                    mtimeMillis = 1L,
                ),
            )
            i += size
        }
        return candidates
    }

    @Test
    fun compaction_preserves_fold_including_future_objects() =
        propTest {
            val compactor = SnapshotCompactor(NoPublishFs, "test-install")
            checkAll(PropTestConfig(iterations = iterations), Arb.long()) { seed ->
                val rng = Random(seed)
                val devices = 2 + rng.nextInt(3)
                val count = 30 + rng.nextInt(70)
                val all = EventHistoryGen(seed, devices, count).generate()

                val splitPoint = all.size / 2
                val toCompact = all.subList(0, splitPoint).toList()
                val future = all.subList(splitPoint, all.size).toList()

                val candidates = asCandidates(toCompact, rng)
                val surviving = compactor.survivingEvents(candidates)

                // The snapshot's surviving events must be exactly the same multiset (by dot) as inputs.
                assertEquals(
                    toCompact.map { it.dot }.toSet(),
                    surviving.map { it.dot }.toSet(),
                    "surviving dots must equal the compacted objects' dots",
                )

                // Known-others: fold(snapshot) == fold(originals).
                run {
                    val expected = JournalFolder.fold(toCompact)
                    val actual = JournalFolder.fold(surviving)
                    val (d, t) = universe(toCompact)
                    FoldedStateEquivalence.assertEquivalent(expected, actual, d, t)
                }

                // Future-objects: snapshot fixed BEFORE `future` existed; equivalence still holds.
                run {
                    val expected = JournalFolder.fold(all)
                    val actual = JournalFolder.fold(surviving + future)
                    val (d, t) = universe(all)
                    FoldedStateEquivalence.assertEquivalent(expected, actual, d, t)
                }
            }
        }

    @Test
    fun two_concurrent_snapshots_of_overlapping_sets_converge() =
        propTest {
            val compactor = SnapshotCompactor(NoPublishFs, "test-install")
            checkAll(PropTestConfig(iterations = 200), Arb.long()) { seed ->
                val rng = Random(seed)
                val devices = 2 + rng.nextInt(3)
                val count = 30 + rng.nextInt(50)
                val all = EventHistoryGen(seed, devices, count).generate()
                val candidates = asCandidates(all, rng)

                // Two devices compact OVERLAPPING subsets whose union is the whole set (each snapshot
                // covers a majority; their intersection is non-empty). Convergence of the two snapshots
                // must reproduce the full history.
                val setA = candidates.filterIndexed { idx, _ -> idx % 3 != 0 || idx % 2 == 0 }
                val setB = candidates.filterIndexed { idx, _ -> idx % 3 == 0 || idx % 2 == 1 }
                val snapA = compactor.survivingEvents(setA)
                val snapB = compactor.survivingEvents(setB)

                // Ingesting both snapshots (event dedupe by dot) equals folding all originals.
                val merged = (snapA + snapB).distinctBy { it.dot }
                val expected = JournalFolder.fold(all)
                val actual = JournalFolder.fold(merged)
                val (d, t) = universe(all)
                FoldedStateEquivalence.assertEquivalent(expected, actual, d, t)
            }
        }
}
