package dev.npn.core.app

import dev.npn.core.db.AutoMatchRuleDao
import dev.npn.core.db.toEntity
import dev.npn.core.db.toRule
import dev.npn.core.journal.PendingOp
import dev.npn.core.rules.AutoMatchRule
import dev.npn.core.rules.AutoMatchRuleJson
import dev.npn.core.rules.RuleApplier
import dev.npn.core.util.NpnLog
import dev.npn.core.util.warn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The library's auto-match rules. The journal is the master copy: one synced setting holds the
 * whole list ([LibrarySettingKeys.AUTO_MATCH_RULES]), and the rules table in the index is its cache,
 * brought in step by [mirror] on open, after each write and when another device's list arrives.
 *
 * A list this build cannot decode (written by a newer build, say) is not "no rules": the cache
 * keeps the last list that did decode, and every edit is refused rather than written over it,
 * since it could only write back what this build understood.
 *
 * Writes run through [owned], so the session waits them out before it closes.
 */
class AutoMatchRuleRepositoryImpl internal constructor(
    private val dao: AutoMatchRuleDao,
    private val applier: RuleApplier,
    private val coordinator: JournalCoordinator,
    private val log: NpnLog,
    private val owned: suspend (suspend () -> Unit) -> Unit,
) : AutoMatchRuleRepository {
    override fun observeRules(): Flow<List<AutoMatchRule>> =
        dao.observeAll().map { list ->
            list.map { it.toRule() }.sortedWith(compareBy(AutoMatchRule::position, AutoMatchRule::id))
        }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun save(rule: AutoMatchRule): String {
        val saved = rule.copy(id = rule.id.ifBlank { Uuid.random().toString() }).toEntity().toRule()
        edit { rules -> rules.filterNot { it.id == saved.id } + saved }
        applier.applyToExistingDocs()
        return saved.id
    }

    override suspend fun delete(id: String) = edit { rules -> rules.filterNot { it.id == id } }

    override suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    ) {
        edit { rules -> rules.map { if (it.id == id) it.copy(enabled = enabled) else it } }
        if (enabled) applier.applyToExistingDocs()
    }

    /**
     * The rules to match with: the journal's list, or the cache's last decoded one when this build
     * cannot read the journal's.
     */
    suspend fun current(): List<AutoMatchRule> =
        decoded(coordinator.setting(LibrarySettingKeys.AUTO_MATCH_RULES)) ?: dao.getAll().map { it.toRule() }

    /** Bring the cache in step with the journal's list; one that does not decode leaves it as it is. */
    suspend fun mirror() {
        val rules = decoded(coordinator.setting(LibrarySettingKeys.AUTO_MATCH_RULES)) ?: return
        val rows = rules.map { it.toEntity() }.sortedWith(compareBy({ it.position }, { it.id }))
        if (dao.getAll() != rows) dao.replaceAll(rows)
    }

    /** Rewrite the journal's list through [change] against its live value, then refresh the cache. */
    private suspend fun edit(change: (List<AutoMatchRule>) -> List<AutoMatchRule>) =
        owned {
            coordinator.commitBuilt { state ->
                val json = state.effectiveSetting(LibrarySettingKeys.AUTO_MATCH_RULES)
                val rules =
                    checkNotNull(decoded(json)) { "The auto-match rules were saved by a newer version of the app" }
                val next = change(rules).sortedWith(compareBy(AutoMatchRule::position, AutoMatchRule::id))
                if (next == rules) {
                    emptyList()
                } else {
                    val encoded = AutoMatchRuleJson.encodeList(next)
                    listOf(PendingOp.settingSet(LibrarySettingKeys.AUTO_MATCH_RULES, encoded))
                }
            }
            mirror()
        }

    /** The rules in [json], none for a library that never had any, null for a list that does not decode. */
    private fun decoded(json: String?): List<AutoMatchRule>? =
        if (json == null) {
            emptyList()
        } else {
            try {
                AutoMatchRuleJson.decodeList(json)
            } catch (e: IllegalArgumentException) {
                log.warn("Auto-match rules did not decode; keeping the last ones that did", e)
                null
            }
        }
}
