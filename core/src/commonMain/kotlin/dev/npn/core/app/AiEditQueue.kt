package dev.npn.core.app

import dev.npn.core.ai.AiConfig
import dev.npn.core.ai.AiEditRequest
import dev.npn.core.ai.AiEditSuggestion
import dev.npn.core.ai.AiSuggest
import dev.npn.core.db.AiEditJobDao
import dev.npn.core.db.AiEditJobDocEntity
import dev.npn.core.db.AiEditJobEntity
import dev.npn.core.journal.EpochClock
import dev.npn.core.util.NpnJson
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.work.backoffMs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A finished queued Edit Run, opened for review. The queued counterpart of one
 * [BatchResult.Review]: the same rows, the same pre-select policy, the same partial-failure
 * semantics — only the suggestions were paid for earlier and by a background drain.
 *
 * The run is deleted from the queue as soon as it stops being reviewable: a fully applied [apply],
 * or an explicit [discard] when the user walks away without writing. Nothing else removes it, so
 * closing the app mid-review costs nothing.
 *
 * Reopening a run that was left behind after a partial-failure [apply] re-projects the stored
 * suggestions against the documents' *current* metadata, so items already written on the earlier
 * attempt can be offered again. That is safe — writing them again is a no-op — but the run only
 * leaves the queue once the remaining selection is written or the review is discarded.
 */
interface EditRunReview {
    val jobId: String

    /** Documents with at least one suggestion, in the order they were submitted. */
    val docs: List<ReviewDoc>

    /** Documents the model had nothing to say about; shown collapsed, never written. */
    val emptyDocs: List<ReviewDoc>

    /** Documents given up on after repeated model failures. */
    val failures: Int

    /**
     * Write the selected suggestions of [reviewed]. Identical semantics to [AiEditRun.apply] —
     * per-tag transactions, failures as an [ApplyOutcome.Retry] with succeeded items deselected —
     * except that a queued run has no scope left to continue into, so [ApplyResult.moreRemain] is
     * always false. A fully applied run is finished, and is dropped from the queue.
     */
    suspend fun apply(reviewed: List<ReviewDoc>): ApplyOutcome

    /** Leave the review without writing anything, dropping the run from the queue. */
    suspend fun discard()
}

internal class QueuedEditReview(
    override val jobId: String,
    override val docs: List<ReviewDoc>,
    override val emptyDocs: List<ReviewDoc>,
    override val failures: Int,
    private val reviewApply: ReviewApply,
    private val onFinished: suspend () -> Unit,
) : EditRunReview {
    /** Suggestions written across earlier partial-failure apply attempts of this review. */
    private var appliedSoFar = 0

    override suspend fun apply(reviewed: List<ReviewDoc>): ApplyOutcome =
        when (val outcome = reviewApply.apply(reviewed)) {
            ReviewOutcome.NothingSelected -> {
                ApplyOutcome.NothingSelected
            }

            is ReviewOutcome.Written -> {
                onFinished()
                ApplyOutcome.Done(
                    ApplyResult(outcome.applied + appliedSoFar, outcome.docsChanged, moreRemain = false),
                )
            }

            is ReviewOutcome.Retry -> {
                appliedSoFar += outcome.applied
                ApplyOutcome.Retry(outcome.remaining, outcome.failedDocs, outcome.total)
            }
        }

    override suspend fun discard() = onFinished()
}

/** Handing bulk AI edits to the background, and picking their results back up. */
interface AiEditQueueFacade {
    /**
     * Queue an Edit Run over [docIds] with [request], returning its job id. Only records the
     * intent: every model call happens later on the queue's own drain, so this returns as fast as
     * a database write and the caller can leave the screen.
     */
    suspend fun enqueueEditRun(
        docIds: List<String>,
        request: AiEditRequest,
    ): String

    /**
     * The finished run [jobId] projected onto Review rows against the documents' *current*
     * metadata, or null when the run is gone or still owes model calls. Callers reach this from
     * the processing queue, which only offers finished runs.
     */
    suspend fun openEditRun(jobId: String): EditRunReview?

    /** Drop a queued run and its suggestions, whether or not it ever finished. */
    suspend fun discardEditRun(jobId: String)
}

