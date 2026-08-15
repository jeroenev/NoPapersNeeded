package dev.npn.app

import dev.npn.core.ai.AiEditRequest
import dev.npn.core.ai.AiEditSuggestion
import dev.npn.core.ai.AiSuggest

/**
 * Suggestion fake: [edit]/[editByDoc] back the single [suggestEdits] primitive. Failure injection
 * via [failSuggestEdits] (every doc) and [failSuggestEditsForDocs] (per doc); call recording via
 * [suggestEditsCalls]/[suggestEditsRequests], which record at entry so the lists reflect dispatch
 * order even when calls run concurrently. The delays let tests observe progress and cancel mid-run.
 */
class FakeAiSuggest : AiSuggest {
    var configuredValue: Boolean = true

    /** Backs [suggestEdits] for docs without an [editByDoc] entry. */
    var edit: AiEditSuggestion = AiEditSuggestion.EMPTY

    /** Per-doc [suggestEdits] results (wins over [edit]). */
    var editByDoc: Map<String, AiEditSuggestion> = emptyMap()

    /** docIds passed to [suggestEdits], in dispatch order. */
    val suggestEditsCalls = mutableListOf<String>()

    /** Every request passed to [suggestEdits], in dispatch order. */
    val suggestEditsRequests = mutableListOf<AiEditRequest>()
    var failSuggestEdits: (() -> Nothing)? = null

    /** suggestEdits throws for these docIds only (per-doc partial-failure testing). */
    var failSuggestEditsForDocs: Set<String> = emptySet()

    /** Optional per-call delay so tests can observe progress and cancel mid-run. */
    var suggestDelayMillis: Long = 0

    /** Per-doc delay override (wins over [suggestDelayMillis]); lets tests skew completion order. */
    var suggestDelayByDoc: Map<String, Long> = emptyMap()

    override suspend fun configured(): Boolean = configuredValue

    override suspend fun suggestEdits(
        docId: String,
        request: AiEditRequest,
    ): AiEditSuggestion {
        suggestEditsCalls += docId
        suggestEditsRequests += request
        val delay = suggestDelayByDoc[docId] ?: suggestDelayMillis
        if (delay > 0) kotlinx.coroutines.delay(delay)
        failSuggestEdits?.invoke()
        if (docId in failSuggestEditsForDocs) error("suggest edits failed for $docId")
        return editByDoc[docId] ?: edit
    }
}
