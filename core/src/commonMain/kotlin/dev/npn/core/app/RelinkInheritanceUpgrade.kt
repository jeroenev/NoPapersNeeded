package dev.npn.core.app

import dev.npn.core.index.JournalCoordinator
import dev.npn.core.journal.FoldedState
import dev.npn.core.journal.PendingOp
import dev.npn.core.model.SystemProperty

// LEGACY, recurring. Older builds write a relink with no copies and fold a relink target over its
// predecessors' registers (per field or property the latest write among them wins, a clear
// included, and their tags are one OR-set). This build's fold reads a document's own events only; a
// relink's transaction copies the metadata instead (FoldedState.relinkOps). As long as an older
// build may relink in a library, this file writes what such a relink left out: at every open,
// after every rescan or watch-driven fold that took in new events, and in every relink this build
// writes, so both folds show the same.

/**
 * Give every relink target whose inherited view differs from its own the copies that make them
 * equal, in one transaction, and nothing when none differs. Run at every open as an
 * [upgradeAtOpen], after every rescan, and after every ingest or reconcile that folded new events
 * (`LibrarySessionImpl.writeRelinkCopies`), each best effort, under the writer lock like any
 * commit ([JournalCoordinator.commitBuilt]); the fold it reads is the one that pass built anyway,
 * and the cost is linear in relink targets. So a relink an older build wrote, or an edit of a
 * predecessor by a device that had not seen the relink, is copied as soon as this device folds it,
 * as a fresh index's open would copy it, whether a rescan or the folder watch brought it.
 *
 * Returns false, having written nothing, while writes are refused: the copies are still owed.
 *
 * Devices converge through LWW: two that copy at once write the same values, and their tag copies
 * are one OR-set.
 *
 * The one race it accepts: a device that copies before an edit to a target or its predecessor
 * (made on a device that had not seen the copy's inputs) reached it writes the older value, and
 * when its copy's HLC is the later one, the copy wins over that edit, on older builds too.
 */
internal suspend fun JournalCoordinator.copyRelinkInheritance(): Boolean {
    if (!canWrite()) return false
    commitBuilt(RelinkInheritanceUpgrade::copies)
    return true
}

/** The inherited view older builds fold, written as copies over [FoldedState]'s own registers. */
internal object RelinkInheritanceUpgrade {
    /**
     * For a relink of [oldDoc] onto [newDoc], to go in its transaction: the [copies] of every other
     * target. [FoldedState.relinkOps] writes [newDoc]'s from the inherited views itself, and [oldDoc]
     * is superseded by the same transaction.
     */
    fun alongsideRelink(
        oldDoc: String,
        newDoc: String,
    ): (FoldedState) -> List<PendingOp> = { state -> copies(state).filter { it.doc != oldDoc && it.doc != newDoc } }

    /** The ops that give every relink target in [state] what it inherited as its own; empty once they have. */
    fun copies(state: FoldedState): List<PendingOp> =
        state.relinkTargets().sorted().flatMap { target -> copiesFor(state, target) }

    private fun copiesFor(
        state: FoldedState,
        target: String,
    ): List<PendingOp> =
        buildList {
            val chain = state.chainOf(target)
            val inheritedFields = state.inheritedFields(chain)
            val ownFields = state.effectiveFields(target)
            for (key in (inheritedFields.keys + ownFields.keys).sorted()) {
                val value = inheritedFields[key]
                if (ownFields[key] != value) add(PendingOp.fieldSet(target, key, value))
            }
            for (key in SystemProperty.entries) {
                val value = state.inheritedProp(chain, key)
                if (state.effectiveProp(target, key) != value) add(PendingOp.propSet(target, key, value))
            }
            val ownTags = state.effectiveTags(target)
            for (tag in state.inheritedTags(chain).sorted()) if (tag !in ownTags) add(PendingOp.tagAdd(target, tag))
        }
}
