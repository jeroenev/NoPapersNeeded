package dev.npn.core.app

import dev.npn.core.db.AutoMatchRuleDao
import dev.npn.core.db.toEntity
import dev.npn.core.db.toRule
import dev.npn.core.rules.AutoMatchRule
import dev.npn.core.rules.AutoMatchRuleJson
import dev.npn.core.rules.RuleApplier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class AutoMatchRuleRepositoryImpl(
    private val dao: AutoMatchRuleDao,
    private val applier: RuleApplier,
    private val persist: suspend (String) -> Unit = {},
) : AutoMatchRuleRepository {
    override fun observeRules(): Flow<List<AutoMatchRule>> =
        dao.observeAll().map { list ->
            list.map { it.toRule() }.sortedWith(compareBy(AutoMatchRule::position, AutoMatchRule::id))
        }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun save(rule: AutoMatchRule): String {
        val id = rule.id.ifBlank { Uuid.random().toString() }
        dao.upsert(rule.copy(id = id).toEntity())
        persistRules()
        applier.applyToExistingDocs()
        return id
    }

    override suspend fun delete(id: String) {
        dao.deleteById(id)
        persistRules()
    }

    override suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    ) {
        dao.setEnabled(id, enabled)
        persistRules()
        if (enabled) applier.applyToExistingDocs()
    }

    private suspend fun persistRules() {
        persist(AutoMatchRuleJson.encodeList(dao.getAll().map { it.toRule() }))
    }
}
