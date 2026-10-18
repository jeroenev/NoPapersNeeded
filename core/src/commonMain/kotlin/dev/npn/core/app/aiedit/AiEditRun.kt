package dev.npn.core.app.aiedit

import dev.npn.core.ai.AiEditRequest
import dev.npn.core.ai.AiSuggest
import dev.npn.core.app.Doc
import dev.npn.core.app.DocContentFacade
import dev.npn.core.app.DocListItem
import dev.npn.core.app.DocumentRepository
import dev.npn.core.app.FieldRegistry
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySort
import dev.npn.core.app.MetadataWriter
import dev.npn.core.app.SavedView
import dev.npn.core.app.TagRepository
import dev.npn.core.app.displayTitleOf
import dev.npn.core.app.reviewQueueFilter
import dev.npn.core.model.ReviewMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update

/**
 * Document scope for a bulk AI-edit run.
 *
 * [SELECTION] is only offered when the page was opened from the library's selection mode;
 * [MISSING_FIELDS] targets documents lacking at least one of the fields the run autofills;
 * [SAVED_VIEW] reuses a stored library view (its filter *and* its sort).
 */
enum class EditScope { SELECTION, UNTAGGED, REVIEW_QUEUE, MISSING_FIELDS, SAVED_VIEW, ALL }

/** Everything that changes *which* documents a run would touch. */
data class ScopeKey(
    val scope: EditScope,
    val savedView: SavedView?,
    /** Field keys whose absence selects a document for the MISSING_FIELDS scope. */
    val missing: List<String>,
    val batchSize: Int,
    /** The library's review mode, whose [reviewQueueFilter] the REVIEW_QUEUE scope lists. */
    val reviewMode: ReviewMode,
)

/**
 * Suggestion progress, including structured identity for the most recently completed document.
 */
data class EditProgress(
    val done: Int,
    val total: Int,
    val currentTitle: String?,
    val currentFileName: String,
) {
    /** Same fallback as [Doc.displayTitle]. */
    val currentDisplayTitle: String get() = displayTitleOf(currentTitle, currentFileName)
}

/** Outcome of suggesting over the next batch of the scope. */
sealed interface BatchResult {
    /** The scope has no unprocessed documents left; nothing was requested. */
    data object Empty : BatchResult

    /**
     * Suggestions for review: [docs] carry at least one suggestion, [emptyDocs] came back empty.
     * [failures] of [total] documents could not be suggested (they are not marked processed and
     * will be retried by the next batch).
     */
    data class Review(
        val docs: List<ReviewDoc>,
        val emptyDocs: List<ReviewDoc>,
        val failures: Int,
        val total: Int,
    ) : BatchResult
}

/**
 * One bulk AI-edit run over an opened library: scope resolution, bounded-concurrency suggestion
 * batches, review pre-selection policy and partial-failure apply semantics. Create one per edit
 * session and drive it batch by batch; the run remembers already-[processed] documents so
 * follow-up batches (and the caller's preview) exclude them.
 *
 * - [suggestNextBatch] runs [AiSuggest.suggestEdits] over the next [ScopeKey.batchSize] unprocessed
 *   documents with the bounded concurrency of [SuggestionRunner]; each document is ONE model
 *   request covering every requested section. Progress is reported as each document completes;
 *   results keep the original batch order. Cancellation propagates (nothing is written).
 * - Review pre-selection is [ReviewDoc.recommended], applied by [ReviewProjector].
 * - [apply] hands the batch to its [ReviewApply] (per-tag transactions, one `applyEdits` per
 *   document, failures as an [ApplyOutcome.Retry] without the succeeded items, which the batch never
 *   writes again, and the count of what earlier attempts of the batch wrote); the run adds whether
 *   the scope has documents left, and a failure to find out is the Done's
 *   [ApplyOutcome.Done.followUpFailure].
 *
 * [processed] marks every reviewed document (including no-suggestion ones) at review time, not on
 * apply, so deselecting or skipping a document never sends it back through the model. A run handed
 * to the background ([remainingDocs] + [markHandedOff]) counts the same way.
 *
 * An interface so the screen driving it can be tested against a scripted run; [AiEditRunImpl] is
 * the one implementation.
 */
interface AiEditRun {
    /** Documents already reviewed in this run; excluded from every later batch. */
    val processed: StateFlow<Set<String>>

    /**
     * Documents in scope, live. The library selection travels as the filter's ids, and an empty one
     * selects nothing; a saved view contributes its own sort, everything else uses the default sort.
     */
    fun docsFor(key: ScopeKey): Flow<List<DocListItem>>

    /** Whether the scope still has unprocessed docs (drives the "Next batch" affordance). */
    suspend fun moreRemain(key: ScopeKey): Boolean

    /**
     * Every document of the scope this run has not reviewed yet — the batch cap deliberately not
     * applied. This is what a run handed to the background covers: the point of queueing is that
     * nobody is waiting on the answer, so splitting it into review-sized batches would only make
     * the user come back for the rest.
     */
    suspend fun remainingDocs(key: ScopeKey): List<DocListItem>

    /**
     * Mark [docIds] reviewed by something other than this run's own batches — a background run
     * that took them — so the preview and any later batch skip documents already spent.
     */
    fun markHandedOff(docIds: List<String>)

