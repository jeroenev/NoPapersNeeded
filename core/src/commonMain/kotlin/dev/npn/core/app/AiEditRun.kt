package dev.npn.core.app

import dev.npn.core.ai.AiEditRequest
import dev.npn.core.ai.AiField
import dev.npn.core.ai.AiSuggest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

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
)

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
 * [fields] are the single-value autofill suggestions. Title and file name stay separate because the
 * review rows compare against the raw stored title; [displayTitle] is what to render.
 */
data class ReviewDoc(
    val docId: String,
    val title: String?,
    val fileName: String,
    val snippet: String?,
    val chips: List<ReviewChip>,
    val removals: List<ReviewChip>,
    val fields: List<ReviewField>,
) {
    /** Same fallback as [Doc.displayTitle]. */
    val displayTitle: String get() = displayTitleOf(title, fileName)
}

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

/** Outcome of applying the selected suggestions of a review batch. */
sealed interface ApplyOutcome {
    /** No selection was set; nothing was written. */
    data object NothingSelected : ApplyOutcome

    /** Every selected suggestion was written (counting earlier retry attempts of this batch). */
    data class Done(
        val result: ApplyResult,
    ) : ApplyOutcome

    /**
     * Some writes failed: [failedDocs] of [total] documents are retained in [remaining] with only
     * their actually-unapplied selections still selected, so a retry never re-writes a (doc, tag)
     * or (doc, field) that already succeeded.
     */
    data class Retry(
        val remaining: List<ReviewDoc>,
        val failedDocs: Int,
        val total: Int,
    ) : ApplyOutcome
}

/**
 * One bulk AI-edit run over an opened library: scope resolution, bounded-concurrency suggestion
 * batches, review pre-selection policy and partial-failure apply semantics. Create one per edit
 * session and drive it batch by batch; the run remembers already-[processed] documents so
 * follow-up batches (and the caller's preview) exclude them.
 *
 * - [suggestNextBatch] runs [AiSuggest.suggestEdits] over the next [ScopeKey.batchSize] unprocessed
 *   documents with bounded concurrency ([SUGGEST_CONCURRENCY]); each document is ONE model request
 *   covering every requested section. Progress is reported as each document completes; results are
 *   re-ordered to match the original batch order. Cancellation propagates (nothing is written).
 * - Review pre-selection is [ReviewProjector]'s: existing-vocabulary tag additions pre-selected,
 *   new tags and removals pre-deselected; field fill-ins pre-selected, overwrites pre-deselected.
 * - [apply] delegates the write and the retry rebuild to [ReviewApply] (per-tag transactions, one
 *   `applyEdits` per document, failures as an [ApplyOutcome.Retry] with succeeded items
 *   deselected) and adds only this run's own accounting: suggestions written by earlier attempts
 *   of the same batch, and whether the scope has documents left.
 *
 * [processed] marks every reviewed document (including no-suggestion ones) at review time, not on
 * apply, so deselecting or skipping a document never sends it back through the model. A run handed
 * to the background ([remainingDocs] + [markHandedOff]) counts the same way.
 */
