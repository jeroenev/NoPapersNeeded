package dev.npn.app.ui.ai

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.app.ui.common.reasonText
import dev.npn.app.ui.common.uiText
import dev.npn.core.ai.AiEditRequest
import dev.npn.core.ai.AiFieldConfig
import dev.npn.core.ai.AiFieldMode
import dev.npn.core.app.AiEditRun
import dev.npn.core.app.AiTagging
import dev.npn.core.app.ApplyOutcome
import dev.npn.core.app.ApplyResult
import dev.npn.core.app.BatchResult
import dev.npn.core.app.DocListItem
import dev.npn.core.app.EditProgress
import dev.npn.core.app.EditScope
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.ReviewDoc
import dev.npn.core.app.SavedView
import dev.npn.core.app.ScopeKey
import dev.npn.core.app.SettingsRepository
import dev.npn.core.model.FieldDef
import dev.npn.core.model.ReviewMode
import dev.npn.core.util.runCatchingCancellable
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
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_edit_apply_failed
import no_papers_needed.composeapp.generated.resources.ai_edit_apply_fields_failed
import no_papers_needed.composeapp.generated.resources.ai_edit_apply_tags_failed
import no_papers_needed.composeapp.generated.resources.ai_edit_queue_failed
import no_papers_needed.composeapp.generated.resources.ai_edit_setup_load_failed
import no_papers_needed.composeapp.generated.resources.ai_edit_suggest_failed

/**
 * The documents the next batch would process ([batch]) out of [total] still in scope.
 *
 * [loaded] separates "the scope query has answered and the answer is none" from "it has not
 * answered yet", which a bare count cannot: without it an opening page claims zero documents for
 * as long as the (possibly library-wide) query takes, and the user reads that as a broken scope.
 */
data class ScopePreview(
    val total: Int,
    val batch: List<DocListItem>,
    val loaded: Boolean = true,
) {
    companion object {
        val EMPTY = ScopePreview(0, emptyList(), loaded = false)
    }
}

/**
 * What one Start would buy: [documents] in scope and [requests] model calls actually made now —
 * the whole scope for a background run, one batch for a run reviewed here.
 */
data class RunPlan(
    val documents: Int,
    val requests: Int,
    val background: Boolean,
    val loaded: Boolean,
) {
    /** Whether the run is big enough that Start should ask first. */
    val needsConfirmation: Boolean get() = requests > CONFIRM_ABOVE_REQUESTS

    companion object {
        val EMPTY = RunPlan(documents = 0, requests = 0, background = false, loaded = false)

        /**
         * Above this many model calls, Start asks for confirmation.
         *
         * Deliberately well above [AiTagging.BACKGROUND_THRESHOLD_DOCS]: that threshold answers
         * "should a person wait for this", which is true of runs far too small to be worth a
         * dialog. This one answers "is this a bulk commitment of the user's money" — a scope
         * picked by filter can be the whole library, and "All documents" is one tap away from the
         * scope they meant.
         */
        const val CONFIRM_ABOVE_REQUESTS = 50
    }
}

/** Why Start is inert, in the order the user should fix them. Null means the run is ready. */
enum class StartBlocker {
    NO_TASK,
    TOO_MANY_FIELDS,
    NOTHING_REQUESTED,
    NO_SAVED_VIEW,
    LOADING_SCOPE,
    NO_DOCUMENTS,
}

/** UI-level per-field autofill configuration for a run. */
data class AiFieldOption(
    val enabled: Boolean = true,
    val mode: AiFieldMode = AiFieldMode.UNFILLED,
)

/** The phases of the bulk AI-edit flow. */
sealed interface EditPhase {
    /** Scope/fields/tags/prompt selection + Start. */
    data object Idle : EditPhase

    /** Concurrent single-request `suggestEdits` calls in progress. */
    data class Suggesting(
        val progress: EditProgress,
    ) : EditPhase

