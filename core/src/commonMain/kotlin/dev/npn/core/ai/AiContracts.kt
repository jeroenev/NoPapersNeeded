package dev.npn.core.ai

import kotlinx.coroutines.flow.Flow

/**
 * The AI provider a configuration talks to. Every entry maps onto a dedicated Koog `LLMClient`
 * except [CUSTOM], which reuses the OpenAI-compatible chat-completions client against a
 * user-supplied server root (Ollama-compatible gateways, llama.cpp, LiteLLM, ...). Enum order is
 * the presentation order in settings; [CUSTOM] (fully manual) stays last by design.
 */
enum class AiProvider(
    /** Brand name shown in the provider picker (not localized: these are product names). */
    val displayName: String,
    /** Server root used when [AiConfig.baseUrl] is blank; null when the user must supply one. */
    val defaultBaseUrl: String?,
    /** Whether requests to this provider are unauthenticated without an API key. */
    val requiresApiKey: Boolean,
    /** Whether the settings UI offers the base-URL field (local/self-hosted endpoints). */
    val editableBaseUrl: Boolean,
) {
    OPENAI("OpenAI", "https://api.openai.com", requiresApiKey = true, editableBaseUrl = false),
    ANTHROPIC("Anthropic", "https://api.anthropic.com", requiresApiKey = true, editableBaseUrl = false),
    GOOGLE("Google", "https://generativelanguage.googleapis.com", requiresApiKey = true, editableBaseUrl = false),
    MISTRAL("Mistral", "https://api.mistral.ai", requiresApiKey = true, editableBaseUrl = false),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com", requiresApiKey = true, editableBaseUrl = false),
    OPENROUTER("OpenRouter", "https://openrouter.ai", requiresApiKey = true, editableBaseUrl = false),
    OLLAMA("Ollama", "http://localhost:11434", requiresApiKey = false, editableBaseUrl = true),
    CUSTOM("Custom (OpenAI-compatible)", null, requiresApiKey = false, editableBaseUrl = true),
}

/**
 * How much reasoning/thinking effort a model call may spend, chosen separately for the chat
 * assistant and for AI-edit suggestion calls. [DEFAULT] keeps the endpoint's own behaviour.
 * Mapping is provider-specific (OpenAI `reasoning_effort`, Anthropic/Google thinking budgets,
 * Ollama `think`); providers without a supported knob in Koog 1.1.1 (OpenRouter, DeepSeek,
 * Mistral) always behave like [DEFAULT] — see `AiRuntime.reasoningParams`.
 */
enum class AiReasoningLevel {
    /** No override: whatever the provider does out of the box for the model. */
    DEFAULT,

    /** Reasoning explicitly disabled (where the provider supports turning it off). */
    NONE,
    LOW,
    MEDIUM,
    HIGH,
}

/**
 * Device-local configuration for the AI endpoint. Values live in the app settings (never in
 * the journal: the key must not leak to synced replicas). [provider] selects a Koog client;
 * [AiProvider.CUSTOM] works with any OpenAI-compatible chat-completions server.
 */
data class AiConfig(
    val enabled: Boolean = false,
    /** Which provider requests go to; [AiProvider.CUSTOM] is the fully manual escape hatch. */
    val provider: AiProvider = AiProvider.OPENAI,
    /**
     * Server root override, e.g. `https://api.openai.com` or `http://localhost:11434`. Blank
     * means the provider's [AiProvider.defaultBaseUrl]; required only for [AiProvider.CUSTOM].
     */
    val baseUrl: String = "",
    /** Bearer token; may stay blank for local endpoints that skip auth. */
    val apiKey: String = "",
    /** Model id as the endpoint knows it, e.g. `gpt-4.1-mini` or `qwen3:8b`. */
    val model: String = "",
    /** Reasoning effort for the chat assistant. Default: endpoint behaviour. */
    val reasoningAssistant: AiReasoningLevel = AiReasoningLevel.DEFAULT,
    /** Reasoning effort for AI-edit suggestion calls. Default: LOW (cheap extraction tasks). */
    val reasoningEdit: AiReasoningLevel = AiReasoningLevel.LOW,
    /** User-configured cap on tag suggestions produced per document (device-local, 1..15). */
    val maxTagsPerDoc: Int = 8,
    /** User-configured cap on genuinely-new (non-vocabulary) tags per document (device-local, 0..10). */
    val maxNewTagsPerDoc: Int = 5,
) {
    /** The effective server root: the explicit override, or the provider default. */
    val effectiveBaseUrl: String
        get() = baseUrl.ifBlank { provider.defaultBaseUrl.orEmpty() }

    val configured: Boolean
        get() =
            enabled &&
                model.isNotBlank() &&
                effectiveBaseUrl.isNotBlank() &&
                (!provider.requiresApiKey || apiKey.isNotBlank())

    companion object {
        val DISABLED = AiConfig()
    }
}

/**
 * One AI tag proposal for a document. [tag] is already canonicalized to the library's exact
 * spelling when it matches an existing vocabulary entry case-insensitively; [isNew] is true when
 * no existing tag matches (the suggestion would introduce a brand-new tag).
 */
data class TagSuggestion(
    val tag: String,
    val isNew: Boolean,
)

/**
 * AI proposals for a document's single-value properties. Each field is null when the model had no
 * (validated) suggestion or the suggestion matched the document's current value. [correspondent]
 * is canonicalized to the library's exact spelling when it matches an existing correspondent
 * case-insensitively; [docDate] is always a valid `yyyy-MM-dd` ISO date.
 */
data class MetadataSuggestion(
    val title: String? = null,
    val correspondent: String? = null,
    val docDate: String? = null,
) {
    val isEmpty: Boolean
        get() = title == null && correspondent == null && docDate == null

    companion object {
        val EMPTY = MetadataSuggestion()
    }
}

