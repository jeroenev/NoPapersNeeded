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
import dev.npn.core.work.EndpointHealth
import dev.npn.core.work.QueueRetryPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    override suspend fun apply(reviewed: List<ReviewDoc>): ApplyOutcome =
        reviewApply
            .apply(reviewed, moreRemain = { false })
            .also { if (it is ApplyOutcome.Done) onFinished() }

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

    /**
     * Drop a queued run and its suggestions, whether or not it ever finished. Returns at once, even
     * while the drain is waiting on model calls for the run: their answers are dropped.
     */
    suspend fun discardEditRun(jobId: String)
}

/**
 * Bulk AI edits the user handed to the background, run as a persisted queue.
 *
 * An Edit Run costs one model call per document, too long to hold a screen open for. Queued, it is
 * work the app owes: it survives leaving the page, an endpoint outage and a killed process, and
 * its answers wait on the processing queue until the user opens them. Only the model calls happen
 * here, each answer stored verbatim; the Review and every write still go through the same human
 * confirmation as an interactive run. (That is the difference from [AiAutoEnricher], which may
 * write because its policy is fixed and conservative.)
 *
 * ## Failure: systemic versus per-document
 *
 * [processNext] works one chunk of documents per step, as many as [SuggestionRunner] asks about at
 * once, and treats failure by how much of the chunk failed:
 *
 * - **Part of the chunk failed**: the endpoint is alive, so the failed documents carry the attempt
 *   and are given up on at [MAX_DOC_ATTEMPTS], so one document the endpoint always rejects cannot
 *   keep a run from ever becoming reviewable.
 * - **The whole chunk failed**: most likely an outage (endpoint down, no network, bad
 *   credentials). The documents are not charged; the *job* backs off and the endpoint goes on its
 *   shared cooldown ([EndpointHealth]), which also holds automatic enrichment back.
 *
 * A one-document chunk that always fails would never be charged under the second rule, so the
 * systemic reading is a budget: [MAX_JOB_ATTEMPTS] backed-off retries (about half an hour on the
 * shared ramp) cost the documents nothing, and after that a total failure is charged like any
 * other. A chunk that made any progress clears the job's backoff and its budget.
 *
 * The drain is single-flight ([dev.npn.core.work.QueueDrainer]), but nothing holds a run while its
 * model calls are out: a step writes back only if the run still exists
 * ([AiEditJobDao.writeBackIfPresent]), so a discard lands at once and stays.
 *
 * Queueing a run asks for its drain through [onEnqueued], so it never waits for the periodic tick.
 * The queue's writes run through [ownWrite].
 */