    /**
     * Suggest edits for the next batch of the scope; see the class docs for concurrency, ordering
     * and failure semantics. [onProgress] fires once before the first request and then as each
     * document completes.
     */
    suspend fun suggestNextBatch(
        key: ScopeKey,
        request: AiEditRequest,
        onProgress: (EditProgress) -> Unit,
    ): BatchResult

    /** Write the selected suggestions of [docs]; see the class docs for partial-failure semantics. */
    suspend fun apply(
        key: ScopeKey,
        docs: List<ReviewDoc>,
    ): ApplyOutcome

    /**
     * What [apply] wrote of the current batch so far, across its attempts: what Done reports when
     * the user skips the rest of the batch. [ApplyResult.moreRemain] is false; [moreRemain] says.
     */
    fun landedSoFar(): ApplyResult
}

/** The [AiEditRun] over a library's facets. */
class AiEditRunImpl(
    private val documents: DocumentRepository,
    private val meta: MetadataWriter,
    content: DocContentFacade,
    ai: AiSuggest,
    fieldRegistry: FieldRegistry,
    /** The library's tag vocabulary, which decides whether a suggested tag is new. */
    tags: TagRepository,
    /** Documents handed over from the library's selection mode; empty when opened standalone. */
    private val selection: List<String> = emptyList(),
) : AiEditRun {
    /** The current batch's Review→journal step; each batch gets its own, so counts never carry over. */
    private var reviewApply = ReviewApply(meta)

    /** The shared pre-select policy, applied to each answered document. */
    private val projector = ReviewProjector(content, fieldRegistry, tags)

    private val runner = SuggestionRunner(ai)

    private val _processed = MutableStateFlow(emptySet<String>())
    override val processed: StateFlow<Set<String>> = _processed.asStateFlow()

    override fun docsFor(key: ScopeKey): Flow<List<DocListItem>> =
        when (key.scope) {
            EditScope.SELECTION -> {
                if (selection.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    documents.observeDocuments(LibraryFilter(docIds = selection), SORT)
                }
            }

            EditScope.SAVED_VIEW -> {
                key.savedView?.let { documents.observeDocuments(it.filter, it.sort) }
                    ?: flowOf(emptyList())
            }

            // No autofill field enabled means the "missing a chosen field" scope selects nothing,
            // rather than silently degrading into "all documents".
            EditScope.MISSING_FIELDS -> {
                if (key.missing.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    documents.observeDocuments(LibraryFilter(missingFields = key.missing), SORT)
                }
            }

            EditScope.UNTAGGED -> {
                documents.observeDocuments(LibraryFilter(untaggedOnly = true), SORT)
            }

            EditScope.REVIEW_QUEUE -> {
                documents.observeDocuments(key.reviewMode.reviewQueueFilter(), SORT)
            }

            EditScope.ALL -> {
                documents.observeDocuments(LibraryFilter(), SORT)
            }
        }

    override suspend fun moreRemain(key: ScopeKey): Boolean = nextBatchDocs(key).isNotEmpty()

    override suspend fun remainingDocs(key: ScopeKey): List<DocListItem> =
        docsFor(key).first().filterNot { it.doc.docId in _processed.value }

    override fun markHandedOff(docIds: List<String>) {
        _processed.update { it + docIds }
    }

    override suspend fun suggestNextBatch(
        key: ScopeKey,
        request: AiEditRequest,
        onProgress: (EditProgress) -> Unit,
    ): BatchResult {
        val batch = nextBatchDocs(key)
        if (batch.isEmpty()) return BatchResult.Empty
        val first = batch.first().doc
        onProgress(EditProgress(0, batch.size, first.title, first.fileName))
        var done = 0
        val outcomes =
            runner.suggest(
                batch.map { it.doc.docId },
                request,
                onEach = { index, _ ->
                    done++
                    val doc = batch[index].doc
                    onProgress(EditProgress(done, batch.size, doc.title, doc.fileName))
                },
            ) { index, suggestion -> projector.project(batch[index], suggestion) }
        return enterReview(outcomes.mapNotNull { it.getOrNull() }, outcomes.count { it.isFailure }, batch.size)
    }

    private fun enterReview(
        results: List<ReviewDoc>,
        failures: Int,
        total: Int,
    ): BatchResult.Review {
        // Mark EVERY reviewed doc (incl. no-suggestion ones) processed now, not on apply, so the
        // next batch never re-suggests docs the user already saw — deselecting or skipping a doc
        // must not send it back through the model on "Next batch".
        _processed.update { done -> done + results.map { it.docId } }
        reviewApply = ReviewApply(meta)
        val (withSuggestions, empty) = results.partition { it.hasSuggestions }
        return BatchResult.Review(withSuggestions, empty, failures, total)
    }

    override suspend fun apply(
        key: ScopeKey,
        docs: List<ReviewDoc>,
    ): ApplyOutcome =
        // Every document of the Review is processed already: enterReview marked them all.
        reviewApply.apply(docs, moreRemain = { moreRemain(key) })

    override fun landedSoFar(): ApplyResult = reviewApply.landedSoFar()

    // Taken as a one-shot snapshot rather than a live flow: a run continues (and finishes with a
    // correct moreRemain) while the caller's preview subscription is gone.
    private suspend fun nextBatchDocs(key: ScopeKey): List<DocListItem> = remainingDocs(key).take(key.batchSize)

    private companion object {
        val SORT = LibrarySort()
    }
}
