package dev.npn.app.ui.rules

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.core.app.AutoMatchRuleRepository
import dev.npn.core.app.LibrarySession
import dev.npn.core.rules.AutoMatchRule
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class AutoMatchRulesViewModel(
    private val repository: AutoMatchRuleRepository,
) : MessagesViewModel() {

    constructor(session: LibrarySession) : this(session.rules)

    val rules: StateFlow<List<AutoMatchRule>> =
        repository.observeRules()
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    fun save(rule: AutoMatchRule) = launchReporting("save rule") { repository.save(rule) }

    fun delete(id: String) = launchReporting("delete rule") { repository.delete(id) }

    fun setEnabled(id: String, enabled: Boolean) =
        launchReporting("update rule") { repository.setEnabled(id, enabled) }
}