/**
 * Bulk AI edits the user handed to the background, run as a persisted queue.
 *
 * ## Why a queue
 *
 * The suggestion phase of an Edit Run is one model call per document, and a run over a few hundred
 * documents takes long enough that holding a screen open for it is the wrong shape. Queueing turns
 * it into work the app owes: the run survives leaving the page, the endpoint being down, the
 * network being off and the process being killed, and its answers wait on the processing queue
 * until the user opens them.
 *
 * The result is *not* applied unattended. This queue only buys the expensive half — the model calls
 * — and stores each answer verbatim; the Review, its pre-select policy and every write still go
 * through the same human confirmation as an interactive run. That is the whole difference between
 * this and [AiAutoEnricher], which is allowed to write because its policy is fixed and
 * conservative.
 *
 * ## Failure: systemic versus per-document
 *
 * [processNext] works one chunk of at most [CHUNK_DOCS] documents per step, concurrently. What it
 * does about failure depends on how much of the chunk failed, because the two causes want opposite
 * treatment:
 *
 * - **Part of the chunk failed** — the endpoint is clearly alive, so this is about those documents.
 *   They carry the attempt, and at [MAX_DOC_ATTEMPTS] they are given up on and reported as
 *   failures. Without that cap one document the endpoint always rejects would keep a finished run
 *   from ever becoming reviewable.
 * - **The whole chunk failed** — overwhelmingly an endpoint down, no network, bad credentials.
 *   Nothing is held against the documents (charging them attempts would let one afternoon of
 *   airplane mode silently destroy a run the user is waiting on); the *job* backs off exponentially
 *   and the drain parks for [AiAutoEnricher.FAILURE_COOLDOWN_MS], which caps an outage at one
 *   chunk a minute however many runs are queued.
 *
 * That second rule cannot hold forever, because "the whole chunk failed" stops being evidence of
 * anything once the chunk is one document: the last poison document of a run is always alone in
 * its chunk, and a run whose documents are never charged is a run that never becomes reviewable.
 * So the systemic reading is a *budget*: for [MAX_JOB_ATTEMPTS] backed-off retries a total failure
 * is read as an outage and costs the documents nothing, and after that it is charged like any
 * other failure. With the shared backoff ramp that buys roughly half an hour of downtime for free
 * and still clears a permanently-rejected document within a few hours.
 *
 * A chunk that made any progress clears the job's backoff *and* its budget, so a run recovers on
 * its next kick rather than serving out a delay earned while the network was down.
 *
 * ## Triggering
 *
 * Queueing a run asks for its drain through [onEnqueued], so a queued run never waits for the
 * periodic tick, whoever holds the queue. The queue writes run through [ownWrite].
 */
