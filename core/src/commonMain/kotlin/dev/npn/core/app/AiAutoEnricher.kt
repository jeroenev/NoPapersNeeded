package dev.npn.core.app

import dev.npn.core.ai.AiConfig
import dev.npn.core.ai.AiEditRequest
import dev.npn.core.ai.AiEditSuggestion
import dev.npn.core.ai.AiFieldConfig
import dev.npn.core.ai.AiFieldMode
import dev.npn.core.ai.AiSuggest
import dev.npn.core.db.AiEnrichQueueDao
import dev.npn.core.db.AiEnrichQueueEntity
import dev.npn.core.db.DocumentDao
import dev.npn.core.journal.EpochClock
import kotlinx.coroutines.CancellationException

/**
 * Unattended AI enrichment of documents whose OCR text was just indexed, run as a persisted queue.
 *
 * This is the no-review counterpart of [AiEditRun]: there is no human in the loop, so the policy is
 * deliberately conservative and fixed rather than user-tunable.
 *
 * - **Tags**: additions are restricted to the library's existing vocabulary
 *   ([AiEditRequest.maxNewTags] `= 0`, plus a defensive filter here) — the automation organizes
 *   documents into the taxonomy the user already built, it never invents one. A document the model
 *   finds no existing tag for is simply left untagged.
 * - **Tag removals** are not requested: the pass is purely additive. Nothing here is reviewed, and
 *   the deterministic auto-match rules run immediately before it, so a removal would let the model
 *   silently undo a rule — or a tag the user added while the document sat in the queue. Correcting
 *   tags is the reviewed [AiEditRun]'s job, where each removal is opt-in.
 * - **Fields** (every registry field marked aiEnabled) use [AiFieldMode.UNFILLED]: only empty fields
 *   are filled, so an automatic pass can never overwrite a value a human entered. Tags and fields
 *   are independent — fields are written even when no vocabulary tag matched.
 *
 * Everything comes from ONE [AiSuggest.suggestEdits] call per document, and all writes go through
 * [MetadataWriter] (i.e. the journal), never straight to the index.
 *
 * ## Why a queue
 *
 * [enqueue] runs on the OCR pipeline's drain thread and does nothing but local DB work; the model
 * call happens later on the [dev.npn.core.work.QueueDrainer], so OCR of the remaining import is
 * never stalled behind the network. Because the intent to enrich is persisted, work also survives
 * the endpoint being down, the network being off and the process being killed mid-import: the row
 * stays and the next drain kick (queue change, rescan, or the periodic maintenance tick) picks it
 * up.
 *
 * ## Completion and retries
 *
 * A successful pass stamps [DocumentDao.markAiEnriched] and deletes the row — the stamp, not the
 * queue, is what stops a later re-OCR of the same document (an upgrade run under a better engine)
 * from re-spending the user's quota. A failed call backs off exponentially up to a daily ceiling
 * and is retried indefinitely; the feature being off or the endpoint unconfigured is not a failure
 * at all, since the drain simply does not run.
 *
 * Nothing here gives up on a document. An attempt cap would have to be a bet on *why* the call
 * failed, and the likely reasons — endpoint down, device offline, misconfigured URL — are ones that
 * outlive any cap worth having and then resolve: an afternoon of airplane mode would silently cost
 * every document queued in it, permanently and invisibly. Retrying forever instead makes the worst
 * case a document whose content the endpoint always rejects, which costs one call a day and stops
 * the moment the user trashes it or a manual AI edit stamps it.
 */
