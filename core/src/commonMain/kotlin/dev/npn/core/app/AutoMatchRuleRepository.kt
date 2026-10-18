package dev.npn.core.app

import dev.npn.core.rules.AutoMatchRule
import kotlinx.coroutines.flow.Flow

/** The library's document auto-matching rules, synced through its journal. */
interface AutoMatchRuleRepository {
    fun observeRules(): Flow<List<AutoMatchRule>>

    /** Save [rule], [AutoMatchRule.normalized], under a new id when its own is blank; returns its id. */
    suspend fun save(rule: AutoMatchRule): String

    suspend fun delete(id: String)

    suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    )
}
