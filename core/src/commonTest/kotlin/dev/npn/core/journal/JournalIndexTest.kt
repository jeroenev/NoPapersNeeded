package dev.npn.core.journal

import dev.npn.core.model.DocProperty
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class JournalIndexTest {

    private val doc = "a".repeat(64)

    private fun ev(dev: String, seq: Long, op: JournalOp, t: Long, d: String? = doc) =
        JournalEvent(1, seq, Hlc(t, 0, dev), dev, d, op)

    private suspend fun write(store: SegmentStore, dev: String, first: Long, events: List<JournalEvent>) {
        store.writeSegment(dev, SegmentRef.plainName(first), JournalCodec.encodeLines(events))
    }

    @Test
    fun incremental_fold_equals_full_refold() = runTest {
        val store = InMemorySegmentStore()
        write(store, "d1", 1, listOf(ev("d1", 1, JournalOp.TagAdd("x"), 10)))
        val index = JournalIndex(store)

        val first = index.refresh(emptyMap())
        assertEquals(setOf("x"), first.state.effectiveTags(doc))

        // add a new segment for d1
        write(store, "d1", 2, listOf(ev("d1", 2, JournalOp.TagAdd("y"), 20)))
        // also a new device
        write(store, "d2", 1, listOf(ev("d2", 1, JournalOp.PropSet(DocProperty.REVIEWED, "true"), 15)))

        val incremental = index.refresh(first.foldedSegments)
        val full = index.refresh(emptyMap())

        assertEquals(full.state.effectiveTags(doc), incremental.state.effectiveTags(doc))
        assertEquals(setOf("x", "y"), incremental.state.effectiveTags(doc))
        assertEquals("true", incremental.state.effectiveProp(doc, DocProperty.REVIEWED))
        assertEquals(full.foldedSegments, incremental.foldedSegments)
    }

    @Test
    fun fold_tracks_max_hlc_across_devices() = runTest {
        val store = InMemorySegmentStore()
        write(store, "d1", 1, listOf(ev("d1", 1, JournalOp.TagAdd("x"), 10)))
        write(store, "d2", 1, listOf(ev("d2", 1, JournalOp.PropSet(DocProperty.REVIEWED, "true"), 5_000)))
        val folded = JournalIndex(store).refresh(emptyMap())
        assertEquals(Hlc(5_000, 0, "d2"), folded.state.maxHlc)
    }

    @Test
    fun compaction_triggered_refold_of_one_device_dir() = runTest {
        val store = InMemorySegmentStore()
        // d1 writes two segments
        write(store, "d1", 1, listOf(ev("d1", 1, JournalOp.TagAdd("x"), 10)))
        write(store, "d1", 2, listOf(ev("d1", 2, JournalOp.TagAdd("y"), 20)))
        val index = JournalIndex(store)
        val folded = index.refresh(emptyMap())
        assertEquals(setOf("x", "y"), folded.state.effectiveTags(doc))

        // Simulate compaction: replace both segments with a single snapshot.
        store.deleteSegment(SegmentRef("d1", SegmentRef.plainName(1)))
        store.deleteSegment(SegmentRef("d1", SegmentRef.plainName(2)))
        store.writeSegment("d1", SegmentRef.snapshotName(1, 2), JournalCodec.encodeLines(listOf(
            ev("d1", 1, JournalOp.TagAdd("x"), 10),
            ev("d1", 2, JournalOp.TagAdd("y"), 20),
        )))

        // The folded set no longer matches (files removed) -> full refold, same result.
        val refreshed = index.refresh(folded.foldedSegments)
        assertEquals(setOf("x", "y"), refreshed.state.effectiveTags(doc))
        assertEquals(setOf(SegmentRef.snapshotName(1, 2)), refreshed.foldedSegments["d1"])
    }
}
