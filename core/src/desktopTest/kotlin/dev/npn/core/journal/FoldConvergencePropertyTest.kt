package dev.npn.core.journal

import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlin.random.Random
import kotlin.test.Test

class FoldConvergencePropertyTest {

    private val iterations = 300

    private fun docsAndTagsOf(events: List<JournalEvent>): Pair<Set<String>, Set<String>> {
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

    @Test
    fun fold_is_order_independent_under_shuffle() = propTest {
        checkAll(PropTestConfig(iterations = iterations), Arb.long()) { seed ->
            val rng = Random(seed)
            val devices = 2 + rng.nextInt(3) // 2..4
            val count = 20 + rng.nextInt(60)
            val events = EventHistoryGen(seed, devices, count).generate()
            val (docs, tags) = docsAndTagsOf(events)

            val base = JournalFolder.fold(events)
            repeat(3) {
                val shuffled = events.shuffled(rng)
                val other = JournalFolder.fold(shuffled)
                FoldedStateEquivalence.assertEquivalent(base, other, docs, tags)
            }
        }
    }

    @Test
    fun fold_is_independent_of_segment_partitioning() = propTest {
        checkAll(PropTestConfig(iterations = iterations), Arb.long()) { seed ->
            val rng = Random(seed)
            val devices = 2 + rng.nextInt(3)
            val count = 20 + rng.nextInt(60)
            val events = EventHistoryGen(seed, devices, count).generate()
            val (docs, tags) = docsAndTagsOf(events)

            // partition into arbitrary segment chunks, folded in arbitrary chunk order
            val shuffled = events.shuffled(rng)
            val chunks = ArrayList<List<JournalEvent>>()
            var i = 0
            while (i < shuffled.size) {
                val size = 1 + rng.nextInt(7)
                chunks.add(shuffled.subList(i, minOf(i + size, shuffled.size)))
                i += size
            }
            chunks.shuffle(rng)

            val state = FoldedState()
            chunks.flatten().forEach { state.apply(it) }

            val base = JournalFolder.fold(events)
            FoldedStateEquivalence.assertEquivalent(base, state, docs, tags)
        }
    }
}
