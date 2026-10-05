package dev.npn.core.testing

import dev.npn.core.ai.AiEditRequest
import dev.npn.core.ai.AiEditSuggestion
import dev.npn.core.ai.AiSuggest
import dev.npn.core.ai.FieldSuggestion
import dev.npn.core.ai.TagSuggestion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * The one [AiSuggest] fake. [edit] backs every document, [editByDoc] overrides it per document;
 * calls are recorded at entry ([suggestEditsCalls]/[suggestEditsRequests]) so the lists reflect
 * dispatch order even when calls run concurrently. Failures are injected per document via
 * [failWith] (an endpoint/model error for that doc); the optional delays let tests observe
 * progress, concurrency and cancellation.
 */
class FakeAiSuggest : AiSuggest {
    var configured: Boolean = true

    /** Backs [suggestEdits] for docs without an [editByDoc] entry. */
    var edit: AiEditSuggestion = AiEditSuggestion.EMPTY

    /** Per-doc [suggestEdits] results (wins over [edit]). */
    var editByDoc: Map<String, AiEditSuggestion> = emptyMap()

    /** docIds passed to [suggestEdits], in dispatch order. */
    val suggestEditsCalls = mutableListOf<String>()

    /** Every request passed to [suggestEdits], in dispatch order. */
    val suggestEditsRequests = mutableListOf<AiEditRequest>()

    /** Called with the docId; throw to simulate an endpoint or model failure for that document. */
    var failWith: ((String) -> Unit)? = null

    /** Optional per-call delay so tests can observe progress and cancel mid-run. */
    var suggestDelayMillis: Long = 0

    /** Per-doc delay override (wins over [suggestDelayMillis]); lets tests skew completion order. */
    var suggestDelayByDoc: Map<String, Long> = emptyMap()

    /**
     * When set, [suggestEdits] records its call and then suspends until the gate completes, so a
     * test can hold a drain mid-model-call and exercise what the user does meanwhile.
     */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun configured(): Boolean = configured

    override suspend fun suggestEdits(
        docId: String,
        request: AiEditRequest,
    ): AiEditSuggestion {
        suggestEditsCalls += docId
        suggestEditsRequests += request
        val delayMillis = suggestDelayByDoc[docId] ?: suggestDelayMillis
        if (delayMillis > 0) delay(delayMillis)
        gate?.await()
        failWith?.invoke(docId)
        return editByDoc[docId] ?: edit
    }
}

/** A model answer for one document. */
fun edit(
    addTags: List<TagSuggestion> = emptyList(),
    removeTags: List<String> = emptyList(),
    metadata: FieldSuggestion = FieldSuggestion.EMPTY,
) = AiEditSuggestion(addTags = addTags, removeTags = removeTags, metadata = metadata)
