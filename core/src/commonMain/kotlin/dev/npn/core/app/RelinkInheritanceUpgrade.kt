package dev.npn.core.app

import dev.npn.core.index.JournalCoordinator
import dev.npn.core.journal.FoldedState
import dev.npn.core.journal.PendingOp
import dev.npn.core.model.FieldValues
import dev.npn.core.model.SystemProperty

// LEGACY, one-shot. Earlier builds wrote a relink with no copies: the fold showed a relink target
// its predecessors' metadata by inheritance (per field or property the latest write among them won,
// a clear included, and their tags were one OR-set). The fold no longer does; a relink's
// transaction copies the metadata instead (FoldedState.relinkOps). This file is the one place that
// still computes the inherited view, to write it once as copies into a library such a build relinked
// in. Nothing else may call it.

/**
 * Once per library, guarded by the synced [LibrarySettingKeys.RELINK_COPIES]: give every relink
 * target whose inherited view differs from its own the copies that make them equal, and set the
 * key, in one transaction. Run at open as an [upgradeAtOpen], so it is best effort and tried again
 * at the next open, and under the writer lock like any commit ([JournalCoordinator.commitBuilt]).
 *
 * The cheap check runs first, as for the field seeding: only an open that has not upgraded yet
 * pays for the fold. Devices converge through the key and LWW: two that upgrade at once write the
 * same values, and their tag copies are one OR-set.
 *
 * The one race it accepts: a device that upgrades before an edit to a predecessor (made on a device
 * that had not seen the relink) reached it copies the older value, and when its copy's HLC is the
 * later one, the copy wins over that edit.
 */
internal suspend fun JournalCoordinator.copyRelinkInheritanceIfNeeded() {
    if (setting(LibrarySettingKeys.RELINK_COPIES) != null || !canWrite()) return
    commitBuilt(RelinkInheritanceUpgrade::pending)
}

/** The inherited view earlier builds folded, computed once over [FoldedState]'s own registers. */
internal object RelinkInheritanceUpgrade {
    /** The value [LibrarySettingKeys.RELINK_COPIES] holds once a library is upgraded. */
    const val DONE = "1"

    /**
     * The upgrade, when [state] has not had it yet: the [copies] and the key that says they are
     * written. A relink this build writes carries it too, should the open's upgrade not have run
     * (writes blocked then): an upgrade after it would take its copies for inherited values.
     */
    fun pending(state: FoldedState): List<PendingOp> =
        if (state.effectiveSetting(LibrarySettingKeys.RELINK_COPIES) != null) {
            emptyList()
        } else {
            copies(state) + PendingOp.settingSet(LibrarySettingKeys.RELINK_COPIES, DONE)
        }

    /** The ops that give every relink target in [state] what it inherited as its own. */
    fun copies(state: FoldedState): List<PendingOp> =
        state
            .docsWithMetadata()
            .map(state::resolveDocId)
            .distinct()
            .filter { state.predecessorsOf(it).isNotEmpty() }
            .sorted()
            .flatMap { target -> copiesFor(state, target, state.predecessorsOf(target)) }

    private fun copiesFor(
        state: FoldedState,
        target: String,
        predecessors: List<String>,
    ): List<PendingOp> =
        buildList {
            val chain = listOf(target) + predecessors
            val inheritedFields = inheritedFields(state, chain)
            val ownFields = state.effectiveFields(target)
            for (key in (inheritedFields.keys + ownFields.keys).sorted()) {
                val value = inheritedFields[key]
                if (ownFields[key] != value) add(PendingOp.fieldSet(target, key, value))
            }
            for (key in SystemProperty.entries) {
                val value =
                    chain.mapNotNull { state.propRegisters(it)[key] }.maxByOrNull { it.hlc }?.value
                if (state.effectiveProp(target, key) != value) add(PendingOp.propSet(target, key, value))
            }
            val ownTags = state.effectiveTags(target)
            val inheritedTags = predecessors.flatMap { state.effectiveTags(it) }.toSortedSet()
            for (tag in inheritedTags) if (tag !in ownTags) add(PendingOp.tagAdd(target, tag))
        }

    /** Per key, the latest write across [chain]'s registers, a clear included; cleared keys left out. */
    private fun inheritedFields(
        state: FoldedState,
        chain: List<String>,
    ): Map<String, String> {
        val winners = HashMap<String, FoldedState.PropVal>()
        for (id in chain) {
            for ((key, register) in state.fieldRegisters(id)) {
                val existing = winners[key]
                if (existing == null || register.hlc > existing.hlc) winners[key] = register
            }
        }
        return winners
            .mapNotNull { (key, register) ->
                register.value?.let { key to FieldValues.normalized(state.effectiveFieldType(key), it) }
            }.toMap()
    }
}
