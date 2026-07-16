package dev.npn.core.pdf

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Serializes CPU-heavy PDF rendering so a burst of thumbnail/page requests (e.g. 200 documents
 * scrolling into view on first load) cannot flood the coroutine dispatchers and freeze the UI.
 *
 * Two guarantees:
 * - A [Semaphore] caps the number of renders running at once to a small multiple of the CPU
 *   count, instead of launching one heavy render per visible item simultaneously.
 * - Work runs on a CPU-bound [dispatcher] (a limited-parallelism [kotlinx.coroutines.Dispatchers.Default]),
 *   not on [kotlinx.coroutines.Dispatchers.IO]. PDFBox `renderImage`/`PdfRenderer` are CPU/native
 *   bound, not blocking IO; running them on IO starves the IO pool used by everything else.
 *
 * A single shared instance should back both thumbnail and page rendering so their combined load
 * stays bounded.
 */
class RenderGate(
    parallelism: Int = defaultRenderParallelism(),
) {
    private val permits = parallelism.coerceAtLeast(1)
    private val semaphore = Semaphore(permits)
    private val dispatcher: CoroutineDispatcher = renderDispatcher(permits)

    /** Run [block] on the bounded CPU dispatcher, holding a permit for its duration. */
    suspend fun <T> render(block: suspend () -> T): T =
        semaphore.withPermit {
            withContext(dispatcher) { block() }
        }
}

/** Bounded CPU-bound dispatcher for render work (platform actual). */
internal expect fun renderDispatcher(parallelism: Int): CoroutineDispatcher

/** Sensible default concurrency for rendering, derived from available CPUs (platform actual). */
expect fun defaultRenderParallelism(): Int
