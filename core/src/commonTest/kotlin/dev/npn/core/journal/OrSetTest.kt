package dev.npn.core.journal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrSetTest {

    private val doc = "a".repeat(64)

    private fun add(dev: String, seq: Long, tag: String, t: Long) =
        JournalEvent(1, seq, Hlc(t, 0, dev), dev, doc, JournalOp.TagAdd(tag))

    private fun remove(dev: String, seq: Long, tag: String, t: Long, dots: List<Dot>) =
        JournalEvent(1, seq, Hlc(t, 0, dev), dev, doc, JournalOp.TagRemove(tag, dots))

    @Test
    fun add_then_remove_observed_dot() {
        val a = add("d1", 1, "x", 10)
        val r = remove("d1", 2, "x", 20, listOf(a.dot))
        val state = JournalFolder.fold(listOf(a, r))
        assertTrue(state.effectiveTags(doc).isEmpty())
    }

    @Test
    fun remove_does_not_kill_concurrent_add_on_other_device() {
        val a1 = add("d1", 1, "x", 10)
        // d2 concurrently adds x; d1's remove only observed a1's dot
        val a2 = add("d2", 1, "x", 11)
        val r = remove("d1", 2, "x", 20, listOf(a1.dot))
        val state = JournalFolder.fold(listOf(a1, a2, r))
        assertEquals(setOf("x"), state.effectiveTags(doc), "a2 survives, tag still present")
    }

    @Test
    fun readd_after_remove_creates_new_live_dot() {
        val a1 = add("d1", 1, "x", 10)
        val r = remove("d1", 2, "x", 20, listOf(a1.dot))
        val a2 = add("d1", 3, "x", 30) // re-add, new dot
        val state = JournalFolder.fold(listOf(a1, r, a2))
        assertEquals(setOf("x"), state.effectiveTags(doc))
    }

    @Test
    fun removed_dot_arriving_after_remove_stays_dead() {
        val a1 = add("d1", 1, "x", 10)
        val r = remove("d1", 2, "x", 20, listOf(a1.dot))
        // fold with remove BEFORE the add: tombstone must keep it dead regardless of order
        val state = JournalFolder.fold(listOf(r, a1))
        assertTrue(state.effectiveTags(doc).isEmpty(), "late add whose dot was removed stays dead")
    }

    @Test
    fun order_independence() {
        val a1 = add("d1", 1, "x", 10)
        val a2 = add("d2", 1, "x", 11)
        val r = remove("d1", 2, "x", 20, listOf(a1.dot))
        val forward = JournalFolder.fold(listOf(a1, a2, r)).effectiveTags(doc)
        val backward = JournalFolder.fold(listOf(r, a2, a1)).effectiveTags(doc)
        assertEquals(forward, backward)
    }
}
