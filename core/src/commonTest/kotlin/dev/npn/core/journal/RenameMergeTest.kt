package dev.npn.core.journal

import kotlin.test.Test
import kotlin.test.assertEquals

class RenameMergeTest {
    private val doc = "a".repeat(64)

    private fun add(
        seq: Long,
        tag: String,
        t: Long,
        dev: String = "d1",
    ) = event(JournalOp.TagAdd(tag), doc, seq, t, dev)

    private fun rename(
        seq: Long,
        from: String,
        to: String,
        t: Long,
        dev: String = "d1",
    ) = event(JournalOp.TagRename(from, to), null, seq, t, dev)

    private fun merge(
        seq: Long,
        from: String,
        to: String,
        t: Long,
        dev: String = "d1",
    ) = event(JournalOp.TagMerge(from, to), null, seq, t, dev)

    @Test
    fun addBeforeRenameFollowsRename() {
        val a = add(1, "old", 10)
        val r = rename(2, "old", "new", 20)
        assertEquals(setOf("new"), JournalFolder.fold(listOf(a, r)).effectiveTags(doc))
    }

    @Test
    fun addAfterRenameKeepsLiteralName() {
        val r = rename(1, "old", "new", 10)
        val a = add(2, "old", 20) // added strictly after the rename hlc
        assertEquals(setOf("old"), JournalFolder.fold(listOf(r, a)).effectiveTags(doc))
    }

    @Test
    fun renameChainABC() {
        val a = add(1, "a", 10)
        val r1 = rename(2, "a", "b", 20)
        val r2 = rename(3, "b", "c", 30)
        assertEquals(setOf("c"), JournalFolder.fold(listOf(a, r1, r2)).effectiveTags(doc))
    }

    @Test
    fun mergeFoldsIntoExistingTag() {
        val a1 = add(1, "cats", 10)
        val a2 = add(2, "felines", 11)
        val m = merge(3, "felines", "cats", 20)
        assertEquals(setOf("cats"), JournalFolder.fold(listOf(a1, a2, m)).effectiveTags(doc))
    }

    @Test
    fun orderIndependentRename() {
        val a = add(1, "old", 10)
        val r1 = rename(2, "old", "mid", 20)
        val r2 = rename(3, "mid", "new", 30)
        val f = JournalFolder.fold(listOf(a, r1, r2)).effectiveTags(doc)
        val b = JournalFolder.fold(listOf(r2, r1, a)).effectiveTags(doc)
        assertEquals(f, b)
        assertEquals(setOf("new"), f)
    }
}
