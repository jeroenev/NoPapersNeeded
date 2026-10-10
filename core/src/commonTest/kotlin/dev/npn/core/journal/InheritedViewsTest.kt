package dev.npn.core.journal

import dev.npn.core.model.FieldKeys
import dev.npn.core.model.SystemProperty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A relink target's metadata and its predecessors' are one set of registers: per field or
 * property the latest write wins, a clear included, and its tags are one OR-set.
 */
class InheritedViewsTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)

    private fun event(
        doc: String,
        op: JournalOp,
        t: Long,
    ) = JournalEvent(1, t, Hlc(t, 0, "d1"), "d1", doc, op)

    private fun title(
        doc: String,
        value: String?,
        t: Long,
    ) = event(doc, JournalOp.FieldSet(FieldKeys.TITLE, value), t)

    private fun relink(
        old: String,
        new: String,
        t: Long,
    ) = event(old, JournalOp.Relink(new), t)

    /** [verify] the fold of [events] in their order and in reverse. */
    private fun folded(
        vararg events: JournalEvent,
        verify: (FoldedState) -> Unit,
    ) {
        verify(JournalFolder.fold(events.toList()))
        verify(JournalFolder.fold(events.reversed()))
    }

    @Test
    fun a_clear_on_the_target_masks_an_inherited_value() =
        folded(title(a, "Water bill", 10), relink(a, b, 20), title(b, null, 30)) { state ->
            assertEquals(emptyMap(), state.inheritedFields(b))
            assertEquals(mapOf(FieldKeys.TITLE to "Water bill"), state.effectiveFields(a))
        }

    @Test
    fun a_later_write_on_a_predecessor_wins_over_the_targets_own() =
        folded(title(b, "Own", 10), title(a, "Inherited", 20), relink(a, b, 30)) { state ->
            assertEquals(mapOf(FieldKeys.TITLE to "Inherited"), state.inheritedFields(b))
        }

    @Test
    fun a_chain_shows_its_newest_value_whatever_the_ids() {
        for ((older, newer) in listOf(a to b, b to a)) {
            folded(title(older, "Old", 10), title(newer, "New", 20), relink(older, newer, 30), relink(newer, c, 40)) {
                assertEquals(mapOf(FieldKeys.TITLE to "New"), it.inheritedFields(c))
            }
        }
    }

    @Test
    fun a_clear_masks_an_inherited_property() {
        val pin = event(a, JournalOp.PropSet(SystemProperty.OCR_ENGINE, "tesseract"), 10)
        val unpin = event(b, JournalOp.PropSet(SystemProperty.OCR_ENGINE, null), 30)
        folded(pin, relink(a, b, 20)) { assertEquals("tesseract", it.inheritedProp(b, SystemProperty.OCR_ENGINE)) }
        folded(pin, relink(a, b, 20), unpin) { assertNull(it.inheritedProp(b, SystemProperty.OCR_ENGINE)) }
    }

    @Test
    fun removing_an_inherited_tag_removes_the_predecessors_adds() {
        val add = event(a, JournalOp.TagAdd("filed"), 10)
        val state = JournalFolder.fold(listOf(add, relink(a, b, 20)))
        assertEquals(setOf("filed"), state.inheritedTags(b))

        val removal = state.tagRemovalOps(listOf(b, a), "filed")

        assertEquals(listOf(PendingOp.tagRemove(b, "filed", listOf(add.dot))), removal)
        state.apply(event(b, removal.single().op, 30))
        assertEquals(emptySet(), state.inheritedTags(b))
        assertEquals(emptySet(), state.effectiveTags(a))
    }
}
