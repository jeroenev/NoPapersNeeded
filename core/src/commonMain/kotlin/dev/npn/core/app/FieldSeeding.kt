package dev.npn.core.app

import dev.npn.core.index.JournalCoordinator
import dev.npn.core.journal.PendingOp
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys

/**
 * Write the well-known field definitions once per library, and bring a library seeded at an
 * older [version] up to it once ([FieldKeys.upgradeSeeded]). Guarded by the synced
 * [FieldKeys.SEEDED_SETTING] marker rather than by "are there any definitions", so a user who
 * deletes every default field does not get them back when another device opens the library.
 *
 * The cheap marker check runs first: [JournalCoordinator.commitBuilt] materializes the whole fold,
 * and paying that on every open is exactly what [JournalCoordinator.refreshState]'s projection
 * reuse avoids. Only a library that has not been seeded at [version] yet (once, ever) reaches the
 * fold.
 */
internal suspend fun JournalCoordinator.seedFieldRegistryIfNeeded(
    defs: List<FieldDef>,
    version: Int = FieldKeys.SEED_VERSION,
) {
    if (seedVersion(setting(FieldKeys.SEEDED_SETTING)) >= version || !canWrite()) return
    commitBuilt { state ->
        val seeded = state.effectiveSetting(FieldKeys.SEEDED_SETTING)
        val defOps =
            when {
                seedVersion(seeded) >= version -> return@commitBuilt emptyList()
                seeded == null -> defs
                else -> FieldKeys.upgradeSeeded(state.effectiveFieldDefs(), defs)
            }.map { PendingOp.fieldDefSet(it) }
        defOps + PendingOp.settingSet(FieldKeys.SEEDED_SETTING, version.toString())
    }
}

/**
 * Give the seeded definitions back the [FieldDef.perDocument] flag an older build dropped
 * ([FieldKeys.lostPerDocument]), changing nothing else. [projected] is the registry as the index
 * holds it: the cheap check, so an open where nothing was lost writes nothing and skips the fold.
 * One write per loss: the older build only drops the flag again when its user edits the field.
 */
internal suspend fun JournalCoordinator.restoreSeededPerDocument(
    seeds: List<FieldDef>,
    projected: List<FieldDef>,
) {
    if (FieldKeys.lostPerDocument(projected, seeds).isEmpty() || !canWrite()) return
    commitBuilt { state ->
        FieldKeys.lostPerDocument(state.effectiveFieldDefs(), seeds).map { PendingOp.fieldDefSet(it) }
    }
}

/** The seed version a [FieldKeys.SEEDED_SETTING] value records: 0 when absent, 1 for the first marker. */
private fun seedVersion(marker: String?): Int = if (marker == null) 0 else marker.toIntOrNull() ?: 1
