package dev.npn.core.pdf

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Serializes CPU-heavy PDF rendering so a burst of thumbnail/page requests (e.g. 200 documents
 * scrolling into view on first load) cannot flood the coroutine dispatchers and freeze the UI.
 *
 * Two guarantees:
 * - A [Semaphore] caps the number of renders running at once at [parallelism], which the platform
 *   picks for its CPUs and memory (`renderParallelism`), instead of launching one heavy render per
 *   visible item simultaneously.
 * - Rendering runs on the CPU-bound [dispatcher], limited to the same [parallelism], not on
 *   [kotlinx.coroutines.Dispatchers.IO]. PDFBox `renderImage`/`PdfRenderer` are CPU/native bound,
 *   not blocking IO; running them on IO starves the IO pool used by everything else. A step of the
 *   work that switches dispatcher itself runs where it switched to, still holding its permit: the
 *   desktop engine parses the document on its IO dispatcher when it opens it.
 *
 * A single shared instance should back both thumbnail and page rendering so their combined load
 * stays bounded.
 */
class RenderGate(
    parallelism: Int,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val permits = parallelism.coerceAtLeast(1)
    private val semaphore = Semaphore(permits)
    private val bounded: CoroutineDispatcher = dispatcher.limitedParallelism(permits)

    /**
     * Run [block] on the bounded CPU dispatcher, holding a permit for its duration, steps that
     * switch dispatcher themselves included.
     */
    suspend fun <T> render(block: suspend () -> T): T =
        semaphore.withPermit {
            withContext(bounded) { block() }
        }
}
