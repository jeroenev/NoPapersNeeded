package dev.npn.core.app.aiedit

import dev.npn.core.ai.AiEditRequest
import dev.npn.core.ai.AiEditSuggestion
import dev.npn.core.ai.AiFieldConfig
import dev.npn.core.ai.AiFieldMode
import dev.npn.core.ai.AiSuggest
import dev.npn.core.ai.UnattendedAiPolicy
import dev.npn.core.app.FieldRegistry
import dev.npn.core.app.MetadataWriter
import dev.npn.core.db.AiEnrichQueueDao
import dev.npn.core.db.AiEnrichQueueEntity
import dev.npn.core.db.AiEnrichedEntity
import dev.npn.core.db.DocumentDao
import dev.npn.core.db.FtsDao
import dev.npn.core.db.isLive
import dev.npn.core.journal.EpochClock
import dev.npn.core.util.reason
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.work.EndpointHealth
import dev.npn.core.work.QueueRetryPolicy

/**
 * Unattended AI enrichment of documents whose OCR text was just indexed, run as a persisted queue.
 *
 * The no-review counterpart of [AiEditRun]: with no human in the loop, the policy is conservative
 * and fixed rather than user-tunable.
 *
 * - **Tags**: additions only from the library's existing vocabulary ([AiEditRequest.maxNewTags]
 *   `= 0`, plus a defensive filter here); a document no existing tag fits stays untagged.
 * - **Tag removals** are never requested. The auto-match rules run just before this pass, and a
 *   removal could silently undo a rule or a tag the user added meanwhile; correcting tags is the
 *   reviewed [AiEditRun]'s job.
 * - **Fields** (every registry field marked aiEnabled) use [AiFieldMode.UNFILLED], so a value a
 *   human entered is never overwritten. Fields are written even when no tag matched.
 * - The write fills only what the document still lacks when it commits
 *   ([MetadataWriter.applyEdits] with `fillOnly`): the model was asked about the document as it was,
 *   and a value or tag a human added while it answered is theirs.
 *
 * Everything comes from ONE [AiSuggest.suggestEdits] call per document, and every write goes
 * through [MetadataWriter] (the journal).
 *
 * [enqueue] runs on the OCR pipeline's drain and only writes a row; the model call happens later on
 * the [dev.npn.core.work.QueueDrainer] (kicked through [onEnqueued]), so OCR never waits on the
 * network, and the persisted row survives an outage or a killed process until a later kick.
 *
 * A successful pass stamps [AiEnrichQueueDao.markEnriched] and deletes the row. The stamp is what
 * stops a later re-OCR of the document from spending again; both live in the device database,
 * keyed by hash, so a rebuilt index forgets neither. Only a document the model was asked about is
 * stamped: one with no indexed text, when fields are requested, is not asked and loses its row
 * unstamped, so text produced for it later queues it again. A failed call backs off along the shared
 * [QueueRetryPolicy] ramp up to a daily ceiling and is retried for ever: an attempt cap would bet
 * on *why* calls fail, and an afternoon offline would then silently and permanently cost every
 * document queued in it. The worst case is one call a day for a document the endpoint always
 * rejects, until it is trashed or a manual AI edit stamps it.
 *
 * Only a failed call says anything about the endpoint ([EndpointHealth]). An answer that could not
 * be written is the document's own problem: its row backs off on the same ramp, and the queues that
 * share the endpoint carry on. While the library refuses writes no call is made at all, since its
 * answer could not be saved.
 */
