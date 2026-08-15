package dev.npn.app.ui.ai

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.UiAction
import dev.npn.app.ui.common.UiTextKey
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.app.ui.common.uiText
import dev.npn.core.ai.AiEditRequest
import dev.npn.core.ai.AiField
import dev.npn.core.ai.AiFieldConfig
import dev.npn.core.ai.AiFieldMode
import dev.npn.core.ai.AiSuggest
import dev.npn.core.app.AiEditRun
import dev.npn.core.app.AiTagging
import dev.npn.core.app.ApplyOutcome
import dev.npn.core.app.ApplyResult
import dev.npn.core.app.BatchResult
import dev.npn.core.app.DocContentFacade
import dev.npn.core.app.DocField
import dev.npn.core.app.DocListItem
import dev.npn.core.app.DocumentRepository
import dev.npn.core.app.EditProgress
import dev.npn.core.app.EditScope
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.MetadataWriter
import dev.npn.core.app.ReviewChip
import dev.npn.core.app.ReviewConfigFacade
import dev.npn.core.app.ReviewDoc
import dev.npn.core.app.SavedView
import dev.npn.core.app.SavedViewRepository
import dev.npn.core.app.ScopeKey
import dev.npn.core.app.SettingsRepository
import dev.npn.core.model.ReviewMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The documents the next batch would process ([batch]) out of [total] still in scope. */
data class ScopePreview(
    val total: Int,
    val batch: List<DocListItem>,
) {
    companion object {
        val EMPTY = ScopePreview(0, emptyList())
    }
}

/** UI-level per-field autofill configuration for a run. */
data class AiFieldOption(
    val enabled: Boolean = true,
    val mode: AiFieldMode = AiFieldMode.UNFILLED,
)

/** The five phases of the bulk AI-edit flow. */
sealed interface EditPhase {
    /** Scope/fields/tags/prompt selection + Start. */
    data object Idle : EditPhase

    /** Concurrent single-request `suggestEdits` calls in progress. */
    data class Suggesting(
        val progress: EditProgress,
    ) : EditPhase

    /** Per-doc suggestion chips + field rows awaiting the user's confirmation; nothing written yet. */
    data class Review(
        val docs: List<ReviewDoc>,
        val emptyDocs: List<ReviewDoc>,
    ) : EditPhase

    /** Writing selected tags and data fields through the journal. */
    data object Applying : EditPhase

    /** Terminal state carrying counts + whether a next batch is available. */
    data class Done(
        val result: ApplyResult,
    ) : EditPhase
}

/**
 * Full UI state. [scope] and [reviewQueueAvailable] drive the Idle controls; [phase] is the current
 * step. Selection toggles live inside [EditPhase.Review.docs].
 *
 * [maxTags], [maxNewTags] and [batchSize] are per-run overrides, seeded from the Settings defaults
 * and editable in the Idle phase. [includeTags] turns tag suggestions on or off entirely and
 * [allowNewTags] decides whether brand-new tags may be invented (when off the request caps new tags
 * at zero, so the [maxNewTags] control is irrelevant); [includeTagRemovals] adds removal
 * suggestions, which stay available even with [includeTags] off. [fieldOptions] picks which
 * single-value fields to autofill and in which [AiFieldMode]; the free text [customPrompt] is
 * passed through to the model as extra instructions. All of these parametrize the single
 * per-document [AiEditRequest].
 */
data class AiEditUiState(
    val scope: EditScope = EditScope.UNTAGGED,
    val reviewQueueAvailable: Boolean = false,
    /** Documents handed over from the library selection; 0 when the page was opened standalone. */
    val selectionSize: Int = 0,
    val savedViews: List<SavedView> = emptyList(),
    val savedViewId: String? = null,
    val phase: EditPhase = EditPhase.Idle,
    val maxTags: Int = AiTagging.DEFAULT_TAGS_PER_DOC,
    val maxNewTags: Int = AiTagging.DEFAULT_NEW_TAGS_PER_DOC,
    val batchSize: Int = AiTagging.DEFAULT_BATCH_DOCS,
    val fieldOptions: Map<AiField, AiFieldOption> = AiField.entries.associateWith { AiFieldOption() },
    val includeTags: Boolean = true,
    val allowNewTags: Boolean = true,
    val includeTagRemovals: Boolean = false,
    val customPrompt: String = "",
)

