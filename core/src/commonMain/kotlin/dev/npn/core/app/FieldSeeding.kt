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
 *
 * The first seeding writes beneath every edit ([JournalCoordinator.commitDefaultsBuilt]): a device
 * that opens the library before another device's journal has reached it sees no marker and seeds,
 * and those seeds must not undo what the other device's user changed in the fields meanwhile (a
 * label, a removed field) once that journal arrives. Its marker loses the same way, so a marker an
 * earlier seed version wrote takes over then, and the next open brings the library up from it.
 */
internal suspend fun JournalCoordinator.seedFieldRegistryIfNeeded(
    defs: List<FieldDef>,
    version: Int = FieldKeys.SEED_VERSION,
) {
    val marker = setting(FieldKeys.SEEDED_SETTING)
    if (seedVersion(marker) >= version || !canWrite()) return
    if (marker == null) {
        val seeded =
            commitDefaultsBuilt { state ->
                if (state.effectiveSetting(FieldKeys.SEEDED_SETTING) != null) return@commitDefaultsBuilt emptyList()
                defs.map { PendingOp.fieldDefSet(it) } +
                    PendingOp.settingSet(FieldKeys.SEEDED_SETTING, version.toString())
            }
        if (seeded) return
    }
    // An upgrade builds on what the library holds, so it is a write like any other.
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
