package dev.npn.app.ui.ai

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.UiAction
import dev.npn.app.ui.common.UiTextKey
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.app.ui.common.uiText
import dev.npn.core.ai.TagSuggestion
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

/** Document scope for a bulk tagging run. */
enum class TaggingScope { UNTAGGED, REVIEW_QUEUE, ALL }

/**
 * One suggested (doc, tag) pairing in the review phase. [selected] drives whether Apply writes it;
 * pre-selected for existing-vocabulary tags, pre-deselected for [isNew] ones.
 */
data class ReviewChip(
    val tag: String,
    val isNew: Boolean,
    val selected: Boolean,
)

/**
 * One document's row in the review phase. Title and file name stay separate so display fallback
 * and future localization remain UI concerns.
 */
data class ReviewDoc(
    val docId: String,
    val title: String?,
    val fileName: String,
    val snippet: String?,
    val chips: List<ReviewChip>,
)

/**
 * Suggestion progress, including structured identity for the most recently completed document.
 */
data class SuggestProgress(
    val done: Int,
    val total: Int,
    val currentTitle: String?,
    val currentFileName: String,
)

/**
 * Terminal counts after Apply: [tagsAdded] tags written across [docsTagged] documents, plus whether
 * more documents remain in the scope for a follow-up batch.
 */
data class ApplyResult(
    val tagsAdded: Int,
    val docsTagged: Int,
    val moreRemain: Boolean,
)

/** The five phases of the bulk-tagging flow (plan section E). */
sealed interface TaggingPhase {
    /** Scope selection + Start. */
    data object Idle : TaggingPhase

    /** Concurrent `suggestTags` calls in progress. */
    data class Suggesting(
        val progress: SuggestProgress,
    ) : TaggingPhase

    /** Per-doc suggestion chips awaiting the user's confirmation; nothing written yet. */
    data class Review(
        val docs: List<ReviewDoc>,
        val emptyDocs: List<ReviewDoc>,
    ) : TaggingPhase

    /** Writing selected tags through the journal. */
    data object Applying : TaggingPhase

    /** Terminal state carrying counts + whether a next batch is available. */
    data class Done(
        val result: ApplyResult,
    ) : TaggingPhase
}

/**
 * Full UI state. [scope] and [reviewQueueAvailable] drive the Idle controls; [phase] is the current
 * step. Selection toggles live inside [TaggingPhase.Review.docs].
 *
 * [maxTags], [maxNewTags] and [batchSize] are per-run overrides, seeded from the Settings defaults
 * and editable in the Idle phase; they parametrize the suggestion calls and the batch size.
 */
data class AiTaggingUiState(
    val scope: TaggingScope = TaggingScope.UNTAGGED,
    val reviewQueueAvailable: Boolean = false,
    val phase: TaggingPhase = TaggingPhase.Idle,
    val maxTags: Int = AiTagging.DEFAULT_TAGS_PER_DOC,
    val maxNewTags: Int = AiTagging.DEFAULT_NEW_TAGS_PER_DOC,
    val batchSize: Int = AiTagging.DEFAULT_BATCH_DOCS,
)

