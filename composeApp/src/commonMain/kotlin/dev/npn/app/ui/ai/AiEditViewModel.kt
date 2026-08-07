package dev.npn.app.ui.ai

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.UiAction
import dev.npn.app.ui.common.UiTextKey
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.app.ui.common.uiText
import dev.npn.core.ai.AiEditRequest
import dev.npn.core.ai.AiEditSuggestion
import dev.npn.core.ai.AiField
import dev.npn.core.ai.AiFieldConfig
import dev.npn.core.ai.AiFieldMode
import dev.npn.core.app.AiTagging
import dev.npn.core.app.DocListItem
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.LibrarySort
import dev.npn.core.app.SettingsRepository
import dev.npn.core.model.ReviewMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Document scope for a bulk AI-edit run. */
enum class EditScope { UNTAGGED, REVIEW_QUEUE, ALL }

/**
 * One suggested (doc, tag) pairing in the review phase. [selected] drives whether Apply writes it;
 * pre-selected for existing-vocabulary tag additions, pre-deselected for [isNew] ones and for tag
 * removals.
 */
data class ReviewChip(
    val tag: String,
    val isNew: Boolean,
    val selected: Boolean,
)

/** UI-level per-field autofill configuration for a run. */
data class AiFieldOption(
    val enabled: Boolean = true,
    val mode: AiFieldMode = AiFieldMode.UNFILLED,
)

/**
 * One suggested data-field [value] in the review phase. [selected] drives whether Apply writes it;
 * pre-selected when it fills an empty field, pre-deselected when it would overwrite the [current]
 * value.
 */
data class ReviewField(
    val field: AiField,
    val value: String,
    val current: String?,
    val selected: Boolean,
)

/**
 * One document's row in the review phase. [chips] are tag additions, [removals] are tag removals
 * (a tag never appears in both — additions exclude current tags, removals are current tags) and
 * [fields] are the single-value autofill suggestions. Title and file name stay separate so display
 * fallback and future localization remain UI concerns.
 */
data class ReviewDoc(
    val docId: String,
    val title: String?,
    val fileName: String,
    val snippet: String?,
    val chips: List<ReviewChip>,
    val removals: List<ReviewChip>,
    val fields: List<ReviewField>,
)

/**
 * Suggestion progress, including structured identity for the most recently completed document.
 */
data class EditProgress(
    val done: Int,
    val total: Int,
    val currentTitle: String?,
    val currentFileName: String,
)

/**
 * Terminal counts after Apply: [applied] suggestions (tags added, tags removed, data fields)
 * written across [docsChanged] documents, plus whether more documents remain in the scope for a
 * follow-up batch.
 */
