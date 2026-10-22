package dev.npn.core.app

import dev.npn.core.journal.FoldedState
import dev.npn.core.journal.JournalEvent
import dev.npn.core.journal.JournalFolder
import dev.npn.core.journal.JournalOp
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.event
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.SystemProperty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The recurring upgrade of relinks an older build wrote without copies: each target gets, as its
 * own, what that build's fold showed it by inheritance. Per field or property the latest write
 * across the target and its predecessors wins, a clear included, and their tags are one OR-set.
 */
class RelinkInheritanceUpgradeTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)

    private fun title(
        doc: String,
        value: String?,
        t: Long,
    ) = event(JournalOp.FieldSet(FieldKeys.TITLE, value), doc, t, t)

    private fun relink(
        old: String,
        new: String,
        t: Long,
    ) = event(JournalOp.Relink(new), old, t, t)

    /** [events] folded, in their order and in reverse, then upgraded: what [verify] sees. */
    private fun upgraded(
        vararg events: JournalEvent,
        verify: (FoldedState) -> Unit,
    ) {
        for (order in listOf(events.toList(), events.reversed())) {
            val state = JournalFolder.fold(order)
            RelinkInheritanceUpgrade.copies(state).forEachIndexed { i, op ->
                state.apply(event(op.op, op.doc, 1_000L + i, 1_000L + i, dev = "upgrade"))
            }
            // Once applied, the copies leave nothing to copy.
            assertEquals(emptyList(), RelinkInheritanceUpgrade.copies(state))
            verify(state)
        }
    }

    @Test
    fun aClearOnTheTargetMasksAnInheritedValue() =
        upgraded(title(a, "Water bill", 10), relink(a, b, 20), title(b, null, 30)) { state ->
            assertEquals(emptyMap(), state.effectiveFields(b))
            assertEquals(mapOf(FieldKeys.TITLE to "Water bill"), state.effectiveFields(a))
        }

    @Test
    fun aLaterWriteOnAPredecessorWinsOverTheTargetsOwn() =
        upgraded(title(b, "Own", 10), title(a, "Inherited", 20), relink(a, b, 30)) { state ->
            assertEquals(mapOf(FieldKeys.TITLE to "Inherited"), state.effectiveFields(b))
        }

    @Test
    fun aChainShowsItsNewestValueWhateverTheIds() {
        for ((older, newer) in listOf(a to b, b to a)) {
            upgraded(title(older, "Old", 10), title(newer, "New", 20), relink(older, newer, 30), relink(newer, c, 40)) {
                assertEquals(mapOf(FieldKeys.TITLE to "New"), it.effectiveFields(c))
            }
        }
    }

    @Test
    fun propertiesAreInheritedAndAClearMasksThem() {
        val pin = event(JournalOp.PropSet(SystemProperty.OCR_ENGINE, "tesseract"), a, 10, 10)
        val path = event(JournalOp.PropSet(SystemProperty.STORAGE_PATH, "bills/water.pdf"), a, 11, 11)
        val unpin = event(JournalOp.PropSet(SystemProperty.OCR_ENGINE, null), b, 30, 30)
        upgraded(pin, path, relink(a, b, 20)) {
            assertEquals("tesseract", it.effectiveProp(b, SystemProperty.OCR_ENGINE))
            assertEquals("bills/water.pdf", it.intendedPath(b))
        }
        upgraded(pin, relink(a, b, 20), unpin) { assertNull(it.effectiveProp(b, SystemProperty.OCR_ENGINE)) }
    }

    @Test
    fun theTargetGetsEveryLiveTagOfItsPredecessors() {
        val filed = event(JournalOp.TagAdd("filed"), a, 10, 10)
        val removed = event(JournalOp.TagAdd("removed"), a, 11, 11)
        val removal = event(JournalOp.TagRemove("removed", listOf(removed.dot)), b, 30, 30)
        upgraded(filed, removed, relink(a, b, 20), removal) { assertEquals(setOf("filed"), it.effectiveTags(b)) }
    }

    /** A library with no relink, or whose targets show what they inherited already, gets no copy. */
    @Test
    fun nothingToCopyWritesNothing() {
        assertEquals(emptyList(), RelinkInheritanceUpgrade.copies(JournalFolder.fold(listOf(title(a, "Bill", 10)))))
        val copied = JournalFolder.fold(listOf(title(a, "Bill", 10), relink(a, b, 20), title(b, "Bill", 21)))
        assertEquals(emptyList<PendingOp>(), RelinkInheritanceUpgrade.copies(copied))
    }
}