/** Whether the configured run asks the model for anything at all (drives the Start affordance). */
fun AiEditUiState.hasEdits(): Boolean =
    includeTags ||
        includeTagRemovals ||
        fieldOptions.values.any { it.enabled } ||
        customPrompt.isNotBlank()

/** Whether [scope] can be picked: the hand-off, review mode and saved views gate three of them. */
fun AiEditUiState.scopeAvailable(scope: EditScope): Boolean =
    when (scope) {
        EditScope.SELECTION -> selectionSize > 0
        EditScope.REVIEW_QUEUE -> reviewQueueAvailable
        EditScope.MISSING_FIELDS -> fieldOptions.values.any { it.enabled }
        EditScope.SAVED_VIEW -> savedViews.isNotEmpty()
        EditScope.UNTAGGED, EditScope.ALL -> true
    }

/** The autofilled fields as library-filter fields; these define [EditScope.MISSING_FIELDS]. */
fun AiEditUiState.missingFields(): List<DocField> =
    AiField.entries.filter { fieldOptions[it]?.enabled == true }.map { it.docField() }

private fun AiField.docField(): DocField =
    when (this) {
        AiField.TITLE -> DocField.TITLE
        AiField.CORRESPONDENT -> DocField.CORRESPONDENT
        AiField.DOC_DATE -> DocField.DOC_DATE
    }

private fun AiEditUiState.scopeKey(): ScopeKey =
    ScopeKey(
        scope = scope,
        savedView = savedViews.firstOrNull { it.id == savedViewId },
        missing = missingFields(),
        batchSize = batchSize,
    )

