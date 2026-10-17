package dev.npn.core.app.aiedit

import dev.npn.core.ai.AiEditRequest
import dev.npn.core.ai.AiEditSuggestion
import dev.npn.core.ai.AiFacadeImpl
import dev.npn.core.ai.AiSuggest
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * One [AiSuggest.suggestEdits] call per document, at most [CONCURRENCY] of one run's documents at
 * once. Used by the interactive [AiEditRun] and the queued [AiEditQueue], so a run in the
 * background never asks the endpoint harder than one the user watches. This bounds one run's
 * fan-out (and the answers it holds while [suggest]'s `then` handles them); the ceiling every
 * asking path shares, runs and the background drains together, is the AI facade's
 * ([AiFacadeImpl.MAX_CONCURRENT_SUGGESTIONS]).
 */
internal class SuggestionRunner(
    private val ai: AiSuggest,
) {
    /**
     * Ask about each of [docIds] with [request], passing each answer through [then] while it still
     * holds its slot. The outcomes come back in [docIds]' order, whatever order the calls finish
     * in. A failure is that document's outcome; cancellation tears the whole run down. [onEach]
     * runs as each document finishes, one call at a time.
     */
    suspend fun <R> suggest(
        docIds: List<String>,
        request: AiEditRequest,
        onEach: (index: Int, outcome: Result<R>) -> Unit = { _, _ -> },
        then: suspend (index: Int, suggestion: AiEditSuggestion) -> R,
    ): List<Result<R>> {
        val slots = Semaphore(CONCURRENCY)
        val finishing = Mutex()
        return coroutineScope {
            docIds
                .mapIndexed { index, docId ->
                    async {
                        slots.withPermit {
                            val outcome = runCatchingCancellable { then(index, ai.suggestEdits(docId, request)) }
                            finishing.withLock { onEach(index, outcome) }
                            outcome
                        }
                    }
                }.awaitAll()
        }
    }

    companion object {
        /** One run's documents asked about at once: as many as the endpoint's shared ceiling. */
        const val CONCURRENCY = AiFacadeImpl.MAX_CONCURRENT_SUGGESTIONS
    }
}