/** A single-value document property the AI can suggest a value for. */
enum class AiField { TITLE, CORRESPONDENT, DOC_DATE }

/**
 * Whether a field suggestion should be produced for every document or only documents that are
 * currently missing that field's value.
 */
enum class AiFieldMode {
    /** Suggest a value even when the document already has one (the user may accept a replacement). */
    ALL,

    /** Only suggest when the field is currently empty; existing values are never touched. */
    UNFILLED,
}

/** One field to ask about in an edit run, with the user's [mode]. */
data class AiFieldConfig(
    val field: AiField,
    val mode: AiFieldMode = AiFieldMode.UNFILLED,
)

/**
 * One AI-edit run's per-document configuration: which fields to autofill ([fields]), whether to
 * also propose tag additions ([tags]) and/or tag removals ([tagRemovals]), the optional per-call
 * tag caps ([maxTags]/[maxNewTags]; null means the configured defaults), and an optional free-text
 * [customPrompt] the user typed (e.g. "merge tags that differ only in case").
 */
data class AiEditRequest(
    val fields: List<AiFieldConfig> = emptyList(),
    val tags: Boolean = false,
    val tagRemovals: Boolean = false,
    val maxTags: Int? = null,
    val maxNewTags: Int? = null,
    val customPrompt: String? = null,
)

/**
 * AI-proposed edits for one document. [addTags] carries canonicalized additions (see
 * [TagSuggestion]), [removeTags] are tags already on the document that the model proposes
 * removing (verbatim current spelling), and [metadata] carries the validated single-value field
 * suggestions (subject to the run's [AiFieldMode]).
 */
data class AiEditSuggestion(
    val addTags: List<TagSuggestion> = emptyList(),
    val removeTags: List<String> = emptyList(),
    val metadata: MetadataSuggestion = MetadataSuggestion.EMPTY,
) {
    val isEmpty: Boolean
        get() = addTags.isEmpty() && removeTags.isEmpty() && metadata.isEmpty

    companion object {
        val EMPTY = AiEditSuggestion()
    }
}

enum class AiRole { USER, ASSISTANT }

/** One turn of an assistant conversation, oldest first. */
data class AiChatTurn(
    val role: AiRole,
    val text: String,
)

/**
 * Streaming event from [AiFacade.ask]. The cold flow emits any number of [TextDelta] and
 * tool events, then exactly one terminal [Done]. Network/endpoint errors propagate as flow
 * exceptions (the caller maps them to one-shot messages); the flow is cancellable at any point.
 */
sealed interface AiChatEvent {
    /** A chunk of assistant answer text to append to the current answer. */
    data class TextDelta(
        val text: String,
    ) : AiChatEvent

    /** The agent decided to call [name] with the raw arguments JSON [argsJson]. */
    data class ToolCallStarted(
        val name: String,
        val argsJson: String,
    ) : AiChatEvent

    /** The tool [name] finished; [result] is its (already bounded) text output. */
    data class ToolCallFinished(
        val name: String,
        val argsJson: String,
        val result: String,
    ) : AiChatEvent

    /** Terminal event carrying the full accumulated answer text. */
    data class Done(
        val fullText: String,
    ) : AiChatEvent
}

/**
 * AI features scoped to one opened library. All model calls go to the user-configured
 * OpenAI-compatible endpoint; nothing here writes to the journal. Callers surface
 * failures as UI messages ([ask]/[suggestTags]/[suggestMetadata] throw on network/endpoint
 * errors).
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
    fun ask(
        history: List<AiChatTurn>,
        question: String,
    ): Flow<AiChatEvent>

    /**
     * Suggest new tags for [docId] from its indexed text, preferring the library's existing
     * tag vocabulary. Suggestions matching an existing tag are canonicalized to its exact
     * spelling; [TagSuggestion.isNew] marks genuinely new tags (capped per doc, ranked last).
     * Returns suggestions only (never writes); accepting one goes through the normal
     * MetadataWriter/journal path. Empty when the doc has no indexed text yet.
     *
     * [maxTags] and [maxNewTags] are optional per-call overrides of the configured defaults
     * (from settings): null means use the configured default. Both are clamped to
     * `AiTagging.RANGE` / `AiTagging.NEW_TAGS_RANGE` respectively.
     */
    suspend fun suggestTags(
        docId: String,
        maxTags: Int? = null,
        maxNewTags: Int? = null,
    ): List<TagSuggestion>

    /**
     * Suggest single-value properties (title, correspondent, document date) for [docId] from its
     * indexed text. Correspondent suggestions prefer the library's existing correspondents and are
     * canonicalized to their exact spelling; dates are validated ISO `yyyy-MM-dd`; suggestions
     * matching the document's current values are dropped. Returns suggestions only (never
     * writes); accepting one goes through the normal MetadataWriter/journal path. Empty when the
     * doc has no indexed text yet.
     */
    suspend fun suggestMetadata(docId: String): MetadataSuggestion

    /**
     * Propose edits for [docId] from its indexed text and current metadata, honouring [request]'s
     * field selection/modes, tag-add/removal flags and optional [AiEditRequest.customPrompt]. All
     * requested pieces come back from ONE model request — never a separate call per section.
     * Tag additions are canonicalized to the library vocabulary, tag removals are filtered to tags
     * actually on the document, and field values are validated as in [suggestMetadata] (plus the
     * per-field [AiFieldMode]). Returns suggestions only (never writes); accepting one goes
     * through the normal MetadataWriter/journal path. Empty when the doc has no indexed text yet
     * and field suggestions were requested (tag-only runs can still work off the tag list).
     */
    suspend fun suggestEdits(
        docId: String,
        request: AiEditRequest,
    ): AiEditSuggestion
}