    /**
     * The run was handed to the background queue: [documents] documents were submitted and no
     * model call has been made yet. Terminal for this screen — the result is picked up from the
     * processing queue, not here.
     */
    data class Queued(
        val documents: Int,
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
 * Whether leaving the page now throws away suggestions already paid for: a run still suggesting
 * (the viewmodel goes with the page and cancels it) or a Review nobody has applied.
 */
val EditPhase.leavingDiscardsSuggestions: Boolean
    get() = this is EditPhase.Suggesting || this is EditPhase.Review

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
    /**
     * The job the user picked, or null while they have not picked one — in which case the page
     * shows only the task chooser. See [EditTask].
     */
    val task: EditTask? = null,
    /**
     * Whether the field registry has answered yet. The field list is the only part of the form
     * that arrives asynchronously, and an empty list is otherwise indistinguishable from a library
     * with no fields.
     */
    val registryLoaded: Boolean = false,
    val maxTags: Int = AiTagging.DEFAULT_TAGS_PER_DOC,
    val maxNewTags: Int = AiTagging.DEFAULT_NEW_TAGS_PER_DOC,
    val batchSize: Int = AiTagging.DEFAULT_BATCH_DOCS,
    /** The registry, for labels and the "add a field to this run" picker. */
    val fields: List<FieldDef> = emptyList(),
    /**
     * Per-field autofill configuration by key. Seeded from the registry's aiEnabled fields once it
     * arrives; any other registered field can be added to the run from the picker.
     */
    val fieldOptions: Map<String, AiFieldOption> = emptyMap(),
    val includeTags: Boolean = true,
    val allowNewTags: Boolean = true,
    val includeTagRemovals: Boolean = false,
    val customPrompt: String = "",
    /**
     * The user's explicit choice about running in the background, or null while they have not
     * made one — in which case the scope size decides (see [runsInBackground]). Kept as an
     * override rather than a plain flag so the default stays live: changing the scope from three
     * documents to three hundred should flip it, and only until the user says otherwise.
     */
    val backgroundOverride: Boolean? = null,
)

/**
 * Whether a run over [count] documents goes to the background queue: the user's choice when they
 * made one, otherwise [AiTagging.BACKGROUND_THRESHOLD_DOCS]. Private so the threshold policy stays
 * in the viewmodel; the UI reads [AiEditViewModel.runsInBackground] instead.
 */
private fun AiEditUiState.runsInBackground(count: Int): Boolean =
    backgroundOverride ?: (count > AiTagging.BACKGROUND_THRESHOLD_DOCS)

/**
 * Whether the configured run asks the model for anything at all (drives the Start affordance).
 *
 * A custom prompt on its own deliberately does NOT count. The reply contract is built from the
 * requested sections (see `AiPrompts.editSystem`), so a prompt-only run asks the model to answer
 * with an object that has no allowed keys — it can only come back empty, having cost a request per
 * document. The prompt refines a run; it cannot be one.
 */
fun AiEditUiState.hasEdits(): Boolean =
    !tooManyFields && (includeTags || includeTagRemovals || fieldOptions.values.any { it.enabled })

/**
 * Why the run cannot start yet, most fixable first, or null when it can. Lives here rather than in
 * the composables because every entry is a policy statement about the run, not about the layout —
 * and because a disabled button with no reason is the single most common complaint about a form
 * this dense.
 */
internal fun AiEditUiState.startBlocker(preview: ScopePreview): StartBlocker? =
    when {
        task == null -> StartBlocker.NO_TASK
        tooManyFields -> StartBlocker.TOO_MANY_FIELDS
        !hasEdits() -> StartBlocker.NOTHING_REQUESTED
        scope == EditScope.SAVED_VIEW && savedViews.none { it.id == savedViewId } -> StartBlocker.NO_SAVED_VIEW
        !preview.loaded -> StartBlocker.LOADING_SCOPE
        preview.total == 0 -> StartBlocker.NO_DOCUMENTS
        else -> null
    }

/** Whether [scope] can be picked: the hand-off, review mode and saved views gate three of them. */
fun AiEditUiState.scopeAvailable(scope: EditScope): Boolean =
    when (scope) {
        EditScope.SELECTION -> selectionSize > 0
        EditScope.REVIEW_QUEUE -> reviewQueueAvailable
        EditScope.MISSING_FIELDS -> fieldOptions.values.any { it.enabled }
        EditScope.SAVED_VIEW -> savedViews.isNotEmpty()
        EditScope.UNTAGGED, EditScope.ALL -> true
    }

/**
 * The autofilled fields' keys in registry order: what the run asks for, and the fields whose
 * absence defines [EditScope.MISSING_FIELDS].
 */
fun AiEditUiState.enabledFieldKeys(): List<String> =
    fieldOptions.filterValues { it.enabled }.keys.inRegistryOrder(fields)

/** These keys in [fields]' registry order; a key the registry no longer lists comes last. */
internal fun Iterable<String>.inRegistryOrder(fields: List<FieldDef>): List<String> {
    val order = fields.map { it.key }
    return sortedBy { key -> order.indexOf(key).takeIf { it >= 0 } ?: Int.MAX_VALUE }
}

/** True when the run asks about more fields than one reply can sensibly carry (see P11 in the plan). */
val AiEditUiState.tooManyFields: Boolean
    get() = fieldOptions.count { it.value.enabled } > AiEditRequest.MAX_FIELDS_PER_RUN

private fun AiEditUiState.scopeKey(): ScopeKey =
    ScopeKey(
        scope = scope,
        savedView = savedViews.firstOrNull { it.id == savedViewId },
        missing = enabledFieldKeys(),
        batchSize = batchSize,
    )

/**
 * What the setup form changes, and its Start. [AiEditViewModel] implements it; every change is
 * ignored outside [EditPhase.Idle], where the run works from a snapshot of the form.
 */
interface AiEditSetupActions {
    /**
     * Pick the job this run does, applying its preset over the current configuration. Re-picking
     * the task the run already carries resets it to the preset, a one-click way out of a form the
     * user has edited into a corner.
     */
    fun setTask(task: EditTask)

