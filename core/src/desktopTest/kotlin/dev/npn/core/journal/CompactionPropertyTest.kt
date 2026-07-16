package dev.npn.core.journal

import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlin.random.Random
import kotlin.test.Test

class CompactionPropertyTest {

    private val iterations = 300

    private fun universe(events: List<JournalEvent>): Pair<Set<String>, Set<String>> {
        val docs = HashSet<String>()
        val tags = HashSet<String>()
        for (e in events) {
            e.doc?.let { docs.add(it) }
            when (val op = e.op) {
                is JournalOp.Relink -> docs.add(op.newDoc)
                is JournalOp.TagAdd -> tags.add(op.tag)
                is JournalOp.TagRemove -> tags.add(op.tag)
                is JournalOp.TagRename -> { tags.add(op.from); tags.add(op.to) }
                is JournalOp.TagMerge -> { tags.add(op.from); tags.add(op.to) }
                is JournalOp.TagStyle -> tags.add(op.tag)
                else -> {}
            }
        }
        return docs to tags
    }

    /**
     * Build a full history, split into "own device" events (dev0) and "others". Verify:
     *   fold(compact(own, others) + others) == fold(own + others)
     * and the stronger guarantee: extend others with MORE events unknown at compaction time and
     * the equivalence still holds against the ORIGINAL compaction output.
     */
    @Test
    fun compaction_preserves_fold_including_future_others() = propTest {
        checkAll(PropTestConfig(iterations = iterations), Arb.long()) { seed ->
            val rng = Random(seed)
            val devices = 2 + rng.nextInt(3) // dev0 is "own"
            val count = 30 + rng.nextInt(70)
            val all = EventHistoryGen(seed, devices, count).generate()

            val own = all.filter { it.dev == "dev0" }
            val othersAtCompaction = all.filter { it.dev != "dev0" }

            // Simulate future others: generate extra events (from a fresh non-dev0 device set)
            // that were unknown when we compacted. Give them a distinct device id and seqs.
            val future = generateFutureOthers(seed xor 0x5DEECE66DL, rng.nextInt(30))

            val compactedOwn = Compactor().compact(own, othersAtCompaction)

            // known-others case
            run {
                val expected = JournalFolder.fold(own + othersAtCompaction)
                val actual = JournalFolder.fold(compactedOwn + othersAtCompaction)
                val (d, t) = universe(own + othersAtCompaction)
                FoldedStateEquivalence.assertEquivalent(expected, actual, d, t)
            }

            // future-others case: compaction output was fixed BEFORE `future` existed
            run {
                val extendedOthers = othersAtCompaction + future
                val expected = JournalFolder.fold(own + extendedOthers)
                val actual = JournalFolder.fold(compactedOwn + extendedOthers)
                val (d, t) = universe(own + extendedOthers)
                FoldedStateEquivalence.assertEquivalent(expected, actual, d, t)
            }
        }
    }

    private fun generateFutureOthers(seed: Long, n: Int): List<JournalEvent> {
        if (n == 0) return emptyList()
        val rng = Random(seed)
        val clock = HlcClock("devFuture", EpochClock { 1_800_000_000_000L + rng.nextInt(0, 1000) })
        val docs = (0 until 3).map { d -> d.toString().padStart(64, ('a' + d)) }
        val tags = listOf("invoice", "tax", "receipt", "medical", "contract")
        val out = ArrayList<JournalEvent>()
        var seq = 0L
        repeat(n) {
            seq += 1
            val hlc = clock.next()
            val doc = docs[rng.nextInt(docs.size)]
            val op: JournalOp = when (rng.nextInt(6)) {
                0 -> JournalOp.TagAdd(tags[rng.nextInt(tags.size)])
                1 -> JournalOp.PropSet(
                    dev.npn.core.model.DocProperty.REVIEWED,
                    if (rng.nextBoolean()) null else "s${rng.nextInt(100)}",
                )
                2 -> JournalOp.DocTrash("/f${rng.nextInt(50)}.pdf")
                3 -> JournalOp.DocDelete
                4 -> return@repeat out.add(
                    JournalEvent(1, seq, hlc, "devFuture", null,
                        JournalOp.TagRename(tags[rng.nextInt(tags.size)], tags[rng.nextInt(tags.size)]))
                ).let {}
                else -> JournalOp.Relink(docs[rng.nextInt(docs.size)])
            }
            out.add(JournalEvent(1, seq, hlc, "devFuture", doc, op))
        }
        return out
    }

    @Test
    fun compaction_reduces_or_keeps_event_count() = propTest {
        checkAll(PropTestConfig(iterations = 200), Arb.long()) { seed ->
            val rng = Random(seed)
            val all = EventHistoryGen(seed, 2 + rng.nextInt(3), 40).generate()
            val own = all.filter { it.dev == "dev0" }
            val others = all.filter { it.dev != "dev0" }
            val compacted = Compactor().compact(own, others)
            require(compacted.size <= own.size) {
                "compaction must not grow the own event set: ${own.size} -> ${compacted.size}"
            }
            // seqs and hlcs preserved (subset of originals by identity)
            val ownByKey = own.associateBy { it.seq }
            for (e in compacted) {
                val orig = ownByKey[e.seq]
                require(orig != null && orig.hlc == e.hlc && orig.op == e.op) {
                    "compaction rewrote event seq=${e.seq}"
                }
            }
        }
    }
}
