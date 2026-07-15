package dev.npn.core.journal

import dev.npn.core.model.DocProperty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PropsTrashRelinkTest {

    private val doc = "a".repeat(64)
    private val doc2 = "b".repeat(64)
    private val doc3 = "c".repeat(64)

    private fun prop(seq: Long, key: DocProperty, value: String?, t: Long, dev: String = "d1") =
        JournalEvent(1, seq, Hlc(t, 0, dev), dev, doc, JournalOp.PropSet(key, value))

    @Test
    fun prop_lww_by_hlc() {
        val e1 = prop(1, DocProperty.STATUS, "inbox", 10)
        val e2 = prop(2, DocProperty.STATUS, "filed", 20)
        assertEquals("filed", JournalFolder.fold(listOf(e1, e2)).effectiveProp(doc, DocProperty.STATUS))
        // order independent
        assertEquals("filed", JournalFolder.fold(listOf(e2, e1)).effectiveProp(doc, DocProperty.STATUS))
    }

    @Test
    fun prop_null_clear_wins_when_later() {
        val e1 = prop(1, DocProperty.TITLE, "hello", 10)
        val e2 = prop(2, DocProperty.TITLE, null, 20)
        assertEquals(null, JournalFolder.fold(listOf(e1, e2)).effectiveProp(doc, DocProperty.TITLE))
    }

    @Test
    fun prop_tiebreak_by_device_id() {
        // same physical + counter, different device -> higher deviceId wins
        val ea = JournalEvent(1, 1, Hlc(10, 0, "aaa"), "aaa", doc, JournalOp.PropSet(DocProperty.STATUS, "a"))
        val ez = JournalEvent(1, 1, Hlc(10, 0, "zzz"), "zzz", doc, JournalOp.PropSet(DocProperty.STATUS, "z"))
        assertEquals("z", JournalFolder.fold(listOf(ea, ez)).effectiveProp(doc, DocProperty.STATUS))
    }

    private fun trash(seq: Long, path: String, t: Long, dev: String = "d1") =
        JournalEvent(1, seq, Hlc(t, 0, dev), dev, doc, JournalOp.DocTrash(path))

    private fun restore(seq: Long, t: Long, dev: String = "d1") =
        JournalEvent(1, seq, Hlc(t, 0, dev), dev, doc, JournalOp.DocRestore)

    private fun delete(seq: Long, t: Long, dev: String = "d1") =
        JournalEvent(1, seq, Hlc(t, 0, dev), dev, doc, JournalOp.DocDelete)

    @Test
    fun trash_restore_lww() {
        val t = trash(1, "/x.pdf", 10)
        val r = restore(2, 20)
        assertFalse(JournalFolder.fold(listOf(t, r)).isTrashed(doc))
        // trash after restore -> trashed
        val t2 = trash(3, "/x.pdf", 30)
        assertTrue(JournalFolder.fold(listOf(t, r, t2)).isTrashed(doc))
        assertEquals("/x.pdf", JournalFolder.fold(listOf(t, r, t2)).trashOrigPath(doc))
    }

    @Test
    fun delete_tombstone_wins_forever() {
        val d = delete(1, 10)
        val r = restore(2, 999) // even a much later restore cannot undo delete
        val state = JournalFolder.fold(listOf(d, r))
        assertTrue(state.isDeleted(doc))
        assertTrue(state.isTrashed(doc))
        // order independent
        assertTrue(JournalFolder.fold(listOf(r, d)).isDeleted(doc))
    }

    private fun relink(seq: Long, old: String, new: String, t: Long, dev: String) =
        JournalEvent(1, seq, Hlc(t, 0, dev), dev, old, JournalOp.Relink(new))

    @Test
    fun relink_chain_resolution() {
        val r1 = relink(1, doc, doc2, 10, "d1")
        val r2 = relink(2, doc2, doc3, 20, "d1")
        val state = JournalFolder.fold(listOf(r1, r2))
        assertEquals(doc3, state.resolveDocId(doc))
        assertEquals(doc3, state.resolveDocId(doc2))
    }

    @Test
    fun concurrent_relink_deterministic_winner_and_losers() {
        // two devices relink same old doc to different targets
        val rA = relink(1, doc, doc2, 10, "aaa")
        val rZ = relink(1, doc, doc3, 10, "zzz") // same physical+counter, zzz wins on tiebreak
        val state = JournalFolder.fold(listOf(rA, rZ))
        assertEquals(doc3, state.resolveDocId(doc))
        val losers = state.relinkLosers()
        assertEquals(listOf(doc to doc2), losers)
        // order independent
        assertEquals(doc3, JournalFolder.fold(listOf(rZ, rA)).resolveDocId(doc))
    }
}
