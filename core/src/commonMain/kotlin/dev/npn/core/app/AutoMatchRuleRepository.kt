package dev.npn.core.app

import dev.npn.core.rules.AutoMatchRule
import kotlinx.coroutines.flow.Flow

/** The library's document auto-matching rules, synced through its journal. */
interface AutoMatchRuleRepository {
    fun observeRules(): Flow<List<AutoMatchRule>>

    suspend fun save(rule: AutoMatchRule): String

    suspend fun delete(id: String)

    suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    )
}