class AiAutoEnricher(
    private val documentDao: DocumentDao,
    private val queueDao: AiEnrichQueueDao,
    private val ai: AiSuggest,
    private val meta: MetadataWriter,
    private val fieldRegistry: FieldRegistry,
    private val readConfig: suspend () -> AiConfig,
    private val clock: EpochClock,
) {
    /** The shared write mechanics; this pass only projects the suggestion onto what it may write. */
    private val editApply = AiEditApply(meta)

    /**
     * When the next call may be attempted after a failure. In memory rather than on a row: it is a
     * statement about the endpoint, not about a document, and it must not survive a restart — an
     * app relaunch is the one moment where retrying immediately is most likely to be right.
     *
     * Only ever read and written from inside [processNext], which the
     * [dev.npn.core.work.QueueDrainer] mutex serialises.
     */
    private var cooldownUntil = 0L

    /**
     * Record that [docId] is owed an enrichment pass. Called from the OCR pipeline's
     * [dev.npn.core.ocr.OcrPipeline.onOcrProduced] hook, so this must stay local-only work — never
     * a model call.
     *
     * Enqueueing (not the drain) is where the user's setting is honoured: a document OCR'd while
     * the toggle was off is never queued, so turning it on later enriches new documents rather
     * than retroactively spending on the whole library.
     *
     * An existing row is left exactly as it is. Repeat firings for the same document are still
     * possible (a re-OCR under a better engine), and resetting the row on each one would let a
     * document that always fails restart its backoff forever.
     *
     * Returns whether a new row was written, which is the caller's cue to kick the drain. The kick
     * is deliberately tied to *this* call rather than to an observer of the queue table: a table
     * observer cannot tell a new document from a failed one being rescheduled, and only the former
     * should wake the drain.
     */
    suspend fun enqueue(docId: String): Boolean {
        val config = readConfig()
        if (!config.autoOnOcr || !config.configured) return false
        if (!isEnrichable(docId)) return false
        if (queueDao.getByDocId(docId) != null) return false
        val now = clock.nowMillis()
        queueDao.upsert(AiEnrichQueueEntity(docId = docId, nextAttemptAt = now, updatedAt = now))
        return true
    }

    /**
     * Process at most one queued document, returning whether the drain should continue.
     *
     * Returns false — pausing the whole drain until the next kick — both when there is no due work
     * and when a call failed. Stopping on failure is deliberate: the overwhelmingly likely cause is
     * a systemic one (endpoint down, no network), and continuing would fire the entire backlog at a
     * dead endpoint one document at a time.
     *
     * Stopping the drain is not enough on its own, because each newly OCR'd document kicks a fresh
     * one and [AiEnrichQueueDao.nextDue] hands out never-tried rows first: importing a folder
     * against a dead endpoint would spend one failed call per document anyway. So a failure also
     * parks this instance for [FAILURE_COOLDOWN_MS], which caps a systemic outage at one call a
     * minute however fast documents arrive.
     *
     * The cooldown is a short constant rather than the failed row's own backoff precisely because
     * it cannot tell an outage from one document the endpoint always rejects. Charging every later
     * document the poison row's escalating delay would let a single bad document stall the queue
     * for a day; a flat minute makes the worst case of guessing wrong a minute of latency.
     *
     * The write, the stamp and the row delete are three steps across two stores (journal, index) and
     * cannot be made atomic. Dying between them costs one repeat call on the next drain, whose
     * suggestion then lands on already-filled fields and already-present tags and so changes
     * nothing. That is deliberately the direction to fail in: stamping first would make the same
     * crash lose the enrichment outright, with nothing left to say it was owed.
     */
    suspend fun processNext(): Boolean {
        val config = readConfig()
        if (!config.autoOnOcr || !config.configured) return false
        val now = clock.nowMillis()
        if (now < cooldownUntil) return false
        val entry = queueDao.nextDue(now) ?: return false
        if (!isEnrichable(entry.docId)) {
            // Enriched by another path, trashed, or gone since it was queued.
            queueDao.delete(entry.docId)
            return true
        }
        return try {
            write(entry.docId, ai.suggestEdits(entry.docId, request()))
            documentDao.markAiEnriched(entry.docId, clock.nowMillis())
            queueDao.delete(entry.docId)
            cooldownUntil = 0L
            true
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            reschedule(entry, t)
            cooldownUntil = clock.nowMillis() + FAILURE_COOLDOWN_MS
            false
        }
    }

    /**
     * Whether [docId] is still worth a model call: it exists, has not already been auto-enriched,
     * and is live. Checked both when queueing and when the row comes up for processing, since a
     * document can be trashed or deleted while it sits in the queue — which is also what stops a
     * document the user removed from being retried forever.
     *
     * Note that a manual [AiEditRun] over the same document does not stamp
     * [dev.npn.core.db.DocumentEntity.aiEnrichedAt] and so does not cancel a queued pass. The pass is then largely
     * wasted (the fields it would fill are no longer `UNFILLED`), but only for documents edited
     * inside the queue window, and letting a partial manual selection suppress it would be the
     * worse guess.
     */
    private suspend fun isEnrichable(docId: String): Boolean {
        val doc = documentDao.getByDocId(docId) ?: return false
        return doc.aiEnrichedAt == null && !doc.trashed && !doc.deleted && !doc.missing
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
                nextAttemptAt = now + backoffMs(attempts),
                updatedAt = now,
                lastError = cause.message ?: cause::class.simpleName,
            ),
        )
    }

    /**
     * Write one suggestion through [AiEditApply.applyAtomically], which commits the whole edit —
     * this document's vocabulary tags AND its fields — in ONE journal transaction. That gives both
     * things this unattended pass depends on: the file is renamed at most once (a template keyed on
     * correspondent and date does not rename per field), and the commit is all-or-nothing, so a
     * failed pass writes nothing and its retry re-asks the model about the state it was originally
     * shown rather than a half-written one. The shared [apply] entry point would split this into a
     * transaction per tag plus one for the fields, which is right for the reviewed multi-document
     * Edit Run but would break that all-or-nothing retry contract here — hence the separate grain.
     *
     * A failed write is rethrown so the [processNext] catch drives the queue's cooldown and
     * backoff — [AiEditApply] collects the cause rather than throwing, so this path re-raises the
     * document's own cause instead of a synthetic one.
     */
    private suspend fun write(
        docId: String,
        suggestion: AiEditSuggestion,
    ) {
        val edit = writableEdit(docId, suggestion)
        // A model that found nothing gets no transaction, no rename, no journal entry at all. An
        // empty edit would already commit nothing; the explicit guard states that intent.
        if (edit.isEmpty) return
        val report = editApply.applyAtomically(edit)
        report.failures[docId]?.let { throw it }
    }

    /**
     * The suggestion projected onto what this unattended pass is allowed to write.
     *
     * Belt and braces: the request already caps new tags at 0 and asks for no removals, but this
     * path writes without review, so an out-of-contract facade must not be able to grow the
     * vocabulary or take a tag off a document. New tags are dropped and removals are never carried.
     *
     * Only non-null field suggestions are included: absent means "the model had nothing", which
     * must leave the document's value alone, not clear it.
     */
    private fun writableEdit(
        docId: String,
        suggestion: AiEditSuggestion,
    ): WritableEdit =
        WritableEdit(
            docId = docId,
            addTags = suggestion.addTags.filterNot { it.isNew }.map { it.tag }, // closed vocabulary
            removeTags = emptyList(), // never removes unattended
            properties = suggestion.metadata.values,
        )

    /**
     * The request every automatic pass uses, read from the registry at drain time: every field the
     * user marked "let AI fill this in", UNFILLED so existing values are never touched.
     * `maxNewTags = 0` turns the vocabulary into a closed set for this flow; `maxTags` stays on the
     * user's configured default.
     */
    private suspend fun request(): AiEditRequest =
        AiEditRequest(
            // Registry order, capped (P11): an unattended pass must not grow the reply contract
            // with every field the user marks for AI.
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

    companion object {
        /** How long a failed call parks the whole queue, regardless of which document failed. */
        internal const val FAILURE_COOLDOWN_MS = 60L * 1000L

        private const val BACKOFF_BASE_MS = 60L * 1000L
        private const val BACKOFF_CEILING_MS = 24L * 60L * 60L * 1000L

        /** Keeps the shift in range for an unbounded [AiEnrichQueueEntity.attempts]; well past the ceiling. */
        private const val BACKOFF_MAX_SHIFT = 20

        /**
         * 1m, 2m, 4m, 8m … 17h, then a day forever.
         *
         * Plain doubling rather than a coarser ramp: the interval is also how long a *recovered*
         * endpoint goes unnoticed, so overshooting costs latency on the common case (a blip, a
         * tunnel, a laptop that just woke) to save calls on the rare one. Eleven steps to the
         * ceiling keeps every early retry cheap while still settling at one call a day for a
         * document that will never succeed.
         *
         * This is an earliest-retry time, not a schedule: nothing wakes the drain when a row falls
         * due. A retry is served by the next kick after that, so on an otherwise idle library the
         * first few steps all collapse onto the periodic maintenance tick and the observed ramp
         * starts at that interval. The doubling only separates the steps above it — which is where
         * the calls being saved actually are.
         */
        fun backoffMs(attempts: Int): Long =
            (BACKOFF_BASE_MS shl (attempts - 1).coerceIn(0, BACKOFF_MAX_SHIFT))
                .coerceAtMost(BACKOFF_CEILING_MS)
    }
}
