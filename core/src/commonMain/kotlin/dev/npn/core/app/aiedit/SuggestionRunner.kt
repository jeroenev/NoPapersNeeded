package dev.npn.core.app.aiedit

import dev.npn.core.ai.AiEditRequest
import dev.npn.core.ai.AiEditSuggestion
import dev.npn.core.ai.AiFacadeImpl
import dev.npn.core.ai.AiRunBudget
import dev.npn.core.ai.AiSuggest
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** One document that was actually asked about, and what came back. */
data class SuggestedOutcome<R>(
    val index: Int,
    val outcome: Result<R>,
)

/**
 * What a runner's call bought. [outcomes] covers only the documents that were asked about;
 * [capReached] says the run's [AiRunBudget] stopped the rest, and [requests] is how many calls were
 * made (equal to [outcomes]' size).
 */
data class SuggestionOutcomes<R>(
    val outcomes: List<SuggestedOutcome<R>>,
    val capReached: Boolean,
) {
    val requests: Int get() = outcomes.size

    /** Documents the budget left unasked. */
    fun skipped(documents: Int): Int = documents - outcomes.size
}

/**
 * One [AiSuggest.suggestEdits] call per document, at most [CONCURRENCY] of one run's documents at
 * once. Used by the interactive [AiEditRun] and the queued [AiEditQueue].
 *
 * What the slots bound is the work a large interactive batch holds at once: the answers waiting
 * on [suggest]'s `then` and the projection it does for them. They do not cap the load on the
 * endpoint; the AI facade does that, for every asking path together
 * ([AiFacadeImpl.MAX_CONCURRENT_SUGGESTIONS]). The queue asks about chunks of exactly
 * [CONCURRENCY] documents, so on its path the slots never make a call wait.
 *
 * [budget] is the run's per-run request cap. A slot is taken before each call, so concurrent calls
 * cannot overshoot it; the documents it leaves unasked are reported through
 * [SuggestionOutcomes.capReached] rather than as failures.
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
        runId: String? = null,
        budget: AiRunBudget? = null,
        onEach: (index: Int, outcome: Result<R>) -> Unit = { _, _ -> },
        then: suspend (index: Int, suggestion: AiEditSuggestion) -> R,
    ): SuggestionOutcomes<R> {
        val slots = Semaphore(CONCURRENCY)
        val finishing = Mutex()
        var skipped = 0
        val outcomes =
            coroutineScope {
                docIds
                    .mapIndexed { index, docId ->
                        async {
                            slots.withPermit {
                                if (budget != null && !budget.tryAcquire()) {
                                    finishing.withLock { skipped++ }
                                    return@withPermit null
                                }
                                val outcome =
                                    runCatchingCancellable {
                                        then(index, ai.suggestEdits(docId, request, runId))
                                    }
                                finishing.withLock { onEach(index, outcome) }
                                SuggestedOutcome(index, outcome)
                            }
                        }
                    }.awaitAll()
                    .filterNotNull()
            }
        return SuggestionOutcomes(outcomes, capReached = skipped > 0)
    }

    companion object {
        /** One run's documents asked about at once: as many as the endpoint's shared ceiling. */
        const val CONCURRENCY = AiFacadeImpl.MAX_CONCURRENT_SUGGESTIONS
    }
}
