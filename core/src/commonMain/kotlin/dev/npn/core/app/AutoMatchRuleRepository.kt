package dev.npn.core.app

import dev.npn.core.rules.AutoMatchRule
import kotlinx.coroutines.flow.Flow

/** Local-only repository for document auto-matching rules. */
interface AutoMatchRuleRepository {
    fun observeRules(): Flow<List<AutoMatchRule>>
    suspend fun save(rule: AutoMatchRule): String
    suspend fun delete(id: String)
    suspend fun setEnabled(id: String, enabled: Boolean)
}
