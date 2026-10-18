package dev.npn.app.ui.detail

import dev.npn.app.ui.common.MessageSink
import dev.npn.app.ui.common.uiText
import dev.npn.core.ai.AiEditRequest
import dev.npn.core.ai.AiFieldConfig
import dev.npn.core.ai.AiFieldMode
import dev.npn.core.ai.FieldSuggestion
import dev.npn.core.ai.TagSuggestion
import dev.npn.core.app.LibrarySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_no_ai_metadata_suggestions
import no_papers_needed.composeapp.generated.resources.common_no_ai_tag_suggestions
import no_papers_needed.composeapp.generated.resources.common_save_field_failed
import no_papers_needed.composeapp.generated.resources.detail_add_tag_failed
import no_papers_needed.composeapp.generated.resources.detail_suggest_details_failed
import no_papers_needed.composeapp.generated.resources.detail_suggest_tags_failed
import org.jetbrains.compose.resources.StringResource

/**
 * AI tag-suggestion state for the detail screen. [suggesting] is the in-flight busy flag, and
 * [suggestions] are the not-yet-accepted new-tag proposals.
 */
data class AiTagState(
    val suggesting: Boolean = false,
    val suggestions: List<TagSuggestion> = emptyList(),
)

/**
 * AI field-suggestion state for the detail screen. [suggesting] is the in-flight busy flag, and
 * [suggestion] holds the not-yet-accepted per-field proposals.
 */
data class AiMetadataState(
    val suggesting: Boolean = false,
    val suggestion: FieldSuggestion = FieldSuggestion.EMPTY,
)

/** What the metadata panel asks of the AI: suggestions for one document's tags and fields. */
interface AiSuggestionActions {
    fun suggestAiTags()

    fun acceptAiTag(tag: String)

    fun dismissAiTag(tag: String)

    fun clearAiTagSuggestions()

    fun suggestAiMetadata()

    /**
     * Accept a field's suggestion if [onStart] permits its value. [onFinished] releases the caller's
     * write guard on success, failure or cancellation, saying whether the write landed.
     */
    fun acceptAiMetadata(
        key: String,
        onStart: (String) -> Boolean = { true },
        onFinished: (Boolean) -> Unit = {},
    )

    fun dismissAiMetadata(key: String)
}

/**
 * The AI suggestions for one document ([docId]): asking for them, and accepting or dismissing each
 * one. Owned by [DocDetailViewModel], which delegates its [AiSuggestionActions] here and lends it
 * its [scope], its [messages] and the [metadataMutex] its own metadata writes take, so an accepted
 * suggestion never interleaves with another write to the document.
 */
internal class DocAiSuggestions(
    private val docId: String,
    session: LibrarySession,
    private val scope: CoroutineScope,
    private val messages: MessageSink,
    private val metadataMutex: Mutex,
) : AiSuggestionActions {
    private val ai = session.aiSuggest
    private val meta = session.meta
    private val fieldRegistry = session.fieldRegistry

    private val _tags = MutableStateFlow(AiTagState())
    val tags: StateFlow<AiTagState> = _tags.asStateFlow()

    private val _metadata = MutableStateFlow(AiMetadataState())
    val metadata: StateFlow<AiMetadataState> = _metadata.asStateFlow()

    /**
     * Ask the AI endpoint for new-tag suggestions from this doc's indexed text. No-op while a
     * request is already in flight. Empty results surface an informational message (the document
     * likely has no OCR text yet); failures surface the error.
     */
    override fun suggestAiTags() {
        if (_tags.value.suggesting) return
        _tags.update { it.copy(suggesting = true) }
        scope.launch {
            val suggestions =
                suggest(
                    failed = Res.string.detail_suggest_tags_failed,
                    nothing = Res.string.common_no_ai_tag_suggestions,
                    isEmpty = List<TagSuggestion>::isEmpty,
                ) { ai.suggestEdits(docId, AiEditRequest(tags = true)).addTags }
            _tags.value = AiTagState(suggestions = suggestions.orEmpty())
        }
    }

    /**
     * Ask the AI endpoint for this document's suggestions through [ask]: the answer, or null when it
     * failed, which is reported with [failed]. An answer with nothing in it says [nothing].
     */
    private suspend fun <T : Any> suggest(
        failed: StringResource,
        nothing: StringResource,
        isEmpty: (T) -> Boolean,
        ask: suspend () -> T,
    ): T? {
        var answer: T? = null
        messages.reporting(failed) { answer = ask() }
        if (answer?.let(isEmpty) == true) messages.message(uiText(nothing))
        return answer
    }

    /** Accept a suggestion: add it as a real tag (normal journal path) and drop it from the list. */
    override fun acceptAiTag(tag: String) {
        scope.launch {
            messages.reporting(Res.string.detail_add_tag_failed) {
                metadataMutex.withLock {
                    meta.addTag(listOf(docId), tag)
                    dismissAiTag(tag)
                }
            }
        }
    }

    override fun dismissAiTag(tag: String) {
        _tags.update { state ->
            state.copy(suggestions = state.suggestions.filterNot { it.tag == tag })
        }
    }

    override fun clearAiTagSuggestions() {
        _tags.update { it.copy(suggestions = emptyList()) }
    }

    /**
     * Ask the AI endpoint for a value for every aiEnabled field from this doc's indexed text.
     * No-op while a request is already in flight. Empty results surface an informational message
     * (no OCR text yet, or nothing beyond the current values); failures surface the error.
     */
    override fun suggestAiMetadata() {
        if (_metadata.value.suggesting) return
        _metadata.update { it.copy(suggesting = true) }
        scope.launch {
            val suggestion =
                suggest(
                    failed = Res.string.detail_suggest_details_failed,
                    nothing = Res.string.common_no_ai_metadata_suggestions,
                    isEmpty = FieldSuggestion::isEmpty,
                ) {
                    // Every field the user marked for AI, in ALL mode: this is an explicit request to
                    // second-guess what is there, unlike the automatic pass which only fills gaps.
                    val fields =
                        fieldRegistry.current().filter { it.aiEnabled }.map { AiFieldConfig(it.key, AiFieldMode.ALL) }
                    ai.suggestEdits(docId, AiEditRequest(fields = fields)).metadata
                }
            _metadata.value = AiMetadataState(suggestion = suggestion ?: FieldSuggestion.EMPTY)
        }
    }

    /** Accept one field's suggestion: write it via the normal journal path and drop it. */
    override fun acceptAiMetadata(
        key: String,
        onStart: (String) -> Boolean,
        onFinished: (Boolean) -> Unit,
    ) {
        val suggested = _metadata.value.suggestion[key] ?: return
        if (!onStart(suggested)) return
        var accepted = false
        val operation =
            scope.launch {
                accepted =
                    messages.reporting(Res.string.common_save_field_failed) {
                        metadataMutex.withLock { meta.setField(docId, key, suggested) }
                    }
                // A newer suggestion for the field, asked for while the write ran, stays.
                if (accepted) {
                    _metadata.update { state ->
                        if (state.suggestion[key] != suggested) return@update state
                        state.copy(suggestion = state.suggestion.without(key))
                    }
                }
            }
        // A cancelled scope may prevent the launch body from starting, so a finally inside it
        // cannot release a guard acquired synchronously by onStart.
        operation.invokeOnCompletion { onFinished(accepted) }
    }

    override fun dismissAiMetadata(key: String) {
        _metadata.update { it.copy(suggestion = it.suggestion.without(key)) }
    }
}
