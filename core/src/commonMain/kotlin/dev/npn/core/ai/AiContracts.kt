package dev.npn.core.ai

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
) {
    val configured: Boolean
        get() = enabled && baseUrl.isNotBlank() && model.isNotBlank()

    companion object {
        val DISABLED = AiConfig()
    }
}

enum class AiRole { USER, ASSISTANT }

/** One turn of an assistant conversation, oldest first. */
data class AiChatTurn(val role: AiRole, val text: String)

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
     * conversation. The returned text may reference documents as `[doc:<docId>]`.
     */
    suspend fun ask(history: List<AiChatTurn>, question: String): String

    /**
     * Suggest new tags for [docId] from its indexed text, preferring the library's existing
     * tag vocabulary. Returns suggestions only (never writes); accepting one goes through
     * the normal MetadataWriter/journal path. Empty when the doc has no indexed text yet.
     */
    suspend fun suggestTags(docId: String): List<String>
}