class AiEditRun(
    private val documents: DocumentRepository,
    private val meta: MetadataWriter,
    private val content: DocContentFacade,
    private val ai: AiSuggest,
    /** Documents handed over from the library's selection mode; empty when opened standalone. */
    private val selection: List<String> = emptyList(),
) {
    /** The shared Review→journal step; this run adds only its own cross-batch accounting. */
    private val reviewApply = ReviewApply(meta)

    /** The shared pre-select policy, applied to each answered document. */
    private val projector = ReviewProjector(content)

    /** Documents already reviewed in this run; excluded from every later batch. */
    private val _processed = MutableStateFlow(emptySet<String>())
    val processed: StateFlow<Set<String>> = _processed.asStateFlow()

    /** Suggestions written across earlier partial-failure apply attempts of the current batch. */
    private var appliedSoFar = 0

    /**
     * Documents in scope, live. The library selection is intersected in memory (ids do not travel
     * through the filter, and the list is already materialized for the library screen anyway); a
     * saved view contributes its own sort, everything else uses the default [SORT].
     */
    fun docsFor(key: ScopeKey): Flow<List<DocListItem>> =
        when (key.scope) {
            EditScope.SELECTION -> {
                val ids = selection.toSet()
                documents
                    .observeDocuments(LibraryFilter(), SORT)
                    .map { docs -> docs.filter { it.doc.docId in ids } }
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
                documents.observeDocuments(LibraryFilter(unreviewedOrUntagged = true), SORT)
            }

            EditScope.ALL -> {
                documents.observeDocuments(LibraryFilter(), SORT)
            }
        }

    /** Whether the scope still has unprocessed docs (drives the "Next batch" affordance). */
    suspend fun moreRemain(key: ScopeKey): Boolean = nextBatchDocs(key).isNotEmpty()

    /**
     * Every document of the scope this run has not reviewed yet — the batch cap deliberately not
     * applied. This is what a run handed to the background covers: the point of queueing is that
     * nobody is waiting on the answer, so splitting it into review-sized batches would only make
     * the user come back for the rest.
     */
    suspend fun remainingDocs(key: ScopeKey): List<DocListItem> =
        docsFor(key).first().filterNot { it.doc.docId in _processed.value }

    /**
     * Mark [docIds] reviewed by something other than this run's own batches — a background run
     * that took them — so the preview and any later batch skip documents already spent.
     */
    fun markHandedOff(docIds: List<String>) {
        _processed.update { it + docIds }
    }

    /**
     * Suggest edits for the next batch of the scope; see class docs for concurrency, ordering and
     * failure semantics. [onProgress] fires once before the first request and then as each
     * document completes.
     */
    suspend fun suggestNextBatch(
        key: ScopeKey,
        request: AiEditRequest,
        onProgress: (EditProgress) -> Unit,
    ): BatchResult {
        val batch = nextBatchDocs(key)
        if (batch.isEmpty()) return BatchResult.Empty
        val first = batch.first().doc
        onProgress(EditProgress(0, batch.size, first.title, first.fileName))
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
                                    projector.project(item, ai.suggestEdits(item.doc.docId, request))
                                }
                            // CancellationException must propagate so the whole scope tears down.
                            outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                            progress.withLock {
                                outcome
                                    .onSuccess { results[index] = it }
                                    .onFailure { failures += item.doc.docId }
                                done++
                                onProgress(EditProgress(done, batch.size, item.doc.title, item.doc.fileName))
                            }
                        }
                    }
                }.awaitAll()
        }
        return enterReview(results.filterNotNull(), failures.size, batch.size)
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
        appliedSoFar = 0
        val (withSuggestions, empty) =
            results.partition {
                it.chips.isNotEmpty() || it.removals.isNotEmpty() ||
                    it.fields.isNotEmpty()
            }
        return BatchResult.Review(withSuggestions, empty, failures, total)
    }

    /** Write the selected suggestions of [docs]; see class docs for partial-failure semantics. */
    suspend fun apply(
        key: ScopeKey,
        docs: List<ReviewDoc>,
    ): ApplyOutcome {
        // The selection projection, the write grain and the retry rebuild all live in the shared
        // ReviewApply; what stays here is this run's own accounting — the suggestions written by
        // earlier attempts of the same batch, and whether the scope has more documents left.
        //
        // _processed is Review state, so it stays this run's to keep: onDocWritten marks each
        // document as its grouped op commits, matching the previous per-op update timing.
        return when (val outcome = reviewApply.apply(docs) { docId -> _processed.update { it + docId } }) {
            ReviewOutcome.NothingSelected -> {
                ApplyOutcome.NothingSelected
            }

            is ReviewOutcome.Written -> {
                ApplyOutcome.Done(
                    // Include suggestions written in earlier partial-failure attempts of this batch.
                    ApplyResult(outcome.applied + appliedSoFar, outcome.docsChanged, moreRemain(key)),
                )
            }

            is ReviewOutcome.Retry -> {
                // Carry the succeeded count forward so it is not lost when Done is finally produced.
                appliedSoFar += outcome.applied
                ApplyOutcome.Retry(outcome.remaining, outcome.failedDocs, outcome.total)
            }
        }
    }

    // Taken as a one-shot snapshot rather than a live flow: a run continues (and finishes with a
    // correct moreRemain) while the caller's preview subscription is gone.
    private suspend fun nextBatchDocs(key: ScopeKey): List<DocListItem> = remainingDocs(key).take(key.batchSize)

    private companion object {
        val SORT = LibrarySort()

        /**
         * Max concurrently processed documents (each runs its single `suggestEdits` LLM call).
         * Bounds load on the model/backend while still overlapping the mostly-IO-bound calls for a
         * large speedup over serial execution.
         */
        const val SUGGEST_CONCURRENCY = 12
    }
}
