package dev.npn.core.ai

import kotlinx.coroutines.flow.Flow

/**
 * Device-local configuration for the bring-your-own AI endpoint. Values live in the app
 * settings (never in the journal: the key must not leak to synced replicas). Any
 * OpenAI-compatible chat-completions server works: OpenAI, OpenRouter, Ollama, llama.cpp,
 * LiteLLM gateways, etc.
 */
data class AiConfig(
    val enabled: Boolean = false,
    /** Server root, e.g. `https://api.openai.com` or `http://localhost:11434`. */
    val baseUrl: String = "",
    /** Bearer token; may stay blank for local endpoints that skip auth. */
    val apiKey: String = "",
    /** Model id as the endpoint knows it, e.g. `gpt-4.1-mini` or `qwen3:8b`. */
    val model: String = "",
    /** User-configured cap on tag suggestions produced per document (device-local, 1..15). */
    val maxTagsPerDoc: Int = 8,
    /** User-configured cap on genuinely-new (non-vocabulary) tags per document (device-local, 0..10). */
    val maxNewTagsPerDoc: Int = 5,
) {
    val configured: Boolean
        get() = enabled && baseUrl.isNotBlank() && model.isNotBlank()

    companion object {
        val DISABLED = AiConfig()
    }
}

/**
 * One AI tag proposal for a document. [tag] is already canonicalized to the library's exact
 * spelling when it matches an existing vocabulary entry case-insensitively; [isNew] is true when
 * no existing tag matches (the suggestion would introduce a brand-new tag).
 */
data class TagSuggestion(val tag: String, val isNew: Boolean)

enum class AiRole { USER, ASSISTANT }

/** One turn of an assistant conversation, oldest first. */
data class AiChatTurn(val role: AiRole, val text: String)

/**
 * Streaming event from [AiFacade.ask]. The cold flow emits any number of [TextDelta] and
 * tool events, then exactly one terminal [Done]. Network/endpoint errors propagate as flow
 * exceptions (the caller maps them to one-shot messages); the flow is cancellable at any point.
 */
sealed interface AiChatEvent {
    /** A chunk of assistant answer text to append to the current answer. */
    data class TextDelta(val text: String) : AiChatEvent

    /** The agent decided to call [name] with the raw arguments JSON [argsJson]. */
    data class ToolCallStarted(val name: String, val argsJson: String) : AiChatEvent

    /** The tool [name] finished; [result] is its (already bounded) text output. */
    data class ToolCallFinished(
        val name: String,
        val argsJson: String,
        val result: String,
    ) : AiChatEvent

    /** Terminal event carrying the full accumulated answer text. */
    data class Done(val fullText: String) : AiChatEvent
}

/**
 * AI features scoped to one opened library. All model calls go to the user-configured
 * OpenAI-compatible endpoint; nothing here writes to the journal. Callers surface
 * failures as UI messages ([ask]/[suggestTags] throw on network/endpoint errors).
 */
interface AiFacade {

    /** Current endpoint configuration (live settings values). */
    suspend fun config(): AiConfig

    /**
     * Answer [question] with a tool-calling agent that can search the library index
     * (OCR text, tags, filenames, metadata). [history] carries the previous turns of the
     * conversation. Returns a cold [Flow] of [AiChatEvent]s: answer text streams in as
     * [AiChatEvent.TextDelta]s, tool use surfaces as [AiChatEvent.ToolCallStarted]/
     * [AiChatEvent.ToolCallFinished], and the run ends with one [AiChatEvent.Done]. The answer
     * text may reference documents as `[doc:<docId>]`. Collection starts the request; cancelling
     * the collection stops it. Network/endpoint errors are thrown from the flow.
     */
    fun ask(history: List<AiChatTurn>, question: String): Flow<AiChatEvent>

    /**
     * Suggest new tags for [docId] from its indexed text, preferring the library's existing
     * tag vocabulary. Suggestions matching an existing tag are canonicalized to its exact
     * spelling; [TagSuggestion.isNew] marks genuinely new tags (capped per doc, ranked last).
     * Returns suggestions only (never writes); accepting one goes through the normal
     * MetadataWriter/journal path. Empty when the doc has no indexed text yet.
     */
    suspend fun suggestTags(docId: String): List<TagSuggestion>
}
