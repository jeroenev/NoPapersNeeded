package dev.npn.core.journal

import dev.npn.core.model.FieldKeys
import dev.npn.core.model.SystemProperty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A relink target's metadata is its own: the relink's transaction copies its predecessor's onto it
 * ([FoldedState.relinkOps]), and the fold never reads a predecessor for a value.
 */
class RelinkCopiesTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    private var seq = 0L

    /** [op] on [doc] at wall time [t], as the next event of device `d1`. */
    private fun at(
        t: Long,
        doc: String,
        op: JournalOp,
    ) = event(op, doc, ++seq, t)

    /** Fold [ops] into [state] as one transaction at wall time [t], one tick apart, as the writer stamps them. */
    private fun FoldedState.commit(
        t: Long,
        ops: List<PendingOp>,
    ) = ops.forEachIndexed { i, op -> apply(at(t + i, checkNotNull(op.doc), op.op)) }

    /** A filed [a]: a title, a correspondent, a tag, its review mark, OCR pin and storage path. */
    private fun filed(): FoldedState =
        JournalFolder.fold(
            listOf(
                at(10, a, JournalOp.FieldSet(FieldKeys.TITLE, "Water bill")),
                at(11, a, JournalOp.FieldSet(FieldKeys.CORRESPONDENT, "Aquafin")),
                at(12, a, JournalOp.TagAdd("filed")),
                at(13, a, JournalOp.PropSet(SystemProperty.REVIEWED, "true")),
                at(14, a, JournalOp.PropSet(SystemProperty.OCR_ENGINE, "tesseract")),
                at(15, a, JournalOp.PropSet(SystemProperty.STORAGE_PATH, "bills/water.pdf")),
            ),
        )

    @Test
    fun theRelinkCarriesCopiesOfEveryFieldPropertyAndTag() {
        val state = filed()

        val ops = state.relinkOps(a, b)

        assertEquals(PendingOp.relink(a, b), ops.first())
        assertEquals(
            setOf(
                PendingOp.fieldSet(b, FieldKeys.CORRESPONDENT, "Aquafin"),
                PendingOp.fieldSet(b, FieldKeys.TITLE, "Water bill"),
                PendingOp.propSet(b, SystemProperty.REVIEWED, "true"),
                PendingOp.propSet(b, SystemProperty.OCR_ENGINE, "tesseract"),
                PendingOp.propSet(b, SystemProperty.STORAGE_PATH, "bills/water.pdf"),
                PendingOp.tagAdd(b, "filed"),
            ),
            ops.drop(1).toSet(),
        )
        state.commit(20, ops)
        assertEquals(state.effectiveFields(a), state.effectiveFields(b))
        assertEquals(setOf("filed"), state.effectiveTags(b))
        assertEquals("bills/water.pdf", state.intendedPath(b))
        assertNull(state.intendedPath(a))
    }

    /** The old document's values replace the new one's, what only the new one has stays, and their tags join. */
    @Test
    fun aRelinkToADocumentWithMetadataOfItsOwnGivesItTheOldOnes() {
        val state = filed()
        state.commit(
            16,
            listOf(
                PendingOp.fieldSet(b, FieldKeys.TITLE, "Scan 0001"),
                PendingOp.fieldSet(b, FieldKeys.NOTES, "From the scanner"),
                PendingOp.tagAdd(b, "inbox"),
                PendingOp.tagAdd(b, "filed"),
                PendingOp.propSet(b, SystemProperty.REVIEWED, "true"),
            ),
        )

        val ops = state.relinkOps(a, b)

        // Only what b does not hold already: its own "filed" and review mark need no copy.
        assertEquals(
            setOf(
                PendingOp.fieldSet(b, FieldKeys.CORRESPONDENT, "Aquafin"),
                PendingOp.fieldSet(b, FieldKeys.TITLE, "Water bill"),
                PendingOp.propSet(b, SystemProperty.OCR_ENGINE, "tesseract"),
                PendingOp.propSet(b, SystemProperty.STORAGE_PATH, "bills/water.pdf"),
            ),
            ops.drop(1).toSet(),
        )
        state.commit(30, ops)
        assertEquals(
            mapOf(
                FieldKeys.CORRESPONDENT to "Aquafin",
                FieldKeys.NOTES to "From the scanner",
                FieldKeys.TITLE to "Water bill",
            ),
            state.effectiveFields(b),
        )
        assertEquals(setOf("inbox", "filed"), state.effectiveTags(b))
    }

    @Test
    fun anEditOnTheTargetAfterTheRelinkStands() {
        val state = filed().apply { commit(20, relinkOps(a, b)) }

        state.commit(
            40,
            listOf(PendingOp.fieldSet(b, FieldKeys.TITLE, null), PendingOp.propSet(b, SystemProperty.OCR_ENGINE, null)),
        )
        state.commit(50, state.tagRemovalOps(listOf(b), "filed"))

        assertEquals(mapOf(FieldKeys.CORRESPONDENT to "Aquafin"), state.effectiveFields(b))
        assertNull(state.effectiveProp(b, SystemProperty.OCR_ENGINE))
        assertEquals(emptySet(), state.effectiveTags(b))
    }

    /**
     * Older builds show a relink target its predecessors' live tag adds too, so a removal from the
     * target lists their dots with its own: no build shows the tag after it.
     */
    @Test
    fun aTagRemovalFromTheTargetListsItsPredecessorsDots() {
        val state = filed().apply { commit(20, relinkOps(a, b)) }

        val removal = state.tagRemovalOps(listOf(b), "filed").single()

        assertEquals(b, removal.doc)
        val listed = (removal.op as JournalOp.TagRemove).dots
        assertEquals(2, listed.size)
        state.commit(50, listOf(removal))
        assertEquals(emptySet(), state.effectiveTags(b))
        assertEquals(emptySet(), state.effectiveTags(a))
        assertEquals(emptySet(), state.inheritedTags(state.chainOf(b)))
    }

    /**
     * A target an older build relinked without copies, relinked again here before any open copied
     * its inherited metadata: the new relink carries what the older builds showed it.
     */
    @Test
    fun aRelinkOfATargetWithoutCopiesCarriesWhatItInherited() {
        val c = "c".repeat(64)
        val state = filed().apply { commit(20, listOf(PendingOp.relink(a, b))) }
        state.commit(21, listOf(PendingOp.fieldSet(b, FieldKeys.NOTES, "Baked")))

        state.commit(30, state.relinkOps(b, c))

        assertEquals(
            mapOf(
                FieldKeys.CORRESPONDENT to "Aquafin",
                FieldKeys.NOTES to "Baked",
                FieldKeys.TITLE to "Water bill",
            ),
            state.effectiveFields(c),
        )
        assertEquals("true", state.effectiveProp(c, SystemProperty.REVIEWED))
        assertEquals("tesseract", state.effectiveProp(c, SystemProperty.OCR_ENGINE))
        assertEquals("bills/water.pdf", state.intendedPath(c))
        assertEquals(setOf("filed"), state.effectiveTags(c))
        // Older builds fold c over a and b: they show it the same.
        val chain = state.chainOf(c)
        assertEquals(state.effectiveFields(c), state.inheritedFields(chain))
        assertEquals(state.effectiveTags(c), state.inheritedTags(chain))
        for (key in SystemProperty.entries) assertEquals(state.effectiveProp(c, key), state.inheritedProp(chain, key))
    }

    /**
     * A value only the new document has stays, also where a clear on the old one is the later write,
     * which older builds would fold over it: the copy restates it with the latest HLC.
     */
    @Test
    fun theNewDocumentsOwnValueOutlastsAnOlderBuildsViewOfALaterClear() {
        val state = JournalFolder.fold(listOf(at(10, b, JournalOp.FieldSet(FieldKeys.NOTES, "Mine"))))
        state.commit(20, listOf(PendingOp.fieldSet(a, FieldKeys.NOTES, null)))

        val ops = state.relinkOps(a, b)

        assertEquals(listOf(PendingOp.relink(a, b), PendingOp.fieldSet(b, FieldKeys.NOTES, "Mine")), ops)
        state.commit(30, ops)
        assertEquals(mapOf(FieldKeys.NOTES to "Mine"), state.inheritedFields(state.chainOf(b)))
    }

    /**
     * An edit of the original that a device makes before the relink reaches it, later than the
     * relink, does not reach the target through the fold, nor does a tag removal from the original:
     * only the next open's copies (`RelinkInheritanceUpgrade`) bring the later edit over.
     */
    @Test
    fun aLaterEditOfThePredecessorDoesNotReachTheTarget() {
        val state = filed().apply { commit(20, relinkOps(a, b)) }

        state.commit(
            40,
            listOf(
                PendingOp.fieldSet(a, FieldKeys.TITLE, "Sewer bill"),
                PendingOp.propSet(a, SystemProperty.STORAGE_PATH, "bills/sewer.pdf"),
            ),
        )
        state.commit(50, state.tagRemovalOps(listOf(a), "filed"))

        assertEquals("Water bill", state.effectiveFields(b)[FieldKeys.TITLE])
        assertEquals("bills/water.pdf", state.intendedPath(b))
        assertEquals(setOf("filed"), state.effectiveTags(b))
    }

    /** A relink without copies, as earlier builds wrote one, shows the target nothing of the original's. */
    @Test
    fun aRelinkWithoutCopiesShowsTheTargetOnlyItsOwn() {
        val state = filed().apply { commit(20, listOf(PendingOp.relink(a, b))) }

        assertEquals(emptyMap(), state.effectiveFields(b))
        assertEquals(emptySet(), state.effectiveTags(b))
        assertNull(state.intendedPath(b))
        assertEquals(b, state.resolveDocId(a))
        assertEquals(listOf(a), state.predecessorsOf(b))
    }
}
