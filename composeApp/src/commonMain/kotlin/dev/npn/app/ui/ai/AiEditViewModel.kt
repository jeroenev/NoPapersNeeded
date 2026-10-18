package dev.npn.app.ui.ai

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.app.ui.common.reasonText
import dev.npn.app.ui.common.uiText
import dev.npn.core.ai.AiEditRequest
import dev.npn.core.ai.AiFieldConfig
import dev.npn.core.ai.AiFieldMode
import dev.npn.core.ai.AiTagging
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.aiedit.AiEditRun
import dev.npn.core.app.aiedit.AiEditRunImpl
import dev.npn.core.app.aiedit.ApplyOutcome
import dev.npn.core.app.aiedit.ApplyResult
import dev.npn.core.app.aiedit.BatchResult
import dev.npn.core.app.aiedit.EditScope
import dev.npn.core.app.aiedit.ReviewDoc
import dev.npn.core.app.aiedit.ScopeKey
import dev.npn.core.model.FieldDef
import dev.npn.core.model.ReviewMode
import dev.npn.core.settings.SettingsRepository
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_edit_apply_failed
import no_papers_needed.composeapp.generated.resources.ai_edit_apply_fields_failed
import no_papers_needed.composeapp.generated.resources.ai_edit_apply_tags_failed
import no_papers_needed.composeapp.generated.resources.ai_edit_more_remain_failed
import no_papers_needed.composeapp.generated.resources.ai_edit_queue_failed
import no_papers_needed.composeapp.generated.resources.ai_edit_setup_load_failed
import no_papers_needed.composeapp.generated.resources.ai_edit_start_failed
import no_papers_needed.composeapp.generated.resources.ai_edit_suggest_failed

/**
 * Whether a run over [count] documents goes to the background queue: the user's choice when they
 * made one, otherwise [AiEditLimits.BACKGROUND_THRESHOLD_DOCS]. Private so the threshold policy stays
 * in the viewmodel; the UI reads [AiEditViewModel.runsInBackground] instead.
 */
private fun AiEditUiState.runsInBackground(count: Int): Boolean =
    backgroundOverride ?: (count > AiEditLimits.BACKGROUND_THRESHOLD_DOCS)

