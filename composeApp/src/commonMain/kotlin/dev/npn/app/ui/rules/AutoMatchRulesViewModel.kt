package dev.npn.app.ui.rules

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.app.ui.common.uiState
import dev.npn.app.ui.common.uiText
import dev.npn.core.app.DocListItem
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.LibrarySort
import dev.npn.core.model.FieldDef
import dev.npn.core.rules.AutoMatchRule
import dev.npn.core.rules.MatchAlgorithm
import dev.npn.core.rules.RuleField
import dev.npn.core.rules.RuleMatcher
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.automation_rule_move_failed
import no_papers_needed.composeapp.generated.resources.automation_rule_preview_count
import no_papers_needed.composeapp.generated.resources.automation_rule_preview_more
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

/** What the rule form shows of the documents the rule as typed matches: still looking, or what it found. */
internal sealed interface RulePreview {
    data object Checking : RulePreview

    /** [count] live documents match; [titles] names the first few of them. */
    data class Matches(
        val count: Int,
        val titles: List<String>,
    ) : RulePreview {
        /** "Matches N documents now", the whole sentence. */
        val summary: UiText get() = uiText(Res.plurals.automation_rule_preview_count, count, count)

        /** "…and N more" when [titles] does not name every match, else null. */
        val more: UiText?
            get() {
                val rest = count - titles.size
                return if (rest > 0) uiText(Res.plurals.automation_rule_preview_more, rest, rest) else null
            }
    }
}

class AutoMatchRulesViewModel(
    session: LibrarySession,
    /** What the screen's back-stack entry saves for a restored process; a new one opens it afresh. */
    saved: SavedStateHandle = SavedStateHandle(),
) : MessagesViewModel() {
    private val repository = session.rules
    private val documents = session.documents
    private val content = session.content

    /** Null until the first load, so the screen does not flash "no rules" before it. In run order. */
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

    /**
     * The documents the rule in the open form would match, as the next pass over the library reads
     * them: null while no form is open or its pattern cannot match anything yet. A change of what the
     * rule matches on shows [RulePreview.Checking] at once and looks again once typing pauses.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal val preview: StateFlow<RulePreview?> =
        dialogState.flow
            .map { (it as? RuleDialog.Form)?.form?.previewQuery() }
            .distinctUntilChanged()
            .flatMapLatest { query ->
                if (query == null) {
                    flowOf(null)
                } else {
                    flow<RulePreview?> {
                        emit(RulePreview.Checking)
                        delay(PREVIEW_DELAY_MS)
                        emit(runCatchingCancellable { matchesOf(query) }.getOrNull())
                    }
                }
            }.stateIn(viewModelScope, WhileSubscribedTimeout, null)

    /** Saves and moves one at a time, so each reads the positions the one before it wrote. */
    private val writing = Mutex()

    /**
     * Save [rule] from the form. The list decides its place, not the form: an existing rule keeps the
     * position it has now (a move since the form opened included), and a new one goes last.
     */
    fun save(rule: AutoMatchRule) =
        launchReporting(Res.string.common_rules_save_failed) {
            writing.withLock {
                val current = repository.observeRules().first()
                val position =
                    current.firstOrNull { rule.id.isNotEmpty() && it.id == rule.id }?.position
                        ?: ((current.maxOfOrNull { it.position } ?: -1) + 1)
                repository.save(rule.copy(position = position))
            }
        }

    fun delete(id: String) = launchReporting(Res.string.common_rules_delete_failed) { repository.delete(id) }

    fun setEnabled(
        id: String,
        enabled: Boolean,
    ) = launchReporting(Res.string.common_rules_update_failed) { repository.setEnabled(id, enabled) }

    /**
     * Move rule [id] [delta] places in the run order (-1 up, +1 down); past either end it stays. Each
     * rule whose position changes is saved as an ordinary rule update, so the journal holds nothing
     * but the rule list it always does.
     */
    fun move(
        id: String,
        delta: Int,
    ) = launchReporting(Res.string.automation_rule_move_failed) {
        writing.withLock {
            for (rule in moved(repository.observeRules().first(), id, delta)) repository.save(rule)
        }
    }

    /** The live documents [query] matches, the first few named. */
    private suspend fun matchesOf(query: PreviewQuery): RulePreview.Matches {
        val test = RuleMatcher.matcher(query.rule())
        val matched =
            documents
                .observeDocuments(LibraryFilter(), LibrarySort())
                .first()
                .filter { test(valueOf(query.field, it)) }
        return RulePreview.Matches(matched.size, matched.take(PREVIEW_TITLES).map { it.doc.displayTitle })
    }

    /** What a rule on [field] reads of [item]: the same value a pass over the library matches. */
    private suspend fun valueOf(
        field: RuleField,
        item: DocListItem,
    ): String? =
        when (field) {
            RuleField.Content -> content.extractedText(item.doc.docId)
            RuleField.FileName -> item.doc.fileName
            is RuleField.Field -> item.doc.fields[field.key]
        }

    private companion object {
        const val DIALOG_KEY = "rules.dialog"

        /** How long typing must pause before the documents are matched again. */
        const val PREVIEW_DELAY_MS = 400L

        /** How many matching documents the form names. */
        const val PREVIEW_TITLES = 5
    }
}

/** What a rule matches on: all the preview needs of the form, so a change elsewhere does not look again. */
private data class PreviewQuery(
    val field: RuleField,
    val algorithm: MatchAlgorithm,
    val pattern: String,
) {
    fun rule(): AutoMatchRule =
        AutoMatchRule(id = "", name = "", field = field, algorithm = algorithm, pattern = pattern)
}

/** The form's match as the matcher reads it, or null while it cannot match anything. */
private fun RuleFormState.previewQuery(): PreviewQuery? =
    pattern.trim().takeIf { it.isNotEmpty() && patternValid }?.let { PreviewQuery(field, algorithm, it) }

/**
 * The rules of [rules] (in run order) that moving [id] [delta] places gives a new position, with it.
 * Positions that already run strictly upwards swap between the two neighbours; a list with ties
 * (positions that leave the order to the rule ids, as rules saved before reordering existed) is
 * numbered afresh from 0, so the order on screen is the order the rules run in from then on.
 */
internal fun moved(
    rules: List<AutoMatchRule>,
    id: String,
    delta: Int,
): List<AutoMatchRule> {
    val from = rules.indexOfFirst { it.id == id }
    val to = from + delta
    if (from < 0 || delta == 0 || to !in rules.indices) return emptyList()
    val strictlyOrdered = rules.zipWithNext().all { (a, b) -> a.position < b.position }
    if (strictlyOrdered && (delta == 1 || delta == -1)) {
        val a = rules[from]
        val b = rules[to]
        return listOf(a.copy(position = b.position), b.copy(position = a.position))
    }
    val order = rules.toMutableList().apply { add(to, removeAt(from)) }
    return order.mapIndexedNotNull { index, rule -> rule.takeIf { it.position != index }?.copy(position = index) }
}
