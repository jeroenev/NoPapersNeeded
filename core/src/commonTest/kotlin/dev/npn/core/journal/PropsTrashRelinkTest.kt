package dev.npn.core.journal

import dev.npn.core.model.SystemProperty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PropsTrashRelinkTest {
    private val doc = "a".repeat(64)
    private val doc2 = "b".repeat(64)
    private val doc3 = "c".repeat(64)

    private fun prop(
        seq: Long,
        key: SystemProperty,
        value: String?,
        t: Long,
        dev: String = "d1",
    ) = event(JournalOp.PropSet(key, value), doc, seq, t, dev)

    @Test
    fun propLwwByHlc() {
        val e1 = prop(1, SystemProperty.OCR_ENGINE, "tesseract", 10)
        val e2 = prop(2, SystemProperty.OCR_ENGINE, "mlkit", 20)
        assertEquals("mlkit", JournalFolder.fold(listOf(e1, e2)).effectiveProp(doc, SystemProperty.OCR_ENGINE))
        // order independent
        assertEquals("mlkit", JournalFolder.fold(listOf(e2, e1)).effectiveProp(doc, SystemProperty.OCR_ENGINE))
    }

    @Test
    fun propNullClearWinsWhenLater() {
        val e1 = prop(1, SystemProperty.OCR_ENGINE, "tesseract", 10)
        val e2 = prop(2, SystemProperty.OCR_ENGINE, null, 20)
        assertEquals(null, JournalFolder.fold(listOf(e1, e2)).effectiveProp(doc, SystemProperty.OCR_ENGINE))
    }

    @Test
    fun propTiebreakByDeviceId() {
        // same physical + counter, different device -> higher deviceId wins
        val ea = event(JournalOp.PropSet(SystemProperty.OCR_ENGINE, "a"), doc, 1, 10, "aaa")
        val ez = event(JournalOp.PropSet(SystemProperty.OCR_ENGINE, "z"), doc, 1, 10, "zzz")
        assertEquals("z", JournalFolder.fold(listOf(ea, ez)).effectiveProp(doc, SystemProperty.OCR_ENGINE))
    }

    private fun field(
        seq: Long,
        key: String,
        value: String?,
        t: Long,
        dev: String = "d1",
    ) = event(JournalOp.FieldSet(key, value), doc, seq, t, dev)

    @Test
    fun fieldLwwAndClear() {
        val e1 = field(1, "amount", "10.00", 10)
        val e2 = field(2, "amount", "42.50", 20)
        assertEquals(mapOf("amount" to "42.50"), JournalFolder.fold(listOf(e1, e2)).effectiveFields(doc))
        // order independent
        assertEquals(mapOf("amount" to "42.50"), JournalFolder.fold(listOf(e2, e1)).effectiveFields(doc))
        // later null clears the field entirely
        val e3 = field(3, "amount", null, 30)
        assertEquals(emptyMap(), JournalFolder.fold(listOf(e1, e2, e3)).effectiveFields(doc))
    }

    @Test
    fun fieldsAreIndependentByKey() {
        val a = field(1, "amount", "5", 10)
        val b = field(2, "vendor", "Acme", 20)
        val folded = JournalFolder.fold(listOf(a, b))
        assertEquals(mapOf("amount" to "5", "vendor" to "Acme"), folded.effectiveFields(doc))
        assertTrue(doc in folded.docsWithMetadata())
    }

    private fun trash(
        seq: Long,
        path: String,
        t: Long,
        dev: String = "d1",
    ) = event(JournalOp.DocTrash(path), doc, seq, t, dev)

    private fun restore(
        seq: Long,
        t: Long,
        dev: String = "d1",
    ) = event(JournalOp.DocRestore, doc, seq, t, dev)

    private fun delete(
        seq: Long,
        t: Long,
        dev: String = "d1",
    ) = event(JournalOp.DocDelete, doc, seq, t, dev)

    @Test
    fun trashRestoreLww() {
        val t = trash(1, "/x.pdf", 10)
        val r = restore(2, 20)
        assertFalse(JournalFolder.fold(listOf(t, r)).isTrashed(doc))
        // trash after restore -> trashed
        val t2 = trash(3, "/x.pdf", 30)
        assertTrue(JournalFolder.fold(listOf(t, r, t2)).isTrashed(doc))
        assertEquals("/x.pdf", JournalFolder.fold(listOf(t, r, t2)).trashOrigPath(doc))
    }

    @Test
    fun deleteTombstoneWinsForever() {
        val d = delete(1, 10)
        val r = restore(2, 999) // even a much later restore cannot undo delete
        val state = JournalFolder.fold(listOf(d, r))
        assertTrue(state.isDeleted(doc))
        assertFalse(state.isTrashed(doc))
        // order independent
        assertTrue(JournalFolder.fold(listOf(r, d)).isDeleted(doc))
    }

    private fun undelete(
        seq: Long,
        t: Long,
        dev: String = "d1",
    ) = event(JournalOp.DocUndelete, doc, seq, t, dev)

    @Test
    fun undeleteNewerThanDeleteBringsTheDocBackOutOfTheTrash() {
        val t = trash(1, "/x.pdf", 5)
        val d = delete(2, 10)
        val u = undelete(3, 20)
        for (events in listOf(listOf(t, d, u), listOf(u, d, t))) {
            val state = JournalFolder.fold(events)
            assertFalse(state.isDeleted(doc))
            assertFalse(state.isTrashed(doc))
        }
    }

    @Test
    fun deleteNewerThanUndeleteWins() {
        val u = undelete(1, 10)
        val d = delete(2, 20)
        assertTrue(JournalFolder.fold(listOf(u, d)).isDeleted(doc))
        assertTrue(JournalFolder.fold(listOf(d, u)).isDeleted(doc))
    }

    @Test
    fun trashAfterUndeleteTrashesAgain() {
        val d = delete(1, 10)
        val u = undelete(2, 20)
        val t = trash(3, "/x.pdf", 30)
        val state = JournalFolder.fold(listOf(t, u, d))
        assertFalse(state.isDeleted(doc))
        assertTrue(state.isTrashed(doc))
    }

    private fun relink(
        seq: Long,
        old: String,
        new: String,
        t: Long,
        dev: String,
    ) = event(JournalOp.Relink(new), old, seq, t, dev)

    @Test
    fun relinkChainResolution() {
        val r1 = relink(1, doc, doc2, 10, "d1")
        val r2 = relink(2, doc2, doc3, 20, "d1")
        val state = JournalFolder.fold(listOf(r1, r2))
        assertEquals(doc3, state.resolveDocId(doc))
        assertEquals(doc3, state.resolveDocId(doc2))
    }

    @Test
    fun predecessorsAreEveryIdResolvingOntoADocument() {
        val state = JournalFolder.fold(listOf(relink(1, doc, doc2, 10, "d1")))
        assertEquals(listOf(doc), state.predecessorsOf(doc2))

        state.apply(relink(2, doc2, doc3, 20, "d1"))

        assertEquals(listOf(doc, doc2), state.predecessorsOf(doc3))
        assertEquals(emptyList(), state.predecessorsOf(doc2))
        assertEquals(emptyList(), state.predecessorsOf(doc))
    }

    @Test
    fun concurrentRelinkDeterministicWinner() {
        // two devices relink same old doc to different targets
        val rA = relink(1, doc, doc2, 10, "aaa")
        val rZ = relink(1, doc, doc3, 10, "zzz") // same physical+counter, zzz wins on tiebreak
        val state = JournalFolder.fold(listOf(rA, rZ))
        assertEquals(doc3, state.resolveDocId(doc))
        // order independent
        assertEquals(doc3, JournalFolder.fold(listOf(rZ, rA)).resolveDocId(doc))
    }
}