private fun AiEditUiState.scopeKey(): ScopeKey =
    ScopeKey(
        scope = scope,
        savedView = savedViews.firstOrNull { it.id == savedViewId },
        missing = enabledFieldKeys(),
        batchSize = batchSize,
        reviewMode = reviewMode,
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
class AiEditViewModel(
    session: LibrarySession,
    /** The device settings, whose tag limits are the run's defaults. */
    settings: SettingsRepository,
    /** Documents handed over from the library's selection mode; empty when opened from the rail. */
    selection: List<String> = emptyList(),
    /** The run this page drives, over the same [selection]. */
    private val edit: AiEditRun =
        AiEditRunImpl(
            session.documents,
            session.meta,
            session.content,
            session.aiSuggest,
            session.fieldRegistry,
            session.tags,
            selection,
        ),
) : ReviewViewModel(),
    AiEditSetupActions {
    private val editQueue = session.aiEditQueue
    private val fieldRegistry = session.fieldRegistry
    private val reviewConfig = session.reviewConfig
    private val savedViewRepository = session.savedViews

    private val _ui =
        MutableStateFlow(
            AiEditUiState(
                scope = if (selection.isEmpty()) EditScope.UNTAGGED else EditScope.SELECTION,
                selectionSize = selection.size,
                // The per-run overrides start from the Settings defaults.
                maxTags = settings.settings.value.aiMaxTagsPerDoc,
                maxNewTags = settings.settings.value.aiMaxNewTagsPerDoc,
            ),
        )
    val ui: StateFlow<AiEditUiState> = _ui.asStateFlow()

    init {
        // Seed the field options from the registry the first time it arrives: every aiEnabled field
        // participates, others sit in the picker. Later registry changes only refresh the labels and
        // the picker, never the user's choices for a run in progress.
        viewModelScope.launch {
            fieldRegistry.fields.collect { defs ->
                _ui.update { state ->
                    if (state.registryLoaded) state.copy(fields = defs) else state.seedFields(defs)
                }
            }
        }
    }

    /**
     * First registry answer: build the field rows, then let the chosen task have the last word.
     *
     * Re-applying the task here is what makes "Fill missing metadata" land on its field-based
     * scope even when it was picked before the registry answered — that scope is unavailable while
     * no field is enabled, so the preset would otherwise have silently fallen back. Only the first
     * emission does this; later ones must never stomp what the user has since configured.
     */
    private fun AiEditUiState.seedFields(defs: List<FieldDef>): AiEditUiState {
        val seeded =
            copy(
                fields = defs,
                registryLoaded = true,
                fieldOptions = defs.filter { it.aiEnabled }.associate { it.key to AiFieldOption() },
            )
        return seeded.task?.let(seeded::withTask) ?: seeded
    }

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
                    ScopePreview(remaining.size, remaining.take(key.batchSize), loaded = true)
                }
            }.stateIn(viewModelScope, WhileSubscribedTimeout, ScopePreview.EMPTY)

    /**
     * What one Start would buy right now: where it runs, how many documents are in scope and how
     * many model calls that actually costs. Derived here (not in the composables) so the size
     * threshold stays a viewmodel/domain policy; [start] re-resolves the placement against a fresh
     * count when the run actually begins.
     */
    val plan: StateFlow<RunPlan> =
        combine(_ui, preview) { state, p ->
            val background = state.runsInBackground(p.total)
            RunPlan(
                documents = p.total,
                // A backgrounded run covers the whole scope; one reviewed here stops at a batch.
                requests = if (background) p.total else minOf(p.total, state.batchSize),
                background = background,
                loaded = p.loaded,
            )
        }.stateIn(viewModelScope, WhileSubscribedTimeout, RunPlan.EMPTY)

    /** Why Start is inert, or null when the run is ready; see [AiEditUiState.startBlocker]. */
    val startBlocker: StateFlow<StartBlocker?> =
        combine(_ui, preview) { state, p -> state.startBlocker(p) }
            .stateIn(viewModelScope, WhileSubscribedTimeout, StartBlocker.NO_TASK)

    private var run: Job? = null

    // Each setup read falls back to a default when it fails, and says so: the form must not quietly
    // offer fewer scopes or other caps than the user configured.
    init {
        viewModelScope.launch {
            val mode =
                runCatchingCancellable { reviewConfig.reviewMode.first() }
                    .onFailure(::reportSetupFailure)
                    .getOrDefault(ReviewMode.DEFAULT)
            _ui.update { it.copy(reviewMode = mode) }
        }
        // Saved views double as AI-edit scopes, so keep the list live and preselect the first one.
        viewModelScope.launch {
            runCatchingCancellable {
                savedViewRepository.observeSavedViews().collect { views ->
                    _ui.update { it.copy(savedViews = views, savedViewId = it.savedViewId ?: views.firstOrNull()?.id) }
                }
            }.onFailure(::reportSetupFailure)
        }
    }

    private fun reportSetupFailure(failure: Throwable) =
        message(uiText(Res.string.ai_edit_setup_load_failed, failure.reasonText()))

    override fun setTask(task: EditTask) = updateIdle { it.withTask(task) }

    override fun clearTask() = updateIdle { it.copy(task = null) }

    override fun setScope(scope: EditScope) =
        updateIdle { if (it.scopeAvailable(scope)) it.copy(scope = scope) else it }

    override fun setSavedView(id: String) =
        updateIdle { state -> if (state.savedViews.any { it.id == id }) state.copy(savedViewId = id) else state }

    override fun setMaxTags(value: Int) = updateIdle { it.copy(maxTags = value.coerceIn(AiTagging.RANGE)) }

    override fun setMaxNewTags(value: Int) =
        updateIdle { it.copy(maxNewTags = value.coerceIn(AiTagging.NEW_TAGS_RANGE)) }

    override fun setBatchSize(value: Int) = updateIdle { it.copy(batchSize = value.coerceIn(AiEditLimits.BATCH_RANGE)) }

    override fun setFieldEnabled(
        key: String,
        enabled: Boolean,
    ) = updateFieldOption(key) { it.copy(enabled = enabled) }

    override fun setFieldMode(
        key: String,
        mode: AiFieldMode,
    ) = updateFieldOption(key) { it.copy(mode = mode) }

    override fun addField(key: String) =
        updateIdle {
            if (key in it.fieldOptions) {
                it
            } else {
                it.asCustom().copy(fieldOptions = it.fieldOptions + (key to AiFieldOption()))
            }
        }

    override fun setIncludeTags(value: Boolean) = updateIdle { it.asCustom().copy(includeTags = value) }

    override fun setAllowNewTags(value: Boolean) = updateIdle { it.asCustom().copy(allowNewTags = value) }

    override fun setIncludeTagRemovals(value: Boolean) = updateIdle { it.asCustom().copy(includeTagRemovals = value) }

    override fun setCustomPrompt(value: String) = updateIdle { it.copy(customPrompt = value) }

    override fun setBackground(value: Boolean) = updateIdle { it.copy(backgroundOverride = value) }

    private fun updateFieldOption(
        key: String,
        transform: (AiFieldOption) -> AiFieldOption,
    ) = updateIdle {
        val option = it.fieldOptions[key] ?: return@updateIdle it
        it.asCustom().copy(fieldOptions = it.fieldOptions + (key to transform(option)))
    }

    /**
     * Change the setup form. Outside [EditPhase.Idle] the run works from a snapshot of it, so every
     * setter is a no-op there; checked inside the update, so a phase change cannot slip between.
     */
    private fun updateIdle(transform: (AiEditUiState) -> AiEditUiState) =
        _ui.update { if (it.phase == EditPhase.Idle) transform(it) else it }

    /**
     * Editing what a task stands for makes the run custom, so the chosen task never describes a
     * configuration the user has edited away. The document scope and the numeric caps are
     * deliberately not part of that identity: "fill missing metadata, but only in this saved view"
     * is still the same job.
     */
    private fun AiEditUiState.asCustom(): AiEditUiState =
        if (task == null || task == EditTask.CUSTOM) this else copy(task = EditTask.CUSTOM)

    /**
     * Start the run: suggest the next batch here, or hand the whole scope to the background queue.
     * Which one is [AiEditUiState.runsInBackground] of the *current* scope size, resolved here
     * rather than from the preview flow so the decision cannot be made against a stale count.
     *
     * Refuses whatever [AiEditUiState.startBlocker] refuses, judged against that fresh count: the
     * same gate the button reads, so nothing spends model calls while the page says it cannot. A
     * finished run asked for a next batch that is no longer there stops offering one. A scope that
     * cannot be read is reported, and the page stays where it was.
     */
    override fun start() {
        if (run?.isActive == true) return
        val state = _ui.value
        run =
            viewModelScope.launch {
                runCatchingCancellable {
                    val remaining = edit.remainingDocs(state.scopeKey())
                    val blocker = state.startBlocker(ScopePreview(remaining.size, emptyList()))
                    when {
                        blocker == StartBlocker.NO_DOCUMENTS -> endWithoutNextBatch()
                        blocker != null -> Unit
                        state.runsInBackground(remaining.size) -> queueRun(remaining.map { it.doc.docId })
                        else -> suggestBatch()
                    }
                }.onFailure { failure ->
                    message(uiText(Res.string.ai_edit_start_failed, failure.reasonText()))
                    _ui.update { s -> if (s.phase is EditPhase.Suggesting) s.copy(phase = EditPhase.Idle) else s }
                }
            }
    }

    /** A finished run whose next batch has gone (reviewed or removed elsewhere) stops offering it. */
    private fun endWithoutNextBatch() =
        _ui.update { state ->
            val done = state.phase as? EditPhase.Done ?: return@update state
            state.copy(phase = done.copy(result = done.result.copy(moreRemain = false)))
        }

    /**
     * Hand the whole remaining scope to the background queue. The whole scope, not one batch: the
     * point of queueing is that nobody is waiting, so splitting it would only make the user come
     * back for the rest.
     */
    private suspend fun queueRun(docIds: List<String>) {
        if (!reporting(Res.string.ai_edit_queue_failed) { editQueue.enqueueEditRun(docIds, buildRequest()) }) {
            _ui.update { it.copy(phase = EditPhase.Idle) }
            return
        }
        // Queued documents are spent as far as this page is concerned: the preview and any later
        // batch must not offer them again while the run is still out there.
        edit.markHandedOff(docIds)
        _ui.update { it.copy(phase = EditPhase.Queued(docIds.size)) }
    }

    /**
     * Cancel an in-flight suggestion run and return to Idle (nothing was written). Anything else is
     * left alone: a Cancel from the frame before the Review landed must not discard the batch.
     */
    fun cancel() {
        if (run?.isActive != true || _ui.value.phase !is EditPhase.Suggesting) return
        run?.cancel()
        run = null
        _ui.update { it.copy(phase = EditPhase.Idle) }
    }

    /** Leaving a Review skips the batch: its documents count as reviewed, the scope remains. */
    override val reviewExit = ReviewExit.SKIP_BATCH

    /** The Review phase's batch; a no-op in every other phase. */
    override fun updateReview(transform: (List<ReviewDoc>) -> List<ReviewDoc>) =
        _ui.update { state ->
            val review = state.phase as? EditPhase.Review ?: return@update state
            state.copy(phase = EditPhase.Review(review.batch.copy(docs = transform(review.batch.docs))))
        }

    /**
     * Write the selected suggestions; see [AiEditRun] for partial-failure semantics. A failed write
     * comes back as a Retry. Once every write landed the page shows Done, also when asking whether
     * more documents remain then failed: that is reported on its own, and no next batch is offered.
     * An Apply that throws is reported, and the Review is back as it was.
     */
    override fun apply() {
        val review = _ui.value.phase as? EditPhase.Review ?: return
        if (run?.isActive == true) return
        run =
            viewModelScope.launch {
                _ui.update { it.copy(phase = EditPhase.Applying) }
                applyReview(
                    review.batch,
                    Res.string.ai_edit_more_remain_failed,
                    write = { docs -> edit.apply(_ui.value.scopeKey(), docs) },
                    showReview = { batch -> _ui.update { it.copy(phase = EditPhase.Review(batch)) } },
                    showDone = { result -> _ui.update { it.copy(phase = EditPhase.Done(result)) } },
                )
            }
    }

    /** From a Done state, run the next batch over the same scope, excluding processed docs. */
    fun nextBatch() {
        if (_ui.value.phase !is EditPhase.Done) return
        start()
    }

    /**
     * Return to the setup form from a terminal phase, keeping the run's configuration and the
     * documents it already spent. The way to change one's mind between batches without leaving the
     * page and losing which documents were already reviewed.
     */
    fun reconfigure() {
        val phase = _ui.value.phase
        if (phase !is EditPhase.Done && phase !is EditPhase.Queued) return
        _ui.update { it.copy(phase = EditPhase.Idle) }
    }

    private suspend fun suggestBatch() {
        // Snapshot the request once so mid-run state changes cannot affect this batch.
        val request = buildRequest()
        // Cancelled runs stay cancelled: a progress callback delivered after [cancel] ran must not
        // put the spinner back on a screen that is already back at Idle.
        val running = currentCoroutineContext()
        val result =
            edit.suggestNextBatch(_ui.value.scopeKey(), request) { progress ->
                if (running.isActive) _ui.update { it.copy(phase = EditPhase.Suggesting(progress)) }
            }
        when (result) {
            BatchResult.Empty -> {
                _ui.update { it.copy(phase = EditPhase.Done(ApplyResult(0, 0, moreRemain = false))) }
            }

            is BatchResult.Review -> {
                if (result.failures > 0) {
                    message(
                        uiText(Res.plurals.ai_edit_suggest_failed, result.total, result.failures, result.total),
                    )
                }
                _ui.update { it.copy(phase = EditPhase.Review(ReviewBatchState(result.docs, result.emptyDocs))) }
            }
        }
    }

    private fun buildRequest(): AiEditRequest {
        val state = _ui.value
        return AiEditRequest(
            fields = state.enabledFieldKeys().map { key -> AiFieldConfig(key, state.fieldOptions.getValue(key).mode) },
            tags = state.includeTags,
            tagRemovals = state.includeTagRemovals,
            maxTags = state.maxTags,
            // "Allow new tags" off means existing vocabulary only, which the cap already encodes.
            maxNewTags = if (state.allowNewTags) state.maxNewTags else 0,
            customPrompt = state.customPrompt.ifBlank { null },
        )
    }

    /**
     * Leave Review without writing anything more: jump straight to Done with what earlier Apply
     * attempts of the batch wrote (none, unless one partly failed) and the correct moreRemain
     * (reviewed docs are already processed, so they are excluded). When that cannot be found out,
     * the failure is reported and no next batch is offered.
     */
    override fun leaveReview() {
        if (_ui.value.phase !is EditPhase.Review) return
        if (run?.isActive == true) return
        run =
            viewModelScope.launch {
                var more = false
                reporting(Res.string.ai_edit_more_remain_failed) { more = edit.moreRemain(_ui.value.scopeKey()) }
                _ui.update { it.copy(phase = EditPhase.Done(edit.landedSoFar().copy(moreRemain = more))) }
            }
    }
}

/**
 * What an Apply that only partly landed could not write, as one sentence: the tags, the details,
 * or both. A Retry's documents keep selected exactly the items still owed, so they say which.
 */
internal fun ApplyOutcome.Retry.failureText(): UiText {
    val tags = remaining.any { it.selectedTagCount > 0 }
    val fields = remaining.any { it.selectedFieldCount > 0 }
    val sentence =
        when {
            !fields -> Res.plurals.ai_edit_apply_tags_failed
            !tags -> Res.plurals.ai_edit_apply_fields_failed
            else -> Res.plurals.ai_edit_apply_failed
        }
    return uiText(sentence, total, failedDocs, total)
}
