package dev.npn.core.app

import dev.npn.core.db.AutoMatchRuleDao
import dev.npn.core.db.toEntity
import dev.npn.core.db.toRule
import dev.npn.core.index.JournalCoordinator
import dev.npn.core.rules.AutoMatchRule
import dev.npn.core.rules.AutoMatchRuleJson
import dev.npn.core.util.NpnLog
import dev.npn.core.util.warn
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The read side of the library's auto-match rules: the list to match with ([current]), and the
 * rules table in the index kept in step with the journal's list ([mirror]). The journal is the
 * master copy: one synced setting holds the whole list ([LibrarySettingKeys.AUTO_MATCH_RULES]).
 *
 * A list this build cannot decode (written by a newer build, say) is not "no rules": the cache
 * keeps the last list that did decode, and [decoded] answers null for it, so an edit
 * ([AutoMatchRuleRepositoryImpl]) can refuse rather than write over it.
 */
internal class AutoMatchRuleSource(
    private val dao: AutoMatchRuleDao,
    private val coordinator: JournalCoordinator,
    private val log: NpnLog,
) {
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

    /**
     * The rules to match with: the journal's list, or the cache's last decoded one when this build
     * cannot read the journal's.
     */
    suspend fun current(): List<AutoMatchRule> =
        decoded(coordinator.setting(LibrarySettingKeys.AUTO_MATCH_RULES)) ?: dao.getAll().map { it.toRule() }

    /** Bring the cache in step with the journal's list; one that does not decode leaves it as it is. */
    suspend fun mirror() {
        val rules = decoded(coordinator.setting(LibrarySettingKeys.AUTO_MATCH_RULES)) ?: return
        val rows = rules.sortedWith(AutoMatchRule.ORDER).map { it.toEntity() }
        if (dao.getAll() != rows) dao.replaceAll(rows)
    }

    /** The rules in [json], none for a library that never had any, null for a list that does not decode. */
    fun decoded(json: String?): List<AutoMatchRule>? {
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
