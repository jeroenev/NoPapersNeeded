package dev.npn.core.app

import dev.npn.core.db.DocumentDao
import dev.npn.core.index.JournalCoordinator
import dev.npn.core.journal.PendingOp
import dev.npn.core.model.ReviewMode
import dev.npn.core.model.SystemProperty
import dev.npn.core.pathrules.StoragePathRule
import dev.npn.core.rules.AutoMatchRuleJson
import dev.npn.core.settings.LegacyLibrarySettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** The library's storage-path rule as its journal holds it: a template, armed or not. */
data class StoragePathSetting(
    val template: String? = null,
    val enabled: Boolean = false,
) {
    /** The rule documents are placed by, or null when there is no template to place them by. */
    internal val rule: StoragePathRule? get() = template?.let { StoragePathRule(it, enabled) }
}

/**
 * Where the library files its documents. Library data, synced through the journal like
 * [ReviewConfigFacade.reviewMode]: the rule that chose a document's synced `storagePath` must be
 * the same on every device, or the location would depend on which device edited it last.
 */
interface StoragePathFacade {
    val storagePath: Flow<StoragePathSetting>

    /** Set the template; a blank one clears it, and with it the rule. */
    suspend fun setStoragePathTemplate(template: String?)

    suspend fun setStoragePathEnabled(enabled: Boolean)
}

/**
 * How long the trash keeps a document before the automatic purge deletes it. Library data, synced
 * through the journal: the purge deletes entries in the library's own trash, which sync carries to
 * every device, so a retention of one device's would decide for all of them.
 */
interface TrashRetentionFacade {
    /** Days a trashed document is kept; [TrashSettings.DEFAULT_TRASH_RETENTION_DAYS] until set. */
    val trashRetentionDays: Flow<Int>

    /** Keep trashed documents for [days], one of [TrashSettings.RETENTION_RANGE]. */
    suspend fun setTrashRetentionDays(days: Int)
}

/**
 * Setting keys of the library behaviour that lives in its journal: every synced library setting is
 * listed here, but for the field registry's seed marker ([dev.npn.core.model.FieldKeys.SEEDED_SETTING]).
 * They are wire strings, never to be renamed.
 */
internal object LibrarySettingKeys {
    /** The review workflow, as a [ReviewMode.wire] value. */
    const val REVIEW_MODE = "reviewMode"

    /** Each saved view is the setting of this prefix and its id, its JSON the value. */
    const val SAVED_VIEW_PREFIX = "savedView:"

    const val STORAGE_PATH_TEMPLATE = "storagePathTemplate"
    const val STORAGE_PATH_ENABLED = "storagePathEnabled"

    /** The whole rule list as one [AutoMatchRuleJson] value; the rules table caches it. */
    const val AUTO_MATCH_RULES = "autoMatchRules"

    /** Days the trash keeps a document, as a decimal number. */
    const val TRASH_RETENTION_DAYS = "trashRetentionDays"
}

/** Library-wide review workflow, synced via the journal. */
interface ReviewConfigFacade {
    /** Library-wide review workflow, synced across devices via the journal. */
    val reviewMode: Flow<ReviewMode>

    suspend fun setReviewMode(mode: ReviewMode)

    /**
     * One-shot migration used when switching to INBOX_WORKFLOW. Marks documents reviewed:
     * [onlyTagged] true -> only documents that currently have at least one tag; false -> all live
     * documents. Writes reviewed=true prop-set events only where needed (unaffected docs keep the
     * default false). No-op set writes nothing.
     */
    suspend fun markReviewedForMigration(onlyTagged: Boolean)
}

/**
 * [ReviewConfigFacade] over the journal: the review workflow is a library setting, and the switch
 * to the inbox workflow marks documents reviewed with the same `prop-set` events [MetadataWriterImpl]
 * writes. Writes run through [SessionLifetime.owned], so the session waits them out before it closes.
 */
internal class JournalReviewConfig(
    private val coordinator: JournalCoordinator,
    private val documentDao: DocumentDao,
    private val lifetime: SessionLifetime,
) : ReviewConfigFacade {
    override val reviewMode: Flow<ReviewMode> =
        coordinator.settingFlow(LibrarySettingKeys.REVIEW_MODE).map(ReviewMode::fromWire)

    override suspend fun setReviewMode(mode: ReviewMode) =
        lifetime.owned<Unit> { coordinator.putSetting(LibrarySettingKeys.REVIEW_MODE, mode.wire) }

    // Journal events are permanent, so only documents the projection does not show reviewed yet
    // get one: a second run, or a library already reviewed, writes nothing.
    override suspend fun markReviewedForMigration(onlyTagged: Boolean) =
        lifetime.owned {
            val ops =
                documentDao
                    .unreviewedLiveDocIds(taggedOnly = onlyTagged)
                    .map { PendingOp.propSet(it, SystemProperty.REVIEWED, "true") }
            if (ops.isNotEmpty()) coordinator.commitAndApply(ops)
        }
}

