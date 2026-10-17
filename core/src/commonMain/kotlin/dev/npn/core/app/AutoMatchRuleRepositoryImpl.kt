package dev.npn.core.app

import dev.npn.core.db.AutoMatchRuleDao
import dev.npn.core.db.toEntity
import dev.npn.core.db.toRule
import dev.npn.core.index.JournalCoordinator
import dev.npn.core.journal.PendingOp
import dev.npn.core.rules.AutoMatchRule
import dev.npn.core.rules.AutoMatchRuleJson
import dev.npn.core.rules.RuleApplier
import dev.npn.core.util.NpnLog
import dev.npn.core.util.warn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Writes, and applying a saved or enabled rule to the documents, run through [SessionLifetime.owned], so the session
 * waits them out before it closes.
 */
class AutoMatchRuleRepositoryImpl internal constructor(
    private val dao: AutoMatchRuleDao,
    private val applier: RuleApplier,
    private val coordinator: JournalCoordinator,
    private val log: NpnLog,
    private val lifetime: SessionLifetime,
) : AutoMatchRuleRepository {
    /** One stored list and what it decoded to: [rules] null, and why in [failure], when it did not. */
    private class Decoded(
        val json: String?,
        val rules: List<AutoMatchRule>?,
        val failure: IllegalArgumentException? = null,
    )

    /**
     * The last list [decoded] read. Rules are read for every indexed document and every rule pass,
     * so each stored list is decoded, and a bad one reported, once rather than on every read.
     */
    private val last = MutableStateFlow(Decoded(null, emptyList()))

    override fun observeRules(): Flow<List<AutoMatchRule>> =
        dao.observeAll().map { list ->
            list.map { it.toRule() }.sortedWith(compareBy(AutoMatchRule::position, AutoMatchRule::id))
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

    /**
     * Rewrite the journal's list through [change] against its live value, then refresh the cache.
     * Its callers run it, and applying the rules after it, through [SessionLifetime.owned].
     */
    private suspend fun edit(change: (List<AutoMatchRule>) -> List<AutoMatchRule>) {
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
    private fun decoded(json: String?): List<AutoMatchRule>? {
        val cached = last.value
        if (cached.json == json) return cached.rules
        val fresh = decode(json)
        // Only the read that records a list reports it, so concurrent reads of a bad one warn once.
        if (last.compareAndSet(cached, fresh)) {
            fresh.failure?.let { log.warn("Auto-match rules did not decode; keeping the last ones that did", it) }
        }
        return fresh.rules
    }

    private fun decode(json: String?): Decoded =
        try {
            Decoded(json, json?.let(AutoMatchRuleJson::decodeList).orEmpty())
        } catch (e: IllegalArgumentException) {
            Decoded(json, null, e)
        }
}