/**
 * Drives the bulk AI-edit screen for one opened library. State machine:
 * `Idle → Suggesting → Review → Applying → Done`.
 *
 * The domain orchestration — scope resolution, bounded-concurrency suggestion batches, review
 * pre-selection policy and partial-failure apply semantics — lives in [AiEditRun] (one per
 * viewmodel). This class keeps only UI state: the Idle-phase controls that parametrize the
 * per-document [AiEditRequest], selection toggling inside the Review phase, and the mapping of
 * [BatchResult]/[ApplyOutcome] onto [EditPhase] plus user-facing messages.
 *
 * Runs are cancellable via the shared [run] job and by leaving the screen (viewModelScope).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions")
class AiEditViewModel(
    documents: DocumentRepository,
    meta: MetadataWriter,
    content: DocContentFacade,
    ai: AiSuggest,
    savedViewRepository: SavedViewRepository,
    reviewConfig: ReviewConfigFacade,
    private val settings: SettingsRepository,
    /** Documents handed over from the library's selection mode; empty when opened from the rail. */
    selection: List<String> = emptyList(),
) : MessagesViewModel() {
    constructor(
        session: LibrarySession,
        settings: SettingsRepository,
        selection: List<String> = emptyList(),
    ) : this(
        documents = session.documents,
        meta = session.meta,
        content = session.content,
        ai = session.aiSuggest,
        savedViewRepository = session.savedViews,
        reviewConfig = session.reviewConfig,
        settings = settings,
        selection = selection,
    )

    private val _ui =
        MutableStateFlow(
            AiEditUiState(
                scope = if (selection.isEmpty()) EditScope.UNTAGGED else EditScope.SELECTION,
                selectionSize = selection.size,
            ),
        )
    val ui: StateFlow<AiEditUiState> = _ui.asStateFlow()

    private val edit = AiEditRun(documents, meta, content, ai, selection)

    /**
     * The documents the next batch would process, refreshed as the scope, batch size, autofill
     * fields or the library itself change. Only collected while the Idle phase is on screen, so
     * the underlying (possibly library-wide) query is not kept alive during a run.
     */
    val preview: StateFlow<ScopePreview> =
        combine(_ui.map { it.scopeKey() }.distinctUntilChanged(), edit.processed, ::Pair)
            .flatMapLatest { (key, done) ->
                edit.docsFor(key).map { docs ->
                    val remaining = docs.filterNot { it.doc.docId in done }
                    ScopePreview(remaining.size, remaining.take(key.batchSize))
                }
            }.stateIn(viewModelScope, WhileSubscribedTimeout, ScopePreview.EMPTY)

    private var run: Job? = null

    init {
        viewModelScope.launch {
            val mode = runCatching { reviewConfig.reviewMode.first() }.getOrDefault(ReviewMode.DEFAULT)
            _ui.update { it.copy(reviewQueueAvailable = mode == ReviewMode.INBOX_WORKFLOW) }
        }
        // Saved views double as AI-edit scopes, so keep the list live and preselect the first one.
        viewModelScope.launch {
            runCatching {
                savedViewRepository.observeSavedViews().collect { views ->
                    _ui.update { it.copy(savedViews = views, savedViewId = it.savedViewId ?: views.firstOrNull()?.id) }
                }
            }
        }
        // Seed the per-run overrides from the persisted Settings defaults (once); keep the
        // AiTagging defaults on failure.
        viewModelScope.launch {
            val snapshot = runCatching { settings.settings.first() }.getOrNull()
            _ui.update {
                it.copy(
                    maxTags = snapshot?.aiMaxTagsPerDoc ?: AiTagging.DEFAULT_TAGS_PER_DOC,
                    maxNewTags = snapshot?.aiMaxNewTagsPerDoc ?: AiTagging.DEFAULT_NEW_TAGS_PER_DOC,
                )
            }
        }
    }

    /** Change the scope (Idle only); unavailable scopes are ignored. */
    fun setScope(scope: EditScope) {
        val state = _ui.value
        if (state.phase != EditPhase.Idle) return
        if (!state.scopeAvailable(scope)) return
        _ui.update { it.copy(scope = scope) }
    }

    /** Pick which saved view backs the [EditScope.SAVED_VIEW] scope (Idle only). */
    fun setSavedView(id: String) {
        if (_ui.value.phase != EditPhase.Idle) return
        if (_ui.value.savedViews.none { it.id == id }) return
        _ui.update { it.copy(savedViewId = id) }
    }

    /** Set the per-run max suggested tags per document (Idle only); clamped to [AiTagging.RANGE]. */
    fun setMaxTags(value: Int) {
        if (_ui.value.phase != EditPhase.Idle) return
        _ui.update { it.copy(maxTags = value.coerceIn(AiTagging.RANGE)) }
    }

    /** Set the per-run max new tags per document (Idle only); clamped to [AiTagging.NEW_TAGS_RANGE]. */
    fun setMaxNewTags(value: Int) {
        if (_ui.value.phase != EditPhase.Idle) return
        _ui.update { it.copy(maxNewTags = value.coerceIn(AiTagging.NEW_TAGS_RANGE)) }
    }

    /** Set the per-run batch size (Idle only); clamped to [AiTagging.BATCH_RANGE]. */
    fun setBatchSize(value: Int) {
        if (_ui.value.phase != EditPhase.Idle) return
        _ui.update { it.copy(batchSize = value.coerceIn(AiTagging.BATCH_RANGE)) }
    }

    /** Toggle whether [field] participates in the run (Idle only). */
    fun setFieldEnabled(
        field: AiField,
        enabled: Boolean,
    ) = updateFieldOption(field) { it.copy(enabled = enabled) }

    /** Set the autofill [mode] for [field] (Idle only). */
    fun setFieldMode(
        field: AiField,
        mode: AiFieldMode,
    ) = updateFieldOption(field) { it.copy(mode = mode) }

    /** Toggle whether tag additions are suggested at all (Idle only). */
    fun setIncludeTags(value: Boolean) {
        if (_ui.value.phase != EditPhase.Idle) return
        _ui.update { it.copy(includeTags = value) }
    }

    /** Toggle whether the model may invent tags outside the current vocabulary (Idle only). */
    fun setAllowNewTags(value: Boolean) {
        if (_ui.value.phase != EditPhase.Idle) return
        _ui.update { it.copy(allowNewTags = value) }
    }

    /** Toggle whether tag removals are suggested (Idle only). */
    fun setIncludeTagRemovals(value: Boolean) {
        if (_ui.value.phase != EditPhase.Idle) return
        _ui.update { it.copy(includeTagRemovals = value) }
    }

    /** Set the free-text extra instructions (Idle only). */
    fun setCustomPrompt(value: String) {
        if (_ui.value.phase != EditPhase.Idle) return
        _ui.update { it.copy(customPrompt = value) }
    }

    private fun updateFieldOption(
        field: AiField,
        transform: (AiFieldOption) -> AiFieldOption,
    ) {
        if (_ui.value.phase != EditPhase.Idle) return
        _ui.update {
            val option = it.fieldOptions[field] ?: return@update it
            it.copy(fieldOptions = it.fieldOptions + (field to transform(option)))
        }
    }

    /** Kick off the concurrent suggestion run over the next batch of the current scope. */
    fun start() {
        if (run?.isActive == true) return
        if (!_ui.value.hasEdits()) return
        run = viewModelScope.launch { suggestBatch() }
    }

    /** Cancel an in-flight suggestion run and return to Idle (nothing was written). */
    fun cancel() {
        run?.cancel()
        run = null
        _ui.update { it.copy(phase = EditPhase.Idle) }
    }

    /**
     * Toggle whether a doc's tag chip is selected for Apply (Review phase only). Tag additions and
     * removals never share a tag, so one toggle covers both lists.
     */
    fun toggleChip(
        docId: String,
        tag: String,
    ) = _ui.update { state ->
        val review = state.phase as? EditPhase.Review ?: return@update state
        val docs =
            review.docs.map { doc ->
                if (doc.docId != docId) {
                    doc
                } else {
                    doc.copy(
                        chips = doc.chips.mapChip(tag),
                        removals = doc.removals.mapChip(tag),
                    )
                }
            }
        state.copy(phase = review.copy(docs = docs))
    }

    private fun List<ReviewChip>.mapChip(tag: String): List<ReviewChip> =
        map { chip -> if (chip.tag == tag) chip.copy(selected = !chip.selected) else chip }

    /** Toggle whether a doc's suggested field value is selected for Apply (Review phase only). */
    fun toggleField(
        docId: String,
        field: AiField,
    ) = _ui.update { state ->
        val review = state.phase as? EditPhase.Review ?: return@update state
        val docs =
            review.docs.map { doc ->
                if (doc.docId != docId) {
                    doc
                } else {
                    doc.copy(
                        fields =
                            doc.fields.map { f ->
                                if (f.field == field) f.copy(selected = !f.selected) else f
                            },
                    )
                }
            }
        state.copy(phase = review.copy(docs = docs))
    }

    /** Select or deselect every suggestion of one document (Review phase only; no-op otherwise). */
    fun setDocSelection(
        docId: String,
        selected: Boolean,
    ) = _ui.update { state ->
        val review = state.phase as? EditPhase.Review ?: return@update state
        val docs =
            review.docs.map { doc ->
                if (doc.docId != docId) doc else doc.withAllSelected(selected)
            }
        state.copy(phase = review.copy(docs = docs))
    }

    /** Select or deselect every suggestion of every document (Review phase only; no-op otherwise). */
    fun setAllSelection(selected: Boolean) =
        _ui.update { state ->
            val review = state.phase as? EditPhase.Review ?: return@update state
            val docs = review.docs.map { doc -> doc.withAllSelected(selected) }
            state.copy(phase = review.copy(docs = docs))
        }

    private fun ReviewDoc.withAllSelected(selected: Boolean): ReviewDoc =
        copy(
            chips = chips.map { it.copy(selected = selected) },
            removals = removals.map { it.copy(selected = selected) },
            fields = fields.map { it.copy(selected = selected) },
        )

    /** Write the selected suggestions; see [AiEditRun] for partial-failure semantics. */
    fun apply() {
        val review = _ui.value.phase as? EditPhase.Review ?: return
        if (run?.isActive == true) return
        run = viewModelScope.launch { applySelections(review) }
    }

    /** From a Done state, run the next batch over the same scope, excluding processed docs. */
    fun nextBatch() {
        if (_ui.value.phase !is EditPhase.Done) return
        start()
    }

    private suspend fun suggestBatch() {
        // Snapshot the request once so mid-run state changes cannot affect this batch.
        val request = buildRequest()
        val result =
            edit.suggestNextBatch(_ui.value.scopeKey(), request) { progress ->
                _ui.update { it.copy(phase = EditPhase.Suggesting(progress)) }
            }
        when (result) {
            BatchResult.Empty -> {
                _ui.update { it.copy(phase = EditPhase.Done(ApplyResult(0, 0, moreRemain = false))) }
            }

            is BatchResult.Review -> {
                if (result.failures > 0) {
                    message(
                        uiText(
                            UiTextKey.COULD_NOT_SUGGEST_TAGS_PARTIAL,
                            result.failures,
                            result.total,
                        ),
                    )
                }
                _ui.update { it.copy(phase = EditPhase.Review(result.docs, result.emptyDocs)) }
            }
        }
    }

    private fun buildRequest(): AiEditRequest {
        val state = _ui.value
        return AiEditRequest(
            fields =
                AiField.entries.mapNotNull { field ->
                    state.fieldOptions[field]?.takeIf { it.enabled }?.let { AiFieldConfig(field, it.mode) }
                },
            tags = state.includeTags,
            tagRemovals = state.includeTagRemovals,
            maxTags = state.maxTags,
            // "Allow new tags" off means existing vocabulary only, which the cap already encodes.
            maxNewTags = if (state.allowNewTags) state.maxNewTags else 0,
            customPrompt = state.customPrompt.ifBlank { null },
        )
    }

    /**
     * Leave Review without writing anything: jump straight to Done with zero counts and the
     * correct moreRemain (reviewed docs are already processed, so they are excluded).
     */
    fun skipReview() {
        if (_ui.value.phase !is EditPhase.Review) return
        if (run?.isActive == true) return
        run =
            viewModelScope.launch {
                val more = edit.moreRemain(_ui.value.scopeKey())
                _ui.update { it.copy(phase = EditPhase.Done(ApplyResult(0, 0, more))) }
            }
    }

    private suspend fun applySelections(review: EditPhase.Review) {
        _ui.update { it.copy(phase = EditPhase.Applying) }
        when (val outcome = edit.apply(_ui.value.scopeKey(), review.docs)) {
            ApplyOutcome.NothingSelected -> {
                _ui.update { it.copy(phase = EditPhase.Review(review.docs, review.emptyDocs)) }
            }

            is ApplyOutcome.Done -> {
                _ui.update { it.copy(phase = EditPhase.Done(outcome.result)) }
            }

            is ApplyOutcome.Retry -> {
                message(
                    uiText(
                        UiTextKey.COULD_NOT_PARTIAL_DOCUMENTS,
                        UiAction.TAG,
                        outcome.failedDocs,
                        outcome.total,
                    ),
                )
                _ui.update { it.copy(phase = EditPhase.Review(outcome.remaining, review.emptyDocs)) }
            }
        }
    }
}
