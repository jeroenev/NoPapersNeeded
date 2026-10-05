package dev.npn.app.ui.rules

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.core.app.LibrarySession
import dev.npn.core.model.FieldDef
import dev.npn.core.rules.AutoMatchRule
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_rules_delete_failed
import no_papers_needed.composeapp.generated.resources.common_rules_save_failed
import no_papers_needed.composeapp.generated.resources.common_rules_update_failed

class AutoMatchRulesViewModel(
    session: LibrarySession,
) : MessagesViewModel() {
    private val repository = session.rules

    val rules: StateFlow<List<AutoMatchRule>> =
        repository
            .observeRules()
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    /** The registry, in order: what a rule can match against and what it can set. */
    val fields: StateFlow<List<FieldDef>> =
        session.fieldRegistry.fields
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    fun save(rule: AutoMatchRule) = launchReporting(Res.string.common_rules_save_failed) { repository.save(rule) }

    fun delete(id: String) = launchReporting(Res.string.common_rules_delete_failed) { repository.delete(id) }

    fun setEnabled(
        id: String,
        enabled: Boolean,
    ) = launchReporting(Res.string.common_rules_update_failed) { repository.setEnabled(id, enabled) }
}
