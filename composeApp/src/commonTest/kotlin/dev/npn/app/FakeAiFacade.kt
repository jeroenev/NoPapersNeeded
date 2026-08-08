package dev.npn.app

import dev.npn.core.ai.AiChatEvent
import dev.npn.core.ai.AiChatTurn
import dev.npn.core.ai.AiConfig
import dev.npn.core.ai.AiEditRequest
import dev.npn.core.ai.AiEditSuggestion
import dev.npn.core.ai.AiFacade
import dev.npn.core.ai.AiProvider
import dev.npn.core.ai.MetadataSuggestion
import dev.npn.core.ai.TagSuggestion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * AI fake: [askScripts] are event lists consumed in order (last one repeats); each backs one
 * [ask] flow. [askResponses] is a convenience: a plain answer string is turned into a
 * `TextDelta` + `Done` script. [suggestions] backs [suggestTags]. Failure injection via the
 * `fail*` hooks ([failAsk] throws when the returned flow is collected), call recording via
 * `asked`/[suggestTagsCalls]. [askDelayMillis] delays each emitted event so tests can observe
 * intermediate streaming state and cancellation.
 */
class FakeAiFacade : AiFacade {
    var configValue =
        AiConfig(
            enabled = true,
            provider = AiProvider.CUSTOM,
            baseUrl = "http://localhost:1",
            model = "test-model",
        )
    var askScripts: List<List<AiChatEvent>> = emptyList()
    var askResponses: List<String> = emptyList()
    var askDelayMillis: Long = 0
    private var askIdx = 0
    val asked = mutableListOf<Pair<List<AiChatTurn>, String>>()
    var failAsk: (() -> Nothing)? = null

    /** When set, the ask flow throws after emitting this many events (mid-stream failure). */
    var failAskAfter: Int? = null
    var suggestions: List<TagSuggestion> = emptyList()

    /**
     * Per-doc suggestion scripts for the bulk tagging flow. When a docId has an entry here it wins
     * over [suggestions]; docs absent from the map fall back to [suggestions]. Lets tests drive
     * distinct results (including empty lists for the "no suggestions" grouping) per document.
     */
    var suggestionsByDoc: Map<String, List<TagSuggestion>> = emptyMap()

    /** docId-only view of [suggestTagsTriples] for assertions that only care about which docs. */
    val suggestTagsCalls = mutableListOf<String>()

    /** Every (docId, maxTags, maxNewTags) triple passed to [suggestTags], in dispatch order. */
    val suggestTagsTriples = mutableListOf<Triple<String, Int?, Int?>>()
    var failSuggestTags: (() -> Nothing)? = null

    /** suggestTags throws for these ids only (per-doc partial-failure testing). */
    var failSuggestTagsForDocs: Set<String> = emptySet()

    /** Optional per-call delay so tests can observe progress and cancel mid-run. */
    var suggestDelayMillis: Long = 0

    /** Per-doc delay override (wins over [suggestDelayMillis]); lets tests skew completion order. */
    var suggestDelayByDoc: Map<String, Long> = emptyMap()

    override suspend fun config(): AiConfig = configValue

    override fun ask(
        history: List<AiChatTurn>,
        question: String,
    ): Flow<AiChatEvent> {
        val script = scriptFor(askIdx)
        askIdx++
        asked += history to question
        return flow {
            failAsk?.invoke()
            var emitted = 0
            for (event in script) {
                if (failAskAfter == emitted) error("stream interrupted")
                if (askDelayMillis > 0) kotlinx.coroutines.delay(askDelayMillis)
                emit(event)
                emitted++
            }
        }
    }

    private fun scriptFor(index: Int): List<AiChatEvent> {
        if (askScripts.isNotEmpty()) return askScripts.getOrElse(index) { askScripts.last() }
        val text = askResponses.getOrElse(index) { askResponses.lastOrNull() ?: "answer" }
        return listOf(AiChatEvent.TextDelta(text), AiChatEvent.Done(text))
    }

    override suspend fun suggestTags(
        docId: String,
        maxTags: Int?,
        maxNewTags: Int?,
    ): List<TagSuggestion> {
        // Record at entry (before the delay) so the call list reflects dispatch order
        // deterministically even when calls run concurrently.
        suggestTagsCalls += docId
        suggestTagsTriples += Triple(docId, maxTags, maxNewTags)
        val delay = suggestDelayByDoc[docId] ?: suggestDelayMillis
        if (delay > 0) kotlinx.coroutines.delay(delay)
        failSuggestTags?.invoke()
        if (docId in failSuggestTagsForDocs) error("suggest failed for $docId")
        return suggestionsByDoc[docId] ?: suggestions
    }

    /** Backs [suggestMetadata] for docs without a [metadataByDoc] entry. */
    var metadataSuggestion: MetadataSuggestion = MetadataSuggestion.EMPTY

    /** Per-doc [suggestMetadata] results (wins over [metadataSuggestion]). */
    var metadataByDoc: Map<String, MetadataSuggestion> = emptyMap()

    /** docIds passed to [suggestMetadata], in dispatch order. */
    val suggestMetadataCalls = mutableListOf<String>()
    var failSuggestMetadata: (() -> Nothing)? = null

    /** suggestMetadata throws for these docIds only (per-doc partial-failure testing). */
    var failSuggestMetadataForDocs: Set<String> = emptySet()

    override suspend fun suggestMetadata(docId: String): MetadataSuggestion {
        suggestMetadataCalls += docId
        if (suggestDelayMillis > 0) kotlinx.coroutines.delay(suggestDelayMillis)
        failSuggestMetadata?.invoke()
        if (docId in failSuggestMetadataForDocs) error("suggest metadata failed for $docId")
        return metadataByDoc[docId] ?: metadataSuggestion
    }

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

    override suspend fun suggestEdits(
        docId: String,
        request: AiEditRequest,
    ): AiEditSuggestion {
        // Record at entry (before the delay) so the call list reflects dispatch order
        // deterministically even when calls run concurrently.
        suggestEditsCalls += docId
        suggestEditsRequests += request
        val delay = suggestDelayByDoc[docId] ?: suggestDelayMillis
        if (delay > 0) kotlinx.coroutines.delay(delay)
        failSuggestEdits?.invoke()
        if (docId in failSuggestEditsForDocs) error("suggest edits failed for $docId")
        return editByDoc[docId] ?: edit
    }
}
