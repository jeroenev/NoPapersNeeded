package dev.npn.core.journal

import dev.npn.core.model.DocProperty
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SegmentWriterTest {
    private val doc = "a".repeat(64)

    private fun writer(
        store: SegmentStore,
        state: LocalJournalState,
        device: String = "dev1",
    ): SegmentWriter {
        var now = 1000L
        val clock = HlcClock(device, { now++ })
        return SegmentWriter(store, device, clock, state)
    }

    @Test
    fun write_once_naming_and_seq_continuity() =
        runTest {
            val store = InMemorySegmentStore()
            val state = InMemoryLocalJournalState()
            val w = writer(store, state)

            val batch1 =
                w.commit(
                    listOf(
                        PendingOp.tagAdd(doc, "a"),
                        PendingOp.tagAdd(doc, "b"),
                    ),
                )
            assertEquals(listOf(1L, 2L), batch1.map { it.seq })

            val batch2 = w.commit(listOf(PendingOp.propSet(doc, DocProperty.REVIEWED, "true")))
            assertEquals(listOf(3L), batch2.map { it.seq })

            val segs = store.listSegments()
            assertEquals(2, segs.size)
            assertTrue(segs.any { it.fileName == SegmentRef.plainName(1) })
            assertTrue(segs.any { it.fileName == SegmentRef.plainName(3) })
        }

    @Test
    fun write_once_fails_on_duplicate_name() =
        runTest {
            val store = InMemorySegmentStore()
            store.writeSegment("dev1", SegmentRef.plainName(1), "x")
            assertFailsWith<IllegalArgumentException> {
                store.writeSegment("dev1", SegmentRef.plainName(1), "y")
            }
        }

    @Test
    fun recover_last_seq_from_store_when_local_state_empty() =
        runTest {
            val store = InMemorySegmentStore()
            // Seed store with an existing segment written by dev1 with seqs 1..3
            val seeded =
                writer(store, InMemoryLocalJournalState()).commit(
                    listOf(
                        PendingOp.tagAdd(doc, "a"),
                        PendingOp.tagAdd(doc, "b"),
                        PendingOp.tagAdd(doc, "c"),
                    ),
                )
            assertEquals(3L, seeded.last().seq)

            // Fresh local state -> must recover lastSeq = 3
            val freshState = InMemoryLocalJournalState()
            val w2 = writer(store, freshState)
            assertEquals(3L, w2.recover())
            val next = w2.commit(listOf(PendingOp.tagAdd(doc, "d")))
            assertEquals(4L, next.single().seq)
        }

    @Test
    fun recover_advances_stale_non_null_local_sequence() =
        runTest {
            val store = InMemorySegmentStore()
            writer(store, InMemoryLocalJournalState()).commit(
                listOf(PendingOp.tagAdd(doc, "a"), PendingOp.tagAdd(doc, "b")),
            )
            val stale = InMemoryLocalJournalState().also { it.setLastSeq(1L) }
            val recovered = writer(store, stale)
            assertEquals(2L, recovered.recover())
            assertEquals(3L, recovered.commit(listOf(PendingOp.tagAdd(doc, "c"))).single().seq)
        }

    private class CountingStore(
        private val delegate: SegmentStore,
    ) : SegmentStore by delegate {
        var listings = 0

        override suspend fun listSegments(): List<SegmentRef> {
            listings++
            return delegate.listSegments()
        }
    }

    @Test
    fun recovery_scans_only_once_per_writer_instance() =
        runTest {
            val store = CountingStore(InMemorySegmentStore())
            val instance = writer(store, InMemoryLocalJournalState())
            instance.commit(listOf(PendingOp.tagAdd(doc, "a")))
            val afterFirst = store.listings
            instance.commit(listOf(PendingOp.tagAdd(doc, "b")))
            assertEquals(afterFirst, store.listings)
        }

    /**
     * The boot sequence (DeviceIdentity.ensureSingleWriter) calls both of these back to back, and
     * on Android a listing walks the journal directory over SAF. They must share one listing.
     */
    @Test
    fun recover_and_fork_check_share_one_listing() =
        runTest {
            val store = CountingStore(InMemorySegmentStore())
            writer(store, InMemoryLocalJournalState()).commit(listOf(PendingOp.tagAdd(doc, "a")))
            store.listings = 0

            val booting = writer(store, InMemoryLocalJournalState())
            booting.recover()
            booting.checkSingleWriter()

            assertEquals(1, store.listings)
        }

    /** A commit adds an own segment, so the cached listing must not survive it. */
    @Test
    fun fork_check_after_commit_sees_the_new_segment() =
        runTest {
            val store = CountingStore(InMemorySegmentStore())
            val state = InMemoryLocalJournalState()
            val instance = writer(store, state)
            instance.recover()
            instance.checkSingleWriter()

            instance.commit(listOf(PendingOp.tagAdd(doc, "a")))
            // The commit recorded its own segment locally, so the fork check must still pass — but it
            // has to re-list to see it rather than reusing the pre-commit (empty) listing.
            assertTrue(instance.checkSingleWriter() is ForkCheck.Ok)

            // Same listing, minus the local bookkeeping, is a fork.
            state.setKnownOwnSegments(emptySet())
            assertTrue(instance.checkSingleWriter() is ForkCheck.ForkNeeded)
        }

    @Test
    fun fork_detection_when_store_has_unknown_own_segment() =
        runTest {
            val store = InMemorySegmentStore()
            // dev1 wrote a segment (simulating another install with same id) but local state is empty
            writer(store, InMemoryLocalJournalState()).commit(listOf(PendingOp.tagAdd(doc, "a")))

            val localState = InMemoryLocalJournalState()
            val w = writer(store, localState)
            // local state knows nothing about the segment in the store
            val check = w.checkSingleWriter()
            assertTrue(check is ForkCheck.ForkNeeded)
            assertEquals(listOf(SegmentRef.plainName(1)), check.unknownSegments)
        }

    @Test
    fun fork_check_ok_when_local_knows_its_segments() =
        runTest {
            val store = InMemorySegmentStore()
            val state = InMemoryLocalJournalState()
            val w = writer(store, state)
            w.commit(listOf(PendingOp.tagAdd(doc, "a")))
            assertEquals(ForkCheck.Ok, w.checkSingleWriter())
        }

    @Test
    fun empty_commit_writes_nothing() =
        runTest {
            val store = InMemorySegmentStore()
            val w = writer(store, InMemoryLocalJournalState())
            assertTrue(w.commit(emptyList()).isEmpty())
            assertTrue(store.listSegments().isEmpty())
        }

    @Test
    fun observe_hlc_orders_later_commits_after_remote() =
        runTest {
            val store = InMemorySegmentStore()
            // Local wall clock (~1000) is far behind the remote HLC already in the store.
            val w = writer(store, InMemoryLocalJournalState())
            val remote = Hlc(50_000L, 3, "other")
            w.observeHlc(remote)
            val event = w.commit(listOf(PendingOp.tagAdd(doc, "a"))).single()
            assertTrue(event.hlc > remote, "commit after observeHlc must order after the remote HLC")
        }

    @Test
    fun observe_hlc_ignores_remote_behind_local() =
        runTest {
            val store = InMemorySegmentStore()
            val w = writer(store, InMemoryLocalJournalState())
            val first = w.commit(listOf(PendingOp.tagAdd(doc, "a"))).single()
            w.observeHlc(Hlc(1L, 0, "other"))
            val second = w.commit(listOf(PendingOp.tagAdd(doc, "b"))).single()
            assertTrue(second.hlc > first.hlc)
            assertTrue(second.hlc.physicalMillis < 50_000L, "stale remote must not inflate the clock")
        }
}
