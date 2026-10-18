package dev.npn.core.app

import dev.npn.core.db.AutoMatchRuleDao
import dev.npn.core.db.toEntity
import dev.npn.core.db.toRule
import dev.npn.core.index.JournalCoordinator
import dev.npn.core.journal.PendingOp
import dev.npn.core.rules.AutoMatchRule
import dev.npn.core.rules.AutoMatchRuleJson
import dev.npn.core.rules.RuleApplier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The library's auto-match rules. The journal is the master copy: one synced setting holds the
 * whole list ([LibrarySettingKeys.AUTO_MATCH_RULES]), and the rules table in the index is its cache,
 * brought in step by [AutoMatchRuleSource.mirror] on open, after each write and when another
 * device's list arrives.
 *
 * A list this build cannot decode (written by a newer build, say) is not "no rules": the cache
 * keeps the last list that did decode, and every edit is refused rather than written over it,
 * since it could only write back what this build understood.
 *
 * Writes, and applying a saved or enabled rule to the documents, run through [SessionLifetime.owned], so the session
 * waits them out before it closes.
 */
internal class AutoMatchRuleRepositoryImpl(
    private val dao: AutoMatchRuleDao,
    /** The rules' read side, and their cache in the index. */
    val source: AutoMatchRuleSource,
    /** Runs the rules on a document, as indexing new text does. */
    val applier: RuleApplier,
    private val coordinator: JournalCoordinator,
    private val lifetime: SessionLifetime,
) : AutoMatchRuleRepository {
    override fun observeRules(): Flow<List<AutoMatchRule>> =
        dao.observeAll().map { list ->
            list.map { it.toRule() }.sortedWith(RULE_ORDER)
        }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun save(rule: AutoMatchRule): String {
        val saved = rule.copy(id = rule.id.ifBlank { Uuid.random().toString() }).toEntity().toRule()
        lifetime.owned {
            edit { rules -> rules.filterNot { it.id == saved.id } + saved }
            applier.applyToExistingDocs()
        }
        return saved.id
    }

    override suspend fun delete(id: String) = lifetime.owned { edit { rules -> rules.filterNot { it.id == id } } }

    override suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    ) = lifetime.owned {
        edit { rules -> rules.map { if (it.id == id) it.copy(enabled = enabled) else it } }
        if (enabled) applier.applyToExistingDocs()
    }

    /**
     * Rewrite the journal's list through [change] against its live value, then refresh the cache.
     * Its callers run it, and applying the rules after it, through [SessionLifetime.owned].
     */
    private suspend fun edit(change: (List<AutoMatchRule>) -> List<AutoMatchRule>) {
        coordinator.commitBuilt { state ->
            val json = state.effectiveSetting(LibrarySettingKeys.AUTO_MATCH_RULES)
            val rules =
                checkNotNull(source.decoded(json)) {
                    "The auto-match rules were saved by a newer version of the app"
                }
            val next = change(rules).sortedWith(RULE_ORDER)
            if (next == rules) {
                emptyList()
            } else {
                val encoded = AutoMatchRuleJson.encodeList(next)
                listOf(PendingOp.settingSet(LibrarySettingKeys.AUTO_MATCH_RULES, encoded))
            }
        }
        source.mirror()
    }
}
