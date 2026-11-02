package dev.npn.app

import dev.npn.core.app.AutoMatchRuleRepository
import dev.npn.core.app.SavedView
import dev.npn.core.app.SavedViewRepository
import dev.npn.core.rules.AutoMatchRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Read-side test doubles: saved views and auto-match rules (the documents' and the tags' are shared,
 * in :core-testing). Everything is backed by [MutableStateFlow]s (so tests can push new emissions)
 * and public mutable fields for behavior injection.
 */

class FakeAutoMatchRuleRepository : AutoMatchRuleRepository {
    val rules = MutableStateFlow<List<AutoMatchRule>>(emptyList())
    val saved = mutableListOf<AutoMatchRule>()
    val deletedIds = mutableListOf<String>()
    val enabledSets = mutableListOf<Pair<String, Boolean>>()

    /** In run order, as the real repository lists them: by position, then by id. */
    override fun observeRules(): Flow<List<AutoMatchRule>> =
        rules.map { list -> list.sortedWith(compareBy(AutoMatchRule::position, AutoMatchRule::id)) }

    override suspend fun save(rule: AutoMatchRule): String {
        saved += rule
        rules.value = rules.value.filter { it.id != rule.id } + rule
        return rule.id.ifBlank { "rule-${saved.size}" }
    }

    override suspend fun delete(id: String) {
        deletedIds += id
    }

    override suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    ) {
        enabledSets += id to enabled
    }
}

class FakeSavedViewRepository : SavedViewRepository {
    val views = MutableStateFlow<List<SavedView>>(emptyList())
    val saved = mutableListOf<SavedView>()
    val deleted = mutableListOf<String>()

    /** If set, save throws this. */
    var failSave: (() -> Nothing)? = null

    /** If set, delete throws this. */
    var failDelete: (() -> Nothing)? = null

    /** If set, the saved-views flow throws this when collected. */
    var failObserve: Throwable? = null

    override fun observeSavedViews(): Flow<List<SavedView>> =
        failObserve?.let { failure -> flow { throw failure } } ?: views

    override suspend fun save(view: SavedView) {
        failSave?.invoke()
        saved += view
    }

    override suspend fun delete(id: String) {
        failDelete?.invoke()
        deleted += id
    }
}