    /** Drop back to the task chooser, keeping the scope and the numeric overrides. */
    fun clearTask()

    /** Change the scope; unavailable scopes are ignored. */
    fun setScope(scope: EditScope)

    /** Pick which saved view backs the [EditScope.SAVED_VIEW] scope. */
    fun setSavedView(id: String)

    /** Set the per-run max suggested tags per document; clamped to [AiTagging.RANGE]. */
    fun setMaxTags(value: Int)

    /** Set the per-run max new tags per document; clamped to [AiTagging.NEW_TAGS_RANGE]. */
    fun setMaxNewTags(value: Int)

    /** Set the per-run batch size; clamped to [AiTagging.BATCH_RANGE]. */
    fun setBatchSize(value: Int)

    /** Toggle whether field [key] participates in the run. */
    fun setFieldEnabled(
        key: String,
        enabled: Boolean,
    )

    /** Set the autofill [mode] for field [key]. */
    fun setFieldMode(
        key: String,
        mode: AiFieldMode,
    )

    /** Add a registered field the seed left out to this run, enabled. */
    fun addField(key: String)

    /** Toggle whether tag additions are suggested at all. */
    fun setIncludeTags(value: Boolean)

    /** Toggle whether the model may invent tags outside the current vocabulary. */
    fun setAllowNewTags(value: Boolean)

    /** Toggle whether tag removals are suggested. */
    fun setIncludeTagRemovals(value: Boolean)

    /** Set the free-text extra instructions. */
    fun setCustomPrompt(value: String)

    /** Choose whether this run goes to the background queue, overriding the size-based default. */
    fun setBackground(value: Boolean)