/**
 * Drives the bulk AI tagging screen for one opened library. State machine (plan section E):
 * `Idle → Suggesting → Review → Applying → Done`.
 *
 * - Idle: pick a [TaggingScope] and optionally tweak the per-run overrides ([AiTaggingUiState.maxTags],
 *   [AiTaggingUiState.maxNewTags], [AiTaggingUiState.batchSize]), which are seeded from the Settings
 *   defaults. The document count for the current scope is observed via [scopeCount]. Start runs
 *   [dev.npn.core.ai.AiFacade.suggestTags] calls over the first [AiTaggingUiState.batchSize] docs with
 *   bounded concurrency ([SUGGEST_CONCURRENCY]), passing the tag overrides, reporting progress as
 *   each doc completes; cancellable via the shared [run] job and by leaving the screen
 *   (viewModelScope). Results are re-ordered to match the original batch order.
 * - Review: per-doc suggestion chips, existing tags pre-selected and new tags pre-deselected; tap
 *   toggles. Docs that produced no suggestions land in a collapsed group. Nothing is written here.
 * - Apply: selected (doc, tag) pairs are grouped by tag and written with one `addTag` journal call
 *   per distinct tag (a multi-doc op list committed in a single transaction); per-tag failures are
 *   collected and surfaced, successes are not rolled back (mirrors the bulk-op convention). Failed
 *   docs keep their unapplied selections so the user can retry.
 *
 * [processedIds] remembers already-tagged docs for the session so the "next batch" continuation
 * excludes them (needed for ALL / review-queue scopes; UNTAGGED docs drop out of the scope query on
 * their own once tagged).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions")
class AiTaggingViewModel(
    val session: LibrarySession,
    private val settings: SettingsRepository,
) : MessagesViewModel() {
    private val scope = MutableStateFlow(TaggingScope.UNTAGGED)
    private val _ui = MutableStateFlow(AiTaggingUiState())
    val ui: StateFlow<AiTaggingUiState> = _ui.asStateFlow()

    /** Live document count for the currently selected scope (drives the Idle "N documents" line). */
    val scopeCount: StateFlow<Int> =
        scope
            .flatMapLatest { session.documents.observeCount(scopeFilter(it)) }
            .stateIn(viewModelScope, WhileSubscribedTimeout, 0)

    private val processedIds = mutableSetOf<String>()

    /** Tags written across earlier partial-failure apply attempts of the current Review batch. */
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
    fun setScope(scope: TaggingScope) {
        if (_ui.value.phase != TaggingPhase.Idle) return
        if (scope == TaggingScope.REVIEW_QUEUE && !_ui.value.reviewQueueAvailable) return
        this.scope.value = scope
        _ui.update { it.copy(scope = scope) }
    }

    /** Set the per-run max suggested tags per document (Idle only); clamped to [AiTagging.RANGE]. */
    fun setMaxTags(value: Int) {
        if (_ui.value.phase != TaggingPhase.Idle) return
        _ui.update { it.copy(maxTags = value.coerceIn(AiTagging.RANGE)) }
    }

    /** Set the per-run max new tags per document (Idle only); clamped to [AiTagging.NEW_TAGS_RANGE]. */
    fun setMaxNewTags(value: Int) {
        if (_ui.value.phase != TaggingPhase.Idle) return
        _ui.update { it.copy(maxNewTags = value.coerceIn(AiTagging.NEW_TAGS_RANGE)) }
    }

    /** Set the per-run batch size (Idle only); clamped to [AiTagging.BATCH_RANGE]. */
    fun setBatchSize(value: Int) {
        if (_ui.value.phase != TaggingPhase.Idle) return
        _ui.update { it.copy(batchSize = value.coerceIn(AiTagging.BATCH_RANGE)) }
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
        _ui.update { it.copy(phase = TaggingPhase.Idle) }
    }

    /** Toggle whether a doc's tag chip is selected for Apply (Review phase only). */
    fun toggleChip(
        docId: String,
        tag: String,
    ) = _ui.update { state ->
        val review = state.phase as? TaggingPhase.Review ?: return@update state
        val docs =
            review.docs.map { doc ->
                if (doc.docId != docId) {
                    doc
                } else {
                    doc.copy(
                        chips =
                            doc.chips.map { chip ->
                                if (chip.tag == tag) chip.copy(selected = !chip.selected) else chip
                            },
                    )
                }
            }
        state.copy(phase = review.copy(docs = docs))
    }

    /** Select or deselect every chip of one document (Review phase only; no-op otherwise). */
    fun setDocSelection(
        docId: String,
        selected: Boolean,
    ) = _ui.update { state ->
        val review = state.phase as? TaggingPhase.Review ?: return@update state
        val docs =
            review.docs.map { doc ->
                if (doc.docId != docId) doc else doc.copy(chips = doc.chips.map { it.copy(selected = selected) })
            }
        state.copy(phase = review.copy(docs = docs))
    }

    /** Select or deselect every chip of every document (Review phase only; no-op otherwise). */
    fun setAllSelection(selected: Boolean) =
        _ui.update { state ->
            val review = state.phase as? TaggingPhase.Review ?: return@update state
            val docs =
                review.docs.map { doc ->
                    doc.copy(chips = doc.chips.map { it.copy(selected = selected) })
                }
            state.copy(phase = review.copy(docs = docs))
        }

    /** Write the selected (doc, tag) pairs; see class docs for partial-failure semantics. */
    fun apply() {
        val review = _ui.value.phase as? TaggingPhase.Review ?: return
        if (run?.isActive == true) return
        run = viewModelScope.launch { applySelections(review) }
    }

    /** From a Done state, run the next batch over the same scope, excluding processed docs. */
    fun nextBatch() {
        if (_ui.value.phase !is TaggingPhase.Done) return
        start()
    }

    private suspend fun suggestBatch() {
        // Snapshot the per-run overrides once so mid-run state changes cannot affect this batch.
        val maxTags = _ui.value.maxTags
        val maxNewTags = _ui.value.maxNewTags
        val batch = nextBatchDocs()
        if (batch.isEmpty()) {
            _ui.update { it.copy(phase = TaggingPhase.Done(ApplyResult(0, 0, moreRemain = false))) }
            return
        }
        _ui.update {
            val first = batch.first().doc
            it.copy(
                phase =
                    TaggingPhase.Suggesting(
                        SuggestProgress(0, batch.size, first.title, first.fileName),
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
                                    val suggestions = session.ai.suggestTags(item.doc.docId, maxTags, maxNewTags)
                                    reviewDoc(item, suggestions, snippetFor(item.doc.docId))
                                }
                            // CancellationException must propagate so the whole scope tears down.
                            outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                            progress.withLock {
                                outcome
                                    .onSuccess { results[index] = it }
                                    .onFailure { failures += item.doc.docId }
                                done++
                                val prog = SuggestProgress(done, batch.size, item.doc.title, item.doc.fileName)
                                _ui.update { s -> s.copy(phase = TaggingPhase.Suggesting(prog)) }
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
        val (withChips, empty) = results.partition { it.chips.isNotEmpty() }
        _ui.update { it.copy(phase = TaggingPhase.Review(withChips, empty)) }
    }

    /**
     * Leave Review without writing anything: jump straight to Done with zero counts and the
     * correct moreRemain (reviewed docs are already in [processedIds], so they are excluded).
     */
    fun skipReview() {
        if (_ui.value.phase !is TaggingPhase.Review) return
        if (run?.isActive == true) return
        run =
            viewModelScope.launch {
                _ui.update { it.copy(phase = TaggingPhase.Done(ApplyResult(0, 0, moreRemain()))) }
            }
    }

    private suspend fun applySelections(review: TaggingPhase.Review) {
        _ui.update { it.copy(phase = TaggingPhase.Applying) }
        val pending =
            review.docs.flatMap { doc ->
                doc.chips.filter { it.selected }.map { doc.docId to it.tag }
            }
        if (pending.isEmpty()) {
            _ui.update { it.copy(phase = TaggingPhase.Review(review.docs, review.emptyDocs)) }
            return
        }
        val failedDocs = mutableSetOf<String>()
        val succeeded = mutableSetOf<Pair<String, String>>()
        // Group by tag so each distinct tag is written once for all its docs in a single journal
        // transaction (MetadataWriter.addTag commits a multi-doc op list atomically). A tag's call
        // is all-or-nothing: on failure every (doc, tag) pair for that tag is retained for retry.
        val byTag = pending.groupBy({ it.second }, { it.first })
        for ((tag, docIds) in byTag) {
            runCatching { session.meta.addTag(docIds, tag) }
                .onSuccess {
                    docIds.forEach { docId ->
                        succeeded += docId to tag
                        processedIds += docId
                    }
                }.onFailure { failedDocs += docIds }
        }
        finishApply(review, succeeded, failedDocs)
    }

    private suspend fun finishApply(
        review: TaggingPhase.Review,
        succeeded: Set<Pair<String, String>>,
        failedDocs: Set<String>,
    ) {
        if (failedDocs.isEmpty()) {
            // Include tags written in earlier partial-failure attempts of this same batch.
            val totalWritten = succeeded.size + appliedSoFar
            val taggedDocs = succeeded.map { it.first }.toSet().size
            val result = ApplyResult(totalWritten, taggedDocs, moreRemain())
            _ui.update { it.copy(phase = TaggingPhase.Done(result)) }
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
        // Keep only the actually-unapplied (doc, tag) selections on the retained failed rows, so a
        // retry never re-writes a pair that already succeeded. Carry the succeeded count forward so
        // it is not lost when the eventual Done result is produced.
        val remaining =
            review.docs
                .filter { it.docId in failedDocs }
                .map { doc ->
                    doc.copy(
                        chips =
                            doc.chips.map { chip ->
                                if (chip.selected && (doc.docId to chip.tag) in succeeded) {
                                    chip.copy(selected = false)
                                } else {
                                    chip
                                }
                            },
                    )
                }
        appliedSoFar += succeeded.size
        _ui.update { it.copy(phase = TaggingPhase.Review(remaining, review.emptyDocs)) }
    }

    private suspend fun nextBatchDocs(): List<DocListItem> =
        currentScopeDocs().filter { it.doc.docId !in processedIds }.take(_ui.value.batchSize)

    /** Whether the scope still has unprocessed docs (drives the "Next batch" affordance). */
    private suspend fun moreRemain(): Boolean = nextBatchDocs().isNotEmpty()

    private suspend fun currentScopeDocs(): List<DocListItem> =
        session.documents.observeDocuments(scopeFilter(scope.value), SORT).first()

    private fun reviewDoc(
        item: DocListItem,
        suggestions: List<TagSuggestion>,
        snippet: String?,
    ): ReviewDoc =
        ReviewDoc(
            docId = item.doc.docId,
            title = item.doc.title,
            fileName = item.doc.fileName,
            snippet = snippet,
            chips = suggestions.map { ReviewChip(it.tag, it.isNew, selected = !it.isNew) },
        )

    private fun scopeFilter(scope: TaggingScope): LibraryFilter =
        when (scope) {
            TaggingScope.UNTAGGED -> LibraryFilter(untaggedOnly = true)
            TaggingScope.REVIEW_QUEUE -> LibraryFilter(unreviewedOrUntagged = true)
            TaggingScope.ALL -> LibraryFilter()
        }

    private companion object {
        val SORT = LibrarySort()

        /**
         * Max in-flight `suggestTags` (LLM) calls. Bounds load on the model/backend while still
         * overlapping the mostly-IO-bound calls for a large speedup over serial execution.
         */
        const val SUGGEST_CONCURRENCY = 4

        /** Max characters of extracted text kept for a Review row's preview snippet. */
        const val SNIPPET_LENGTH = 200
    }
}