class AiEditQueue(
    private val dao: AiEditJobDao,
    private val documents: DocumentRepository,
    content: DocContentFacade,
    private val meta: MetadataWriter,
    ai: AiSuggest,
    fieldRegistry: FieldRegistry,
    private val readConfig: suspend () -> AiConfig,
    private val clock: EpochClock,
    /** The endpoint's cooldown, shared with every other queue that calls it. */
    private val health: EndpointHealth,
    /** Asks for a drain of this queue; called once a run is queued. */
    private val onEnqueued: () -> Unit,
    /** Runs the queue's writes as work the session owns, so closing it waits for them. */
    private val ownWrite: suspend (suspend () -> Unit) -> Unit = { it() },
    private val newJobId: () -> String = ::randomJobId,
) : AiEditQueueFacade {
    private val projector = ReviewProjector(content, fieldRegistry)
    private val runner = SuggestionRunner(ai)

    /** A document is given up on at [MAX_DOC_ATTEMPTS]; a job's own backoff has no cap. */
    private val docRetry = QueueRetryPolicy(MAX_DOC_ATTEMPTS)
    private val jobRetry = QueueRetryPolicy()

    private val _inFlight = MutableStateFlow<String?>(null)

    /**
     * The run this device's drain is suggesting right now, null when it is idle. In-process, like
     * [dev.npn.core.ocr.OcrPipeline.inFlight], rather than read off the rows: progress says only
     * that answers were bought, and a run whose endpoint went away keeps its progress for good.
     */
    val inFlight: StateFlow<String?> = _inFlight.asStateFlow()

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
            reviewApply = ReviewApply(meta),
            onFinished = { dao.deleteJob(jobId) },
        )
    }

    override suspend fun discardEditRun(jobId: String) = ownWrite { dao.deleteJob(jobId) }

    /**
     * Suggest one chunk of the oldest due run, returning whether the drain should continue. See the
     * class docs for the systemic-versus-per-document failure split; a systemic failure returns
     * false, parking the drain until the next kick rather than firing every remaining chunk at a
     * dead endpoint.
     */
    suspend fun processNext(): Boolean {
        val job = dueJob() ?: return false
        val request = job.request() ?: return dropUndecodableJob(job)
        _inFlight.value = job.jobId
        try {
            return suggestNextChunk(job, request)
        } finally {
            _inFlight.value = null
        }
    }

    /**
     * The run to work on now, or null when there is nothing to do: the endpoint is unconfigured,
     * a systemic failure is still being waited out, or no run has a document left to suggest.
     */
    private suspend fun dueJob(): AiEditJobEntity? {
        if (!readConfig().configured) return null
        val now = clock.nowMillis()
        if (health.coolingDown(now)) return null
        return dao.nextDueJob(now)
    }

    private suspend fun suggestNextChunk(
        job: AiEditJobEntity,
        request: AiEditRequest,
    ): Boolean {
        val pending = dao.nextPendingDocs(job.jobId, SuggestionRunner.CONCURRENCY)
        if (pending.isEmpty()) return true
        val items = liveItems(pending.map { it.docId })
        // Documents trashed or deleted since the run was queued are dropped rather than asked
        // about: the answer could not be applied to anything.
        val (live, gone) = pending.partition { it.docId in items }
        if (gone.isNotEmpty()) dao.deleteDocs(job.jobId, gone.map { it.docId })
        if (live.isEmpty()) return dropIfEmptied(job)
        return recordChunk(job, live, runner.suggest(live.map { it.docId }, request) { _, suggestion -> suggestion })
    }

    /**
     * Persist a chunk's outcomes and decide what its failures mean (see the class docs), unless
     * the run was discarded meanwhile.
     */
    private suspend fun recordChunk(
        job: AiEditJobEntity,
        rows: List<AiEditJobDocEntity>,
        outcomes: List<Result<AiEditSuggestion>>,
    ): Boolean {
        val allFailed = outcomes.all { it.isFailure }
        // Read a total failure as an outage only while the job still has budget for that reading;
        // past it the documents are charged, or a run whose last document is poison never ends.
        val charged =
            if (allFailed && job.attempts < MAX_JOB_ATTEMPTS) {
                emptyList()
            } else {
                rows.mapIndexed { index, row -> row.recording(outcomes[index]) }
            }
        val now = clock.nowMillis()
        if (allFailed) {
            // Nothing to carry on with right now, charged or not.
            health.failed(now)
            dao.writeBackIfPresent(job.parked(now, outcomes.firstCause()), charged)
            return false
        }
        // Progress means the endpoint is alive, so the backoff and the systemic budget this job
        // spent while it was not are both stale; clearing them lets the next kick carry straight on.
        health.answered()
        dao.writeBackIfPresent(job.copy(attempts = 0, nextAttemptAt = now, updatedAt = now, lastError = null), charged)
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
                    failed = docRetry.givesUp(tried),
                    lastError = cause.message ?: cause::class.simpleName,
                )
            },
        )

    /** The run with its next attempt pushed out by the shared backoff. */
    private fun AiEditJobEntity.parked(
        now: Long,
        cause: Throwable?,
    ): AiEditJobEntity {
        val tried = attempts + 1
        return copy(
            attempts = tried,
            nextAttemptAt = jobRetry.nextAttemptAt(now, tried),
            updatedAt = now,
            lastError = cause?.let { it.message ?: it::class.simpleName },
        )
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

    /** The live documents behind [docIds], keyed by id; a queued run can cover the whole library. */
    private suspend fun liveItems(docIds: List<String>): Map<String, DocListItem> =
        documents.liveDocuments(docIds).associateBy { it.doc.docId }

    private fun AiEditJobEntity.request(): AiEditRequest? =
        runCatching { json.decodeFromString<AiEditRequest>(requestJson) }.getOrNull()

    private fun AiEditJobDocEntity.suggestion(): AiEditSuggestion? =
        suggestionJson?.let { runCatching { json.decodeFromString<AiEditSuggestion>(it) }.getOrNull() }

    internal companion object {
        /** Lenient so a suggestion stored by a newer build still opens under an older one. */
        private val json = NpnJson.Persisted

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

@OptIn(ExperimentalUuidApi::class)
private fun randomJobId(): String = Uuid.random().toString()