    /**
     * Start the run: suggest the next batch here, or hand the whole scope to the background
     * queue. Refuses whatever [AiEditUiState.startBlocker] refuses.
     */
    fun start()
}

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
    private val settings: SettingsRepository,
    /** Documents handed over from the library's selection mode; empty when opened from the rail. */
    selection: List<String> = emptyList(),
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
            ),
        )
    val ui: StateFlow<AiEditUiState> = _ui.asStateFlow()

    private val edit =
        AiEditRun(session.documents, session.meta, session.content, session.aiSuggest, fieldRegistry, selection)

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
            _ui.update { it.copy(reviewQueueAvailable = mode == ReviewMode.INBOX_WORKFLOW) }
        }
        // Saved views double as AI-edit scopes, so keep the list live and preselect the first one.
        viewModelScope.launch {
            runCatchingCancellable {
                savedViewRepository.observeSavedViews().collect { views ->
                    _ui.update { it.copy(savedViews = views, savedViewId = it.savedViewId ?: views.firstOrNull()?.id) }
                }
            }.onFailure(::reportSetupFailure)
        }
        // Seed the per-run overrides from the persisted Settings defaults (once); keep the
        // AiTagging defaults on failure.
        viewModelScope.launch {
            val snapshot =
                runCatchingCancellable { settings.settings.first() }
                    .onFailure(::reportSetupFailure)
                    .getOrNull()
            _ui.update {
                it.copy(
                    maxTags = snapshot?.aiMaxTagsPerDoc ?: AiTagging.DEFAULT_TAGS_PER_DOC,
                    maxNewTags = snapshot?.aiMaxNewTagsPerDoc ?: AiTagging.DEFAULT_NEW_TAGS_PER_DOC,
                )
            }
        }
    }

    private fun reportSetupFailure(failure: Throwable) =
        message(uiText(Res.string.ai_edit_setup_load_failed, failure.reasonText()))

    override fun setTask(task: EditTask) = updateIdle { it.withTask(task) }

    override fun clearTask() = updateIdle { it.copy(task = null) }

    override fun setScope(scope: EditScope) = updateIdle { if (it.scopeAvailable(scope)) it.copy(scope = scope) else it }

    override fun setSavedView(id: String) =
        updateIdle { state -> if (state.savedViews.any { it.id == id }) state.copy(savedViewId = id) else state }

    override fun setMaxTags(value: Int) = updateIdle { it.copy(maxTags = value.coerceIn(AiTagging.RANGE)) }

    override fun setMaxNewTags(value: Int) = updateIdle { it.copy(maxNewTags = value.coerceIn(AiTagging.NEW_TAGS_RANGE)) }

    override fun setBatchSize(value: Int) = updateIdle { it.copy(batchSize = value.coerceIn(AiTagging.BATCH_RANGE)) }

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
     * finished run asked for a next batch that is no longer there stops offering one.
     */
    override fun start() {
        if (run?.isActive == true) return
        val state = _ui.value
        run =
            viewModelScope.launch {
                val remaining = edit.remainingDocs(state.scopeKey())
                val blocker = state.startBlocker(ScopePreview(remaining.size, emptyList()))
                when {
                    blocker == StartBlocker.NO_DOCUMENTS -> endWithoutNextBatch()
                    blocker != null -> Unit
                    state.runsInBackground(remaining.size) -> queueRun(remaining.map { it.doc.docId })
                    else -> suggestBatch()
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
        runCatchingCancellable { editQueue.enqueueEditRun(docIds, buildRequest()) }
            .onSuccess {
                // Queued documents are spent as far as this page is concerned: the preview and any
                // later batch must not offer them again while the run is still out there.
                edit.markHandedOff(docIds)
                _ui.update { it.copy(phase = EditPhase.Queued(docIds.size)) }
            }.onFailure { cause ->
                message(uiText(Res.string.ai_edit_queue_failed, cause.reasonText()))
                _ui.update { it.copy(phase = EditPhase.Idle) }
            }
    }

    /** Cancel an in-flight suggestion run and return to Idle (nothing was written). */
    fun cancel() {
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
            state.copy(phase = review.copy(docs = transform(review.docs)))
        }

    /** Write the selected suggestions; see [AiEditRun] for partial-failure semantics. */
    override fun apply() {
        val review = _ui.value.phase as? EditPhase.Review ?: return
        if (run?.isActive == true) return
        run = viewModelScope.launch { applySelections(review) }
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
                        uiText(Res.plurals.ai_edit_suggest_failed, result.total, result.failures, result.total),
                    )
                }
                _ui.update { it.copy(phase = EditPhase.Review(result.docs, result.emptyDocs)) }
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
     * Leave Review without writing anything: jump straight to Done with zero counts and the
     * correct moreRemain (reviewed docs are already processed, so they are excluded).
     */
    override fun leaveReview() {
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
                message(outcome.failureText())
                _ui.update { it.copy(phase = EditPhase.Review(outcome.remaining, review.emptyDocs)) }
            }
        }
    }
}

/**
 * What an Apply that only partly landed could not write, as one sentence: the tags, the details,
 * or both. A Retry's documents keep selected exactly the items still owed, so they say which.
 */
internal fun ApplyOutcome.Retry.failureText(): UiText {
    val tags = remaining.any { doc -> doc.chips.any { it.selected } || doc.removals.any { it.selected } }
    val fields = remaining.any { doc -> doc.fields.any { it.selected } }
    val sentence =
        when {
            !fields -> Res.plurals.ai_edit_apply_tags_failed
            !tags -> Res.plurals.ai_edit_apply_fields_failed
            else -> Res.plurals.ai_edit_apply_failed
        }
    return uiText(sentence, total, failedDocs, total)
}
