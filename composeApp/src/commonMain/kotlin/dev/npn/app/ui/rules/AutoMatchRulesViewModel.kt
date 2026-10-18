package dev.npn.app.ui.rules

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.app.ui.common.uiState
import dev.npn.core.app.LibrarySession
import dev.npn.core.model.FieldDef
import dev.npn.core.rules.AutoMatchRule
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_rules_delete_failed
import no_papers_needed.composeapp.generated.resources.common_rules_save_failed
import no_papers_needed.composeapp.generated.resources.common_rules_update_failed

/** The one dialog the rules screen has open, or none; [AutoMatchRulesViewModel.dialog] keeps it. */
@Serializable
internal sealed interface RuleDialog {
    @Serializable
    data class Form(
        val form: RuleFormState,
    ) : RuleDialog

    @Serializable
    data class Delete(
        val id: String,
    ) : RuleDialog
}

class AutoMatchRulesViewModel(
    session: LibrarySession,
    /** What the screen's back-stack entry saves for a restored process; a new one opens it afresh. */
    saved: SavedStateHandle = SavedStateHandle(),
) : MessagesViewModel() {
    private val repository = session.rules

    /** Null until the first load, so the screen does not flash "no rules" before it. */
    val rules: StateFlow<List<AutoMatchRule>?> =
        repository
            .observeRules()
            .stateIn(viewModelScope, WhileSubscribedTimeout, null)

    /** The registry, in order: what a rule can match against and what it can set. */
    val fields: StateFlow<List<FieldDef>> =
        session.fieldRegistry.fields
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    private val dialogState = saved.uiState<RuleDialog?>(DIALOG_KEY, null)

    /** The dialog the screen has open, what is typed into a rule's form included. */
    internal val dialog: StateFlow<RuleDialog?> = dialogState.flow

    internal fun openDialog(dialog: RuleDialog?) = dialogState.set(dialog)

    fun save(rule: AutoMatchRule) = launchReporting(Res.string.common_rules_save_failed) { repository.save(rule) }

    fun delete(id: String) = launchReporting(Res.string.common_rules_delete_failed) { repository.delete(id) }

    fun setEnabled(
        id: String,
        enabled: Boolean,
    ) = launchReporting(Res.string.common_rules_update_failed) { repository.setEnabled(id, enabled) }

    private companion object {
        const val DIALOG_KEY = "rules.dialog"
    }
}
