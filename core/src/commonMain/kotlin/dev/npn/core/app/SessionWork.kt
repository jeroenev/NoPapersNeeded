package dev.npn.core.app

import dev.npn.core.work.QueueDrainer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow

/**
 * [WorkFacade] over the session's three drained queues: OCR, automatic AI enrichment and queued AI
 * edit runs. The drains go through the queues' own drainers rather than running on the caller's
 * coroutine, so closing the session cancels them (see [QueueDrainer.drainAndAwait]).
 *
 * Every drain waits at [firstRescan] before its first read, until [rescanned] opens it.
 */
internal class SessionWork(
    private val lifetime: SessionLifetime,
    private val ocr: QueueDrainer,
    private val enrichment: QueueDrainer,
    private val edits: QueueDrainer,
    private val firstRescan: CompletableDeferred<Unit>,
    reader: WorkQueueReader,
) : WorkFacade {
    override val queue: Flow<List<QueuedWork>> = reader.queue

    override val editRuns: Flow<List<QueuedEditRun>> = reader.editRuns

    override suspend fun drainOcr() {
        lifetime.checkOpen()
        ocr.drainAndAwait()
    }

    override suspend fun drainEnrichment() {
        lifetime.checkOpen()
        enrichment.drainAndAwait()
    }

    override suspend fun drainEditRuns() {
        lifetime.checkOpen()
        edits.drainAndAwait()
    }

    /**
     * The session's rescan succeeded, so the index knows the library's documents: let the drains
     * start. Until the first one, a document without a row may only not be indexed yet, and each
     * drain would drop this device's work for it as gone.
     */
    fun rescanned() {
        firstRescan.complete(Unit)
    }

    /**
     * Kick every queue. New work kicks its own queue; this is for what no enqueue announces: work
     * a killed process left behind, and retries whose backoff has fallen due since.
     */
    fun kickAll() {
        ocr.kick()
        enrichment.kick()
        edits.kick()
    }
}
