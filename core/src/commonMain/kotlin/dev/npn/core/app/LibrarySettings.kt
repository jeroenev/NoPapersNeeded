package dev.npn.core.app

import dev.npn.core.journal.PendingOp
import dev.npn.core.pathrules.StoragePathRule
import dev.npn.core.rules.AutoMatchRuleJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** The library's storage-path rule as its journal holds it: a template, armed or not. */
data class StoragePathSetting(
    val template: String? = null,
    val enabled: Boolean = false,
) {
    /** The rule documents are placed by, or null when there is no template to place them by. */
    val rule: StoragePathRule? get() = template?.let { StoragePathRule(it, enabled) }
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

/** Setting keys of the library behaviour that lives in its journal. */
internal object LibrarySettingKeys {
    const val STORAGE_PATH_TEMPLATE = "storagePathTemplate"
    const val STORAGE_PATH_ENABLED = "storagePathEnabled"

    /** The whole rule list as one [AutoMatchRuleJson] value; the rules table caches it. */
    const val AUTO_MATCH_RULES = "autoMatchRules"
}

/** [StoragePathFacade] over the journal's settings; writes run through [owned]. */
internal class JournalStoragePath(
    private val coordinator: JournalCoordinator,
    private val owned: suspend (suspend () -> Unit) -> Unit,
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

    /** Write [value] under [key] unless it already holds it, so an unchanged value adds no event. */
    private suspend fun set(
        key: String,
        value: String?,
    ) = owned {
        coordinator.commitBuilt { state ->
            if (state.effectiveSetting(key) == value) emptyList() else listOf(PendingOp.settingSet(key, value))
        }
    }
}

/**
 * The journal settings [legacy] stands for, minus its defaults: a device that never set a template
 * or a rule has nothing to adopt. A rules blob this build cannot read is carried over as it is, for
 * the newer build that wrote it.
 */
internal fun LegacyLibrarySettings.journalSettings(): Map<String, String> =
    buildMap {
        storagePathTemplate?.takeIf { it.isNotBlank() }?.let { put(LibrarySettingKeys.STORAGE_PATH_TEMPLATE, it) }
        if (storagePathEnabled) put(LibrarySettingKeys.STORAGE_PATH_ENABLED, true.toString())
        autoMatchRulesJson?.takeIf(::holdsRules)?.let { put(LibrarySettingKeys.AUTO_MATCH_RULES, it) }
    }

private fun holdsRules(json: String): Boolean =
    try {
        AutoMatchRuleJson.decodeList(json).isNotEmpty()
    } catch (_: IllegalArgumentException) {
        true
    }