/** [StoragePathFacade] over the journal's settings; writes run through [SessionLifetime.owned]. */
internal class JournalStoragePath(
    private val coordinator: JournalCoordinator,
    private val lifetime: SessionLifetime,
) : StoragePathFacade {
    override val storagePath: Flow<StoragePathSetting> =
        combine(
            coordinator.settingFlow(LibrarySettingKeys.STORAGE_PATH_TEMPLATE),
            coordinator.settingFlow(LibrarySettingKeys.STORAGE_PATH_ENABLED),
        ) { template, enabled -> StoragePathSetting(template, enabled.toBoolean()) }

    /** The rule as of now, for the import and edit paths that place a document. */
    fun rule(): StoragePathRule? =
        StoragePathSetting(
            coordinator.setting(LibrarySettingKeys.STORAGE_PATH_TEMPLATE),
            coordinator.setting(LibrarySettingKeys.STORAGE_PATH_ENABLED).toBoolean(),
        ).rule

    override suspend fun setStoragePathTemplate(template: String?) =
        set(LibrarySettingKeys.STORAGE_PATH_TEMPLATE, template?.takeIf { it.isNotBlank() })

    override suspend fun setStoragePathEnabled(enabled: Boolean) =
        set(LibrarySettingKeys.STORAGE_PATH_ENABLED, enabled.toString())

    private suspend fun set(
        key: String,
        value: String?,
    ) = lifetime.owned<Unit> { coordinator.putSetting(key, value) }
}

/** [TrashRetentionFacade] over the journal's settings; writes run through [SessionLifetime.owned]. */
internal class JournalTrashRetention(
    private val coordinator: JournalCoordinator,
    private val lifetime: SessionLifetime,
) : TrashRetentionFacade {
    override val trashRetentionDays: Flow<Int> =
        coordinator.settingFlow(LibrarySettingKeys.TRASH_RETENTION_DAYS).map {
            retentionDays(it) ?: TrashSettings.DEFAULT_TRASH_RETENTION_DAYS
        }

    /**
     * The retention as of now, for the purge; null when the journal holds a value this build cannot
     * read (a newer build's), which keeps every entry rather than guess at a shorter retention.
     */
    fun current(): Int? = retentionDays(coordinator.setting(LibrarySettingKeys.TRASH_RETENTION_DAYS))

    override suspend fun setTrashRetentionDays(days: Int) {
        require(days in TrashSettings.RETENTION_RANGE) { "A retention of $days days is out of range" }
        lifetime.owned { coordinator.putSetting(LibrarySettingKeys.TRASH_RETENTION_DAYS, days.toString()) }
    }

    private fun retentionDays(stored: String?): Int? =
        if (stored == null) {
            TrashSettings.DEFAULT_TRASH_RETENTION_DAYS
        } else {
            stored.toIntOrNull()?.takeIf { it in TrashSettings.RETENTION_RANGE }
        }
}

/**
 * The journal settings these device settings stand for, as [JournalCoordinator.adoptSettings]
 * groups. The storage-path template and its switch are one setting, so they are one group: a device
 * with no template has no rule to adopt, whatever its switch says, and one with a template adopts
 * its switch too, off included. A rules blob this build cannot read is carried over as it is, for
 * the newer build that wrote it. A stored trash retention is adopted as stored, the default
 * included, so the library keeps its trash as long as the first device to open it did.
 */
internal fun LegacyLibrarySettings.journalSettings(): List<Map<String, String>> =
    listOfNotNull(
        storagePathTemplate?.takeIf { it.isNotBlank() }?.let { template ->
            mapOf(
                LibrarySettingKeys.STORAGE_PATH_TEMPLATE to template,
                LibrarySettingKeys.STORAGE_PATH_ENABLED to storagePathEnabled.toString(),
            )
        },
        autoMatchRulesJson?.takeIf(::holdsRules)?.let { mapOf(LibrarySettingKeys.AUTO_MATCH_RULES to it) },
        trashRetentionDays?.let { days ->
            mapOf(LibrarySettingKeys.TRASH_RETENTION_DAYS to days.coerceIn(TrashSettings.RETENTION_RANGE).toString())
        },
    )

private fun holdsRules(json: String): Boolean =
    try {
        AutoMatchRuleJson.decodeList(json).isNotEmpty()
    } catch (_: IllegalArgumentException) {
        true
    }

object TrashSettings {
    /** Days a trashed document is kept before an automatic purge removes it. */
    const val DEFAULT_TRASH_RETENTION_DAYS = 30

    /** The retentions a library may set: 0 purges at the next chance, four digits at most. */
    val RETENTION_RANGE = 0..9999
}