class AiAutoEnricher(
    private val documentDao: DocumentDao,
    /** Where a document's extracted text is indexed. */
    private val ftsDao: FtsDao,
    private val queueDao: AiEnrichQueueDao,
    private val ai: AiSuggest,
    private val meta: MetadataWriter,
    private val fieldRegistry: FieldRegistry,
    private val policy: UnattendedAiPolicy,
    private val clock: EpochClock,
    /** The endpoint's cooldown, shared with every other queue that calls it. */
    private val health: EndpointHealth,
    /** Whether the library takes metadata writes now (`JournalCoordinator.canWrite`). */
    private val canWrite: suspend () -> Boolean,
    /** Asks for a drain of this queue; called whenever [enqueue] writes a new row. */
    private val onEnqueued: () -> Unit,
) {
    /** Retried for ever: see the class docs for why nothing here gives up on a document. */
    private val retry = QueueRetryPolicy()

    /**
     * Record that [docId] is owed an enrichment pass. Called from the OCR pipeline's
     * [dev.npn.core.ocr.OcrPipeline.onOcrProduced] hook, so this must stay local work, never a
     * model call.
     *
     * The [UnattendedAiPolicy] is honoured here, not in the drain: a document OCR'd while the policy
     * said no is never queued, so allowing it later does not spend on the whole library. An
     * existing row is left as it is, so a re-OCR cannot restart a failing document's backoff.
     *
     * A new row kicks the drain ([onEnqueued]) and returns true. A table observer could not tell a
     * new document from a failed one being rescheduled, which must not wake the drain.
     */
    suspend fun enqueue(docId: String): Boolean {
        if (!policy.autoEnrich()) return false
        if (!isEnrichable(docId)) return false
        if (queueDao.getByDocId(docId) != null) return false
        val now = clock.nowMillis()
        queueDao.upsert(AiEnrichQueueEntity(docId = docId, nextAttemptAt = now, updatedAt = now))
        onEnqueued()
        return true
    }

    /**
     * Process at most one queued document, returning whether the drain should continue.
     *
     * Returns false, pausing the drain until the next kick, when there is no due work, while the
     * library refuses writes, and when a call or its write failed. A failed call is most likely
     * systemic (endpoint down, no network), and the backlog must not be fired at a dead endpoint.
     * Since each newly OCR'd document kicks a fresh drain and [AiEnrichQueueDao.nextDue] hands out
     * never-tried rows first, it also puts the endpoint on its shared cooldown ([EndpointHealth]),
     * capping an outage at one call a minute. A failed write backs its row off alone.
     *
     * The write, the stamp and the row delete span two stores (journal, device database) and cannot
     * be atomic. Dying between them costs one repeat call whose suggestion changes nothing, since
     * the fields are filled and the tags present; stamping first would lose the enrichment outright.
     */
    suspend fun processNext(): Boolean {
        val entry = dueEntry() ?: return false
        if (!isEnrichable(entry.docId)) {
            // Enriched by another path, trashed, or gone since it was queued.
            queueDao.delete(entry.docId)
            return true
        }
        val request = request()
        if (request.fields.isNotEmpty() && !hasText(entry.docId)) {
            // Nothing to fill a field from (OCR found no text, or ran with no engine), so no call and
            // no mark: the row goes, and text produced for the document later queues it again.
            queueDao.delete(entry.docId)
            return true
        }
        // An answer the library would not save is not worth paying for; the row waits, uncharged.
        if (!canWrite()) return false
        return enrich(entry, request)
    }

    private suspend fun hasText(docId: String): Boolean = ftsDao.contentForDoc(docId)?.isNotBlank() == true

    /**
     * The row to work on now; null while the feature is off or the endpoint cools down, and when
     * nothing is due.
     */
    private suspend fun dueEntry(): AiEnrichQueueEntity? {
        if (!policy.autoEnrich()) return null
        val now = clock.nowMillis()
        if (health.coolingDown(now)) return null
        return queueDao.nextDue(now)
    }

    /**
     * One paid call for [entry], and the write of its answer. Only the call reports to the
     * endpoint's [health]: a write that fails after an answer backs off this row alone.
     */
    private suspend fun enrich(
        entry: AiEnrichQueueEntity,
        request: AiEditRequest,
    ): Boolean {
        val suggestion =
            runCatchingCancellable { ai.suggestEdits(entry.docId, request) }.getOrElse { cause ->
                reschedule(entry, cause)
                health.failed(clock.nowMillis())
                return false
            }
        health.answered()
        return runCatchingCancellable {
            write(entry.docId, suggestion)
            queueDao.markEnriched(AiEnrichedEntity(entry.docId, clock.nowMillis()))
            queueDao.delete(entry.docId)
        }.fold(
            onSuccess = { true },
            onFailure = { cause ->
                reschedule(entry, cause)
                false
            },
        )
    }

    /**
     * Whether [docId] is still worth a model call: it exists, has not already been auto-enriched,
     * and is live. Checked both when queueing and when the row comes up for processing, since a
     * document can be trashed or deleted while it sits in the queue — which is also what stops a
     * document the user removed from being retried forever.
     *
     * Note that a manual [AiEditRun] over the same document does not stamp
     * [AiEnrichQueueDao.markEnriched] and so does not cancel a queued pass. The pass is then largely
     * wasted (the fields it would fill are no longer `UNFILLED`), but only for documents edited
     * inside the queue window, and letting a partial manual selection suppress it would be the
     * worse guess.
     */
    private suspend fun isEnrichable(docId: String): Boolean {
        val doc = documentDao.getByDocId(docId) ?: return false
        return doc.isLive && queueDao.enrichedAt(docId) == null
    }

    /** Bump attempts and push the retry out by the backoff, recording why for diagnostics. */
    private suspend fun reschedule(
        entry: AiEnrichQueueEntity,
        cause: Throwable,
    ) {
        val attempts = entry.attempts + 1
        val now = clock.nowMillis()
        queueDao.upsert(
            entry.copy(
                attempts = attempts,
                nextAttemptAt = retry.nextAttemptAt(now, attempts),
                updatedAt = now,
                lastError = cause.reason(),
            ),
        )
    }

    /**
     * Write what this unattended pass may of one suggestion, as ONE [MetadataWriter.applyEdits]
     * (tags and fields in one journal transaction): the file is renamed at most once, and a failed
     * pass writes nothing, so its retry asks about the state the model was first shown. A failure
     * throws into [enrich], which backs this row off and leaves the endpoint's cooldown alone.
     *
     * The request already asks for no new tags and no removals, but this path writes without
     * review, so new tags are dropped and removals never carried here too. Only the fields the
     * model filled are written, and only where the document has no value when the write commits;
     * an absent one leaves the document's value alone.
     */
    private suspend fun write(
        docId: String,
        suggestion: AiEditSuggestion,
    ) {
        val tags = suggestion.addTags.filterNot { it.isNew }.map { it.tag }
        val fields = suggestion.metadata.values
        // A model that found nothing gets no transaction, no rename, no journal entry at all.
        if (tags.isEmpty() && fields.isEmpty()) return
        meta.applyEdits(listOf(docId), addTags = tags, fields = fields, fillOnly = true)
    }

    /**
     * The request every automatic pass uses, read from the registry at drain time: every field the
     * user marked "let AI fill this in", UNFILLED so existing values are never touched.
     * `maxNewTags = 0` turns the vocabulary into a closed set for this flow; `maxTags` stays on the
     * user's configured default.
     */
    private suspend fun request(): AiEditRequest =
        AiEditRequest(
            // Registry order, capped: an unattended pass must not grow the reply contract with
            // every field the user marks for AI.
            fields =
                fieldRegistry
                    .current()
                    .filter { it.aiEnabled }
                    .take(AiEditRequest.MAX_FIELDS_PER_RUN)
                    .map { AiFieldConfig(it.key, AiFieldMode.UNFILLED) },
            tags = true,
            tagRemovals = false,
            maxNewTags = 0,
        )
}
