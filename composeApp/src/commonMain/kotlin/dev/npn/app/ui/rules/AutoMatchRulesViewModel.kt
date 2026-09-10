package dev.npn.app.ui.rules

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.UiAction
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.core.app.AutoMatchRuleRepository
import dev.npn.core.app.FieldRegistry
import dev.npn.core.app.LibrarySession
import dev.npn.core.model.FieldDef
import dev.npn.core.rules.AutoMatchRule
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class AutoMatchRulesViewModel(
    private val repository: AutoMatchRuleRepository,
    fieldRegistry: FieldRegistry,
) : MessagesViewModel() {
    constructor(session: LibrarySession) : this(session.rules, session.fieldRegistry)

    val rules: StateFlow<List<AutoMatchRule>> =
        repository
            .observeRules()
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    /** The registry, in order: what a rule can match against and what it can set. */
    val fields: StateFlow<List<FieldDef>> =
        fieldRegistry.fields
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    fun save(rule: AutoMatchRule) = launchReporting(UiAction.SAVE_RULE) { repository.save(rule) }

    fun delete(id: String) = launchReporting(UiAction.DELETE_RULE) { repository.delete(id) }

    fun setEnabled(
        id: String,
        enabled: Boolean,
    ) = launchReporting(UiAction.UPDATE_RULE) { repository.setEnabled(id, enabled) }
}