data class ApplyResult(
    val applied: Int,
    val docsChanged: Int,
    val moreRemain: Boolean,
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
 * and editable in the Idle phase. Tag additions from the existing vocabulary are always requested;
 * [allowNewTags] decides whether brand-new tags may be invented (when off the request caps new tags
 * at zero, and the [maxNewTags] control is irrelevant), and [includeTagRemovals] adds removal
 * suggestions. [fieldOptions] picks which single-value fields to autofill and in which
 * [AiFieldMode]; the free text [customPrompt] is passed through to the model as extra instructions.
 * All of these parametrize the single per-document [AiEditRequest].
 */
data class AiEditUiState(
    val scope: EditScope = EditScope.UNTAGGED,
    val reviewQueueAvailable: Boolean = false,
    val phase: EditPhase = EditPhase.Idle,
    val maxTags: Int = AiTagging.DEFAULT_TAGS_PER_DOC,
    val maxNewTags: Int = AiTagging.DEFAULT_NEW_TAGS_PER_DOC,
    val batchSize: Int = AiTagging.DEFAULT_BATCH_DOCS,
    val fieldOptions: Map<AiField, AiFieldOption> = AiField.entries.associateWith { AiFieldOption() },
    val allowNewTags: Boolean = true,
    val includeTagRemovals: Boolean = false,
    val customPrompt: String = "",
)

/**
 * Drives the bulk AI-edit screen for one opened library. State machine:
 * `Idle → Suggesting → Review → Applying → Done`.
 *
 * - Idle: pick an [EditScope], choose which fields to autofill (with a per-field overwrite
 *   [AiFieldMode]), whether brand-new tags may be invented and whether removals are proposed,
 *   optionally type a free-text [customPrompt] (e.g. "merge tags that differ only in case"), and
 *   tweak the per-run overrides ([AiEditUiState.maxTags],
 *   [AiEditUiState.maxNewTags], [AiEditUiState.batchSize]). The document count for the current
 *   scope is observed via [scopeCount]. Start runs
 *   [dev.npn.core.ai.AiFacade.suggestEdits] over the first [AiEditUiState.batchSize] docs with
 *   bounded concurrency ([SUGGEST_CONCURRENCY]); each document is ONE model request covering every
 *   requested section (tags + removals + fields + custom prompt). Progress is reported as each doc
 *   completes; the run is cancellable via the shared [run] job and by leaving the screen
 *   (viewModelScope). Results are re-ordered to match the original batch order.
 * - Review: per-doc suggestion chips (existing-vocabulary tag additions pre-selected, new tags and
 *   removals pre-deselected) plus data-field rows (fill-ins pre-selected, overwrites
 *   pre-deselected); tap toggles. Docs that produced no suggestions land in a collapsed group.
 *   Nothing is written here.
 * - Apply: selected tag additions/removals are grouped by tag and written with one `addTag` /
 *   `removeTag` journal call per distinct tag (multi-doc op lists committed in a single
 *   transaction); selected field values are written per (doc, field) through the matching
 *   MetadataWriter setter. Failures are collected and surfaced, successes are not rolled back
 *   (mirrors the bulk-op convention). Failed docs keep their unapplied selections so the user can
 *   retry.
 *
 * [processedIds] remembers already-processed docs for the session so the "next batch" continuation
 * excludes them (needed for ALL / review-queue scopes; UNTAGGED docs drop out of the scope query on
 * their own once tagged).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions")
class AiEditViewModel(
    val session: LibrarySession,
    private val settings: SettingsRepository,
) : MessagesViewModel() {
    private val scope = MutableStateFlow(EditScope.UNTAGGED)
    private val _ui = MutableStateFlow(AiEditUiState())
    val ui: StateFlow<AiEditUiState> = _ui.asStateFlow()

    /** Live document count for the currently selected scope (drives the Idle "N documents" line). */
    val scopeCount: StateFlow<Int> =
        scope
            .flatMapLatest { session.documents.observeCount(scopeFilter(it)) }
            .stateIn(viewModelScope, WhileSubscribedTimeout, 0)

    private val processedIds = mutableSetOf<String>()

    /** Suggestions written across earlier partial-failure apply attempts of the current Review batch. */
    private var appliedSoFar = 0
    private var run: Job? = null

    init {
        viewModelScope.launch {
            val mode = runCatching { session.reviewMode.first() }.getOrDefault(ReviewMode.DEFAULT)
            _ui.update { it.copy(reviewQueueAvailable = mode == ReviewMode.INBOX_WORKFLOW) }
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

    /** Change the scope (Idle only); the review-queue option is ignored when unavailable. */
    fun setScope(scope: EditScope) {
        if (_ui.value.phase != EditPhase.Idle) return
        if (scope == EditScope.REVIEW_QUEUE && !_ui.value.reviewQueueAvailable) return
        this.scope.value = scope
        _ui.update { it.copy(scope = scope) }
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

    /**
     * Toggle whether the model may invent tags outside the current vocabulary (Idle only). Tag
     * suggestions from the existing vocabulary are always requested.
     */
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

    /** Write the selected suggestions; see class docs for partial-failure semantics. */
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
        val batch = nextBatchDocs()
        if (batch.isEmpty()) {
            _ui.update { it.copy(phase = EditPhase.Done(ApplyResult(0, 0, moreRemain = false))) }
            return
        }
        _ui.update {
            val first = batch.first().doc
            it.copy(
                phase =
                    EditPhase.Suggesting(
                        EditProgress(0, batch.size, first.title, first.fileName),
                    ),
            )
        }
        // Results are indexed by their original batch position so the completion order (which is
        // non-deterministic under bounded concurrency) never leaks into the Review order.
        val results = arrayOfNulls<ReviewDoc>(batch.size)
        val failures = mutableListOf<String>()
        val semaphore = Semaphore(SUGGEST_CONCURRENCY)
        val progress = Mutex()
        var done = 0
        coroutineScope {
            batch
                .mapIndexed { index, item ->
                    async {
                        semaphore.withPermit {
                            val outcome =
                                runCatching {
                                    val suggestion = session.ai.suggestEdits(item.doc.docId, request)
                                    reviewDoc(item, suggestion, snippetFor(item.doc.docId))
                                }
                            // CancellationException must propagate so the whole scope tears down.
                            outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                            progress.withLock {
                                outcome
                                    .onSuccess { results[index] = it }
                                    .onFailure { failures += item.doc.docId }
                                done++
                                val prog = EditProgress(done, batch.size, item.doc.title, item.doc.fileName)
                                _ui.update { s -> s.copy(phase = EditPhase.Suggesting(prog)) }
                            }
                        }
                    }
                }.awaitAll()
        }
        if (failures.isNotEmpty()) {
            message(
                uiText(
                    UiTextKey.COULD_NOT_SUGGEST_TAGS_PARTIAL,
                    failures.size,
                    batch.size,
                ),
            )
        }
        enterReview(results.filterNotNull())
    }

    private fun buildRequest(): AiEditRequest {
        val state = _ui.value
        return AiEditRequest(
            fields =
                AiField.entries.mapNotNull { field ->
                    state.fieldOptions[field]?.takeIf { it.enabled }?.let { AiFieldConfig(field, it.mode) }
                },
            tags = true,
            tagRemovals = state.includeTagRemovals,
            maxTags = state.maxTags,
            // "Suggest new tags" off means existing vocabulary only, which the cap already encodes.
            maxNewTags = if (state.allowNewTags) state.maxNewTags else 0,
            customPrompt = state.customPrompt.ifBlank { null },
        )
    }

    /** First ~[SNIPPET_LENGTH] chars of extracted text, whitespace-collapsed; null when unavailable. */
    private suspend fun snippetFor(docId: String): String? =
        runCatching { session.content.extractedText(docId) }
            .getOrNull()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.take(SNIPPET_LENGTH)

    private fun enterReview(results: List<ReviewDoc>) {
        // Mark EVERY reviewed doc (incl. no-suggestion ones) processed now, not on apply, so the
        // next batch never re-suggests docs the user already saw — deselecting or skipping a doc
        // must not send it back through the model on "Next batch".
        results.forEach { processedIds += it.docId }
        appliedSoFar = 0
        val (withSuggestions, empty) =
            results.partition {
                it.chips.isNotEmpty() || it.removals.isNotEmpty() ||
                    it.fields.isNotEmpty()
            }
        _ui.update { it.copy(phase = EditPhase.Review(withSuggestions, empty)) }
    }

    /**
     * Leave Review without writing anything: jump straight to Done with zero counts and the
     * correct moreRemain (reviewed docs are already in [processedIds], so they are excluded).
     */
    fun skipReview() {
        if (_ui.value.phase !is EditPhase.Review) return
        if (run?.isActive == true) return
        run =
            viewModelScope.launch {
                _ui.update { it.copy(phase = EditPhase.Done(ApplyResult(0, 0, moreRemain()))) }
            }
    }

    private suspend fun applySelections(review: EditPhase.Review) {
        _ui.update { it.copy(phase = EditPhase.Applying) }
        val pendingAdds =
            review.docs.flatMap { doc ->
                doc.chips.filter { it.selected }.map { doc.docId to it.tag }
            }
        val pendingRemovals =
            review.docs.flatMap { doc ->
                doc.removals.filter { it.selected }.map { doc.docId to it.tag }
            }
        val pendingFields =
            review.docs.flatMap { doc ->
                doc.fields.filter { it.selected }.map { doc.docId to it }
            }
        if (pendingAdds.isEmpty() && pendingRemovals.isEmpty() && pendingFields.isEmpty()) {
            _ui.update { it.copy(phase = EditPhase.Review(review.docs, review.emptyDocs)) }
            return
        }
        val failedDocs = mutableSetOf<String>()
        val succeededAdds = mutableSetOf<Pair<String, String>>()
        val succeededRemovals = mutableSetOf<Pair<String, String>>()
        val succeededFields = mutableSetOf<Pair<String, AiField>>()
        // Group by tag so each distinct tag is written once for all its docs in a single journal
        // transaction (MetadataWriter.addTag/removeTag commit a multi-doc op list atomically). A
        // tag's call is all-or-nothing: on failure every (doc, tag) pair for that tag is retained
        // for retry.
        val addsByTag = pendingAdds.groupBy({ it.second }, { it.first })
        for ((tag, docIds) in addsByTag) {
            runCatching { session.meta.addTag(docIds, tag) }
                .onSuccess {
                    docIds.forEach { docId ->
                        succeededAdds += docId to tag
                        processedIds += docId
                    }
                }.onFailure { failedDocs += docIds }
        }
        val removalsByTag = pendingRemovals.groupBy({ it.second }, { it.first })
        for ((tag, docIds) in removalsByTag) {
            runCatching { session.meta.removeTag(docIds, tag) }
                .onSuccess {
                    docIds.forEach { docId ->
                        succeededRemovals += docId to tag
                        processedIds += docId
                    }
                }.onFailure { failedDocs += docIds }
        }
        // Field values are inherently per document, so each selected (doc, field) is one setter
        // call; failures retain only that pair for retry.
        for ((docId, field) in pendingFields) {
            runCatching { writeField(docId, field) }
                .onSuccess {
                    succeededFields += docId to field.field
                    processedIds += docId
                }.onFailure { failedDocs += docId }
        }
        finishApply(review, succeededAdds, succeededRemovals, succeededFields, failedDocs)
    }

    private suspend fun writeField(
        docId: String,
        field: ReviewField,
    ) = when (field.field) {
        AiField.TITLE -> session.meta.setTitle(docId, field.value)
        AiField.CORRESPONDENT -> session.meta.setCorrespondent(listOf(docId), field.value)
        AiField.DOC_DATE -> session.meta.setDocDate(docId, field.value)
    }

    private suspend fun finishApply(
        review: EditPhase.Review,
        succeededAdds: Set<Pair<String, String>>,
        succeededRemovals: Set<Pair<String, String>>,
        succeededFields: Set<Pair<String, AiField>>,
        failedDocs: Set<String>,
    ) {
        val succeededCount = succeededAdds.size + succeededRemovals.size + succeededFields.size
        if (failedDocs.isEmpty()) {
            // Include suggestions written in earlier partial-failure attempts of this same batch.
            val totalWritten = succeededCount + appliedSoFar
            val changedDocs =
                (succeededAdds.map { it.first } + succeededRemovals.map { it.first } + succeededFields.map { it.first })
                    .toSet()
                    .size
            val result = ApplyResult(totalWritten, changedDocs, moreRemain())
            _ui.update { it.copy(phase = EditPhase.Done(result)) }
            return
        }
        message(
            uiText(
                UiTextKey.COULD_NOT_PARTIAL_DOCUMENTS,
                UiAction.TAG,
                failedDocs.size,
                review.docs.size,
            ),
        )
        // Keep only the actually-unapplied selections on the retained failed rows, so a retry never
        // re-writes a (doc, tag) or (doc, field) that already succeeded. Carry the succeeded count
        // forward so it is not lost when the eventual Done result is produced.
        val remaining =
            review.docs
                .filter { it.docId in failedDocs }
                .map { doc ->
                    doc.copy(
                        chips =
                            doc.chips.map { chip ->
                                if (chip.selected && (doc.docId to chip.tag) in succeededAdds) {
                                    chip.copy(selected = false)
                                } else {
                                    chip
                                }
                            },
                        removals =
                            doc.removals.map { removal ->
                                if (removal.selected && (doc.docId to removal.tag) in succeededRemovals) {
                                    removal.copy(selected = false)
                                } else {
                                    removal
                                }
                            },
                        fields =
                            doc.fields.map { field ->
                                if (field.selected && (doc.docId to field.field) in succeededFields) {
                                    field.copy(selected = false)
                                } else {
                                    field
                                }
                            },
                    )
                }
        appliedSoFar += succeededCount
        _ui.update { it.copy(phase = EditPhase.Review(remaining, review.emptyDocs)) }
    }

    private suspend fun nextBatchDocs(): List<DocListItem> =
        currentScopeDocs().filter { it.doc.docId !in processedIds }.take(_ui.value.batchSize)

    /** Whether the scope still has unprocessed docs (drives the "Next batch" affordance). */
    private suspend fun moreRemain(): Boolean = nextBatchDocs().isNotEmpty()

    private suspend fun currentScopeDocs(): List<DocListItem> =
        session.documents.observeDocuments(scopeFilter(scope.value), SORT).first()

    private fun reviewDoc(
        item: DocListItem,
        suggestion: AiEditSuggestion,
        snippet: String?,
    ): ReviewDoc =
        ReviewDoc(
            docId = item.doc.docId,
            title = item.doc.title,
            fileName = item.doc.fileName,
            snippet = snippet,
            chips = suggestion.addTags.map { ReviewChip(it.tag, it.isNew, selected = !it.isNew) },
            removals = suggestion.removeTags.map { ReviewChip(it, isNew = false, selected = false) },
            fields =
                buildList {
                    suggestion.metadata.title?.let { add(reviewField(AiField.TITLE, it, item.doc.title)) }
                    suggestion.metadata.correspondent?.let {
                        add(reviewField(AiField.CORRESPONDENT, it, item.doc.correspondent))
                    }
                    suggestion.metadata.docDate?.let { add(reviewField(AiField.DOC_DATE, it, item.doc.docDate)) }
                },
        )

    /** Fill-ins (no current value) are pre-selected; overwrites require an explicit opt-in. */
    private fun reviewField(
        field: AiField,
        value: String,
        current: String?,
    ): ReviewField =
        ReviewField(
            field = field,
            value = value,
            current = current?.takeIf { it.isNotBlank() },
            selected = current.isNullOrBlank(),
        )

    private fun scopeFilter(scope: EditScope): LibraryFilter =
        when (scope) {
            EditScope.UNTAGGED -> LibraryFilter(untaggedOnly = true)
            EditScope.REVIEW_QUEUE -> LibraryFilter(unreviewedOrUntagged = true)
            EditScope.ALL -> LibraryFilter()
        }

    private companion object {
        val SORT = LibrarySort()

        /**
         * Max concurrently processed documents (each runs its single `suggestEdits` LLM call).
         * Bounds load on the model/backend while still overlapping the mostly-IO-bound calls for a
         * large speedup over serial execution.
         */
        const val SUGGEST_CONCURRENCY = 4

        /** Max characters of extracted text kept for a Review row's preview snippet. */
        const val SNIPPET_LENGTH = 200
    }
}
