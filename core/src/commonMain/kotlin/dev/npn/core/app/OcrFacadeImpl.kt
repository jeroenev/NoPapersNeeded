package dev.npn.core.app

import dev.npn.core.db.DocumentDao
import dev.npn.core.db.OcrQueueDao
import dev.npn.core.db.OcrQueueEntity
import dev.npn.core.db.QueueState
import dev.npn.core.db.queueState
import dev.npn.core.journal.EpochClock
import dev.npn.core.ocr.OcrEngineId
import dev.npn.core.ocr.OcrEngineSelector
import dev.npn.core.ocr.OcrPipeline
import dev.npn.core.ocr.OcrState
import dev.npn.core.work.QueueDrainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * [OcrFacade] over this device's [OcrPipeline], and the owner of its queue: every pass this device
 * owes is queued here and drained by [worker].
 *
 * The queue triggers its own work. Queueing here kicks [worker], and [watchQueue] kicks it whenever
 * the queue changes, which covers the rows other writers add (an import, an upgrade the pipeline
 * found while reconciling sidecars).
 */
internal class OcrFacadeImpl(
    private val lifetime: SessionLifetime,
    private val documentDao: DocumentDao,
    private val queue: OcrQueueDao,
    private val pipeline: OcrPipeline,
    /** Same instance the pipeline runs on, so the AI-OCR offer answers from the real policy. */
    private val engines: OcrEngineSelector,
    private val writer: MetadataWriterImpl,
    private val content: DocContentFacade,
    /** Drains the queue through [OcrPipeline.processNext]. */
    val worker: QueueDrainer,
    private val clock: EpochClock,
) : OcrFacade {
    /** The document the drain is working on right now; see [OcrPipeline.inFlight]. */
    val inFlight: StateFlow<String?> get() = pipeline.inFlight

    // `running` is asked of the pipeline rather than inferred from the count. The two are not the
    // same question — a queue can be full while nothing is draining it (nothing has kicked yet, or
    // the endpoint is down) — and inferring it made the indicator claim work was under way whenever
    // anything was owed, including for rows no drain would ever pick up.
    override val ocrStatus: Flow<OcrStatus> =
        combine(queue.countActive(), pipeline.inFlight) { owed, inFlight ->
            OcrStatus(pending = owed, running = inFlight != null)
        }

    override suspend fun reOcr(docId: String) =
        lifetime.owned {
            if (documentDao.getByDocId(docId) == null) return@owned
            enqueue(docId, rerun = true)
        }

    /**
     * The page count is asked of [content] rather than read off `DocumentEntity`: the row's
     * `pageCount` is a backfilled cache that stays NULL until something has opened the PDF, and a
     * NULL would quietly downgrade a 200-page document's warning to [AiOcrOffer.Ready]. A count
     * that cannot be read at all (unreadable PDF) is not this warning's problem — offer it plainly
     * and let the OCR run report the real fault.
     */
    override suspend fun aiOcrOffer(docId: String): AiOcrOffer {
        if (!engines.aiAvailable()) return AiOcrOffer.Unavailable
        val pages = content.pageCount(docId) ?: return AiOcrOffer.Ready
        return if (engines.isLargeForAi(pages)) AiOcrOffer.Large(pages) else AiOcrOffer.Ready
    }

    override suspend fun ocrWithAi(docId: String): AiOcrOutcome =
        lifetime.owned {
            documentDao.getByDocId(docId) ?: return@owned AiOcrOutcome.DOCUMENT_UNAVAILABLE
            if (!engines.aiAvailable()) return@owned AiOcrOutcome.NO_ENDPOINT
            // Deliberately no page limit here: length is something aiOcrOffer warns about, not
            // something this refuses. The caller asked for this document by name.
            //
            // The pin picks the ai sidecar for search once it exists, on every replica; the request
            // to pay for one is this device's queue flag (see OcrFacade.ocrWithAi). Queue after
            // pinning so the run that drains sees the override already folded onto the row.
            pin(docId, OcrEngineId.AI.column)
            enqueue(docId, aiRequested = true)
            AiOcrOutcome.QUEUED
        }

    override suspend fun ocrVersions(docId: String): List<OcrVersion> {
        val doc = documentDao.getByDocId(docId)
        return pipeline.listVersions(docId).map {
            OcrVersion(
                engine = it.engine,
                deviceId = it.deviceId,
                lang = it.lang,
                createdAt = it.createdAt,
                // Rows indexed before the writer key was recorded only know the engine.
                active = doc?.ocrSource?.let { source -> it.writerKey == source } ?: (it.engine == doc?.ocrEngine),
            )
        }
    }

    override suspend fun setOcrEngine(
        docId: String,
        engine: String?,
    ) = lifetime.owned { pin(docId, engine) }

    /** Ask for a drain, for rows another writer queued (an import, a split's parts). */
    fun kick() = worker.kick()

    /** Kick the drain on every change to the queue while it owes anything, on [scope]. */
    fun watchQueue(scope: CoroutineScope) {
        scope.launch {
            queue.countActive().collect { active ->
                if (active > 0) worker.kick()
            }
        }
    }

    /** Re-select or adopt the sidecars on disk; see [OcrPipeline.reconcileSidecars]. */
    suspend fun reconcileSidecars() = pipeline.reconcileSidecars()

    /**
     * After a rescan: adopt or re-select the sidecars it picked up, then queue every live document
     * still without OCR. A run this device's queue still owes keeps its attempts and requests (the
     * queue outlives a rebuilt index); the doc row only learns where that run stands.
     */
    suspend fun reconcileAfterRescan() {
        pipeline.reconcileSidecars()
        // This runs after every rescan, so it asks for the few live documents still without OCR
        // rather than reading every row to filter them here.
        for (docId in documentDao.liveDocIdsWithOcrState(OcrState.NONE.column)) {
            when (queue.getByDocId(docId)?.queueState) {
                null, QueueState.DONE -> enqueue(docId)
                QueueState.FAILED -> documentDao.updateOcrState(docId, OcrState.FAILED.column, null, null)
                QueueState.PENDING -> documentDao.updateOcrState(docId, OcrState.PENDING.column, null, null)
            }
        }
    }

    private suspend fun pin(
        docId: String,
        engine: String?,
    ) {
        writer.setOcrEngine(docId, engine)
        // The commit above already folded the override into the doc row; re-select now so this
        // device's index reflects the choice immediately.
        pipeline.reselect(docId)
    }

    /**
     * Mark [docId] pending in the OCR queue, flip its doc-row OCR state to pending, and kick the
     * drain. The row write is a partial UPDATE so this state flip cannot clobber metadata columns a
     * concurrent journal fold just projected onto the same row.
     *
     * A request already on the row ([OcrQueueEntity.rerun], [OcrQueueEntity.aiRequested]) is kept:
     * asking for one thing does not withdraw what was asked before it ran.
     */
    private suspend fun enqueue(
        docId: String,
        rerun: Boolean = false,
        aiRequested: Boolean = false,
    ) {
        val existing = queue.getByDocId(docId)
        queue.upsert(
            OcrQueueEntity(
                docId = docId,
                state = QueueState.PENDING.column,
                updatedAt = clock.nowMillis(),
                rerun = rerun || existing?.rerun == true,
                aiRequested = aiRequested || existing?.aiRequested == true,
            ),
        )
        documentDao.updateOcrState(docId, OcrState.PENDING.column, null, null)
        worker.kick()
    }
}