class AiEditQueue(
    private val dao: AiEditJobDao,
    private val documents: DocumentRepository,
    private val content: DocContentFacade,
    private val meta: MetadataWriter,
    private val ai: AiSuggest,
    private val fieldRegistry: FieldRegistry,
    private val readConfig: suspend () -> AiConfig,
    private val clock: EpochClock,
    /** Asks for a drain of this queue; called once a run is queued. */
    private val onEnqueued: () -> Unit,
    /** Runs the queue's writes as work the session owns, so closing it waits for them. */
    private val ownWrite: suspend (suspend () -> Unit) -> Unit = { it() },
    private val newJobId: () -> String = ::randomJobId,
) : AiEditQueueFacade {
    private val projector = ReviewProjector(content, fieldRegistry)
    private val reviewApply = ReviewApply(meta)

    /**
     * Serialises the drain against the user's cancels. The drain steps themselves are already
     * single-flight via the [dev.npn.core.work.QueueDrainer] wrapping [processNext] in the
     * session, but that mutex does not reach [discardEditRun]: without this lock a discard could
     * slip between the drain reading a job and its write-back, and the write-back would recreate
     * a row the user explicitly deleted. Holding it across the model calls costs nothing — the
     * drain is already strictly serial there — and it makes discarding authoritative: the delete
     * always lands after the in-flight chunk has finished writing, so a discarded run never comes
     * back.
     */
    private val drainLock = Mutex()

    /**
     * When the next chunk may be attempted after a systemic failure. In memory rather than on a
     * row, for the same reason as [AiAutoEnricher.cooldownUntil]: it is a statement about the
     * endpoint, not about a run, and an app relaunch is the one moment where retrying immediately
     * is most likely to be right. Only touched from inside [processNext], which [drainLock]
     * serialises.
     */
    private var cooldownUntil = 0L

    override suspend fun enqueueEditRun(
        docIds: List<String>,
        request: AiEditRequest,
    ): String {
        require(docIds.isNotEmpty()) { "a queued edit run needs at least one document" }
        val jobId = newJobId()
        ownWrite { insertRun(jobId, docIds, request) }
        // Queueing is a local write; the kick is what turns it into work, so the two belong together.
        onEnqueued()
        return jobId
    }

    private suspend fun insertRun(
        jobId: String,
        docIds: List<String>,
        request: AiEditRequest,
    ) {
        val now = clock.nowMillis()
        dao.upsertJob(
            AiEditJobEntity(
                jobId = jobId,
                requestJson = json.encodeToString(request),
                createdAt = now,
                updatedAt = now,
                nextAttemptAt = now,
            ),
        )
        // Duplicates would each cost a model call and show up twice in the Review.
        dao.upsertDocs(
            docIds.distinct().mapIndexed { index, docId ->
                AiEditJobDocEntity(jobId = jobId, docId = docId, position = index)
            },
        )
    }

    override suspend fun openEditRun(jobId: String): EditRunReview? {
        val rows = dao.docsFor(jobId).takeIf { it.isNotEmpty() } ?: return null
        // Strictly finished runs only: applying deletes the run, so opening one that is still
        // being suggested would throw away the answers that had not arrived yet.
        if (rows.any { it.isPending }) return null
        val answered = rows.mapNotNull { row -> row.suggestion()?.let { row.docId to it } }
        val items = liveItems(answered.map { it.first })
        val reviewed =
            answered.mapNotNull { (docId, suggestion) ->
                // A document trashed or deleted since it was queued simply drops out; its
                // suggestion is moot and offering it would write to something the user removed.
                items[docId]?.let { projector.project(it, suggestion) }
            }
        val (withSuggestions, empty) = reviewed.partition { it.hasSuggestions }
        return QueuedEditReview(
            jobId = jobId,
            docs = withSuggestions,
            emptyDocs = empty,
            failures = rows.count { it.failed },
            reviewApply = reviewApply,
            onFinished = { dao.deleteJob(jobId) },
        )
    }

    override suspend fun discardEditRun(jobId: String) = ownWrite { drainLock.withLock { dao.deleteJob(jobId) } }

    /**
     * Suggest one chunk of the oldest due run, returning whether the drain should continue. See the
     * class docs for the systemic-versus-per-document failure split; a systemic failure returns
     * false, parking the drain until the next kick rather than firing every remaining chunk at a
     * dead endpoint.
     */
    suspend fun processNext(): Boolean =
        drainLock.withLock {
            val job = dueJob() ?: return@withLock false
            val request = job.request() ?: return@withLock dropUndecodableJob(job)
            suggestNextChunk(job, request)
        }

    /**
     * The run to work on now, or null when there is nothing to do: the endpoint is unconfigured,
     * a systemic failure is still being waited out, or no run has a document left to suggest.
     */
    private suspend fun dueJob(): AiEditJobEntity? {
        if (!readConfig().configured) return null
        val now = clock.nowMillis()
        if (now < cooldownUntil) return null
        return dao.nextDueJob(now)
    }

    private suspend fun suggestNextChunk(
        job: AiEditJobEntity,
        request: AiEditRequest,
    ): Boolean {
        val pending = dao.nextPendingDocs(job.jobId, CHUNK_DOCS)
        if (pending.isEmpty()) return true
        val items = liveItems(pending.map { it.docId })
        // Documents trashed or deleted since the run was queued are dropped rather than asked
        // about: the answer could not be applied to anything.
        val (live, gone) = pending.partition { it.docId in items }
        if (gone.isNotEmpty()) dao.deleteDocs(job.jobId, gone.map { it.docId })
        if (live.isEmpty()) return dropIfEmptied(job)
        return recordChunk(job, live, suggestChunk(live, request))
    }

    /** One model call per document, bounded by the chunk size, each result kept with its row. */
    private suspend fun suggestChunk(
        rows: List<AiEditJobDocEntity>,
        request: AiEditRequest,
    ): List<Result<AiEditSuggestion>> =
        coroutineScope {
            rows
                .map { row ->
                    // Cancellation tears the whole scope down; it is not a failed suggestion.
                    async { runCatchingCancellable { ai.suggestEdits(row.docId, request) } }
                }.awaitAll()
        }

    /** Persist a chunk's outcomes and decide what its failures mean; see the class docs. */
    private suspend fun recordChunk(
        job: AiEditJobEntity,
        rows: List<AiEditJobDocEntity>,
        outcomes: List<Result<AiEditSuggestion>>,
    ): Boolean {
        val allFailed = outcomes.all { it.isFailure }
        // Read a total failure as an outage only while the job still has budget for that reading;
        // past it the documents are charged, or a run whose last document is poison never ends.
        if (allFailed && job.attempts < MAX_JOB_ATTEMPTS) {
            parkJob(job, outcomes.firstCause())
            return false
        }
        dao.upsertDocs(rows.mapIndexed { index, row -> row.recording(outcomes[index]) })
        if (allFailed) {
            // Charged above, but still nothing to carry on with right now.
            parkJob(job, outcomes.firstCause())
            return false
        }
        // Progress means the endpoint is alive, so the backoff and the systemic budget this job
        // spent while it was not are both stale; clearing them lets the next kick carry straight on.
        val now = clock.nowMillis()
        dao.upsertJob(job.copy(attempts = 0, nextAttemptAt = now, updatedAt = now, lastError = null))
        cooldownUntil = 0L
        return true
    }

    private fun List<Result<AiEditSuggestion>>.firstCause(): Throwable? = firstNotNullOfOrNull { it.exceptionOrNull() }

    private fun AiEditJobDocEntity.recording(outcome: Result<AiEditSuggestion>): AiEditJobDocEntity =
        outcome.fold(
            onSuccess = { copy(suggestionJson = json.encodeToString(it), lastError = null) },
            onFailure = { cause ->
                val tried = attempts + 1
                copy(
                    attempts = tried,
                    failed = tried >= MAX_DOC_ATTEMPTS,
                    lastError = cause.message ?: cause::class.simpleName,
                )
            },
        )

    /** Push the whole run's next attempt out by the shared model-failure backoff. */
    private suspend fun parkJob(
        job: AiEditJobEntity,
        cause: Throwable?,
    ) {
        val attempts = job.attempts + 1
        val now = clock.nowMillis()
        dao.upsertJob(
            job.copy(
                attempts = attempts,
                // The same ramp as automatic enrichment: both are "an endpoint just refused a
                // model call", and the cost of guessing the interval wrong is identical.
                nextAttemptAt = now + backoffMs(attempts),
                updatedAt = now,
                lastError = cause?.let { it.message ?: it::class.simpleName },
            ),
        )
        cooldownUntil = now + AiAutoEnricher.FAILURE_COOLDOWN_MS
    }

    /**
     * A run whose stored request cannot be decoded can never be worked on, so it is dropped rather
     * than retried forever. Only reachable across an incompatible schema change to
     * [AiEditRequest] — the encode side is this same class.
     */
    private suspend fun dropUndecodableJob(job: AiEditJobEntity): Boolean {
        dao.deleteJob(job.jobId)
        return true
    }

    /**
     * Drop a run whose every document has left the library. It has nothing left to suggest and
     * nothing left to review, so leaving the row would strand a run the user can neither open nor
     * see. Always continues the drain: something either was cleaned up or is still owed elsewhere.
     */
    private suspend fun dropIfEmptied(job: AiEditJobEntity): Boolean {
        if (dao.docsFor(job.jobId).isEmpty()) dao.deleteJob(job.jobId)
        return true
    }

    /**
     * The live documents behind [docIds], keyed by id. Chunked to keep the expanded `IN (...)`
     * list clear of SQLite's bound-variable ceiling; a queued run can cover the whole library.
     */
    private suspend fun liveItems(docIds: List<String>): Map<String, DocListItem> =
        docIds
            .distinct()
            .chunked(DOC_LOOKUP_CHUNK)
            .flatMap { documents.observeDocuments(LibraryFilter(docIds = it), SORT).first() }
            .associateBy { it.doc.docId }

    private fun AiEditJobEntity.request(): AiEditRequest? =
        runCatching { json.decodeFromString<AiEditRequest>(requestJson) }.getOrNull()

    private fun AiEditJobDocEntity.suggestion(): AiEditSuggestion? =
        suggestionJson?.let { runCatching { json.decodeFromString<AiEditSuggestion>(it) }.getOrNull() }

    internal companion object {
        private val SORT = LibrarySort()

        /** Lenient so a suggestion stored by a newer build still opens under an older one. */
        private val json = NpnJson.Persisted

        /** Keeps the expanded `IN (...)` list clear of SQLite's bound-variable ceiling. */
        private const val DOC_LOOKUP_CHUNK = 400

        /**
         * Documents suggested per drain step, all concurrently. Matches the interactive run's
         * bounded concurrency: the calls are mostly IO-bound, so overlapping them is a large
         * speedup, and the same ceiling keeps a background run from hitting the endpoint harder
         * than a user watching one would.
         */
        internal const val CHUNK_DOCS = 12

        /**
         * Failed calls one document gets before the run gives up on it. Small because a document
         * is only charged when the failure was not read as an outage, so each attempt is real
         * evidence that this document is the problem.
         */
        internal const val MAX_DOC_ATTEMPTS = 3

        /**
         * Backed-off retries a run may blame on the endpoint before a total failure starts being
         * charged to its documents. Five steps of the shared ramp is a little over half an hour of
         * downtime absorbed for free — long enough for a tunnel, a sleeping laptop or a restarted
         * endpoint, short enough that a document the endpoint will never accept stops holding its
         * run within an afternoon.
         */
        internal const val MAX_JOB_ATTEMPTS = 5
    }
}

private val AiEditJobDocEntity.isPending: Boolean get() = suggestionJson == null && !failed

private val ReviewDoc.hasSuggestions: Boolean
    get() = chips.isNotEmpty() || removals.isNotEmpty() || fields.isNotEmpty()

@OptIn(ExperimentalUuidApi::class)
private fun randomJobId(): String = Uuid.random().toString()
