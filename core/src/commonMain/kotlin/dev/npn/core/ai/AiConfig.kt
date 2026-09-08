package dev.npn.core.ai

/**
 * The AI provider a configuration talks to. Every entry maps onto a dedicated Koog `LLMClient`
 * except [OPENCODE_GO] and [CUSTOM], which reuse the OpenAI-compatible chat-completions client
 * (the fixed OpenCode Go gateway, resp. a user-supplied server root: Ollama-compatible gateways,
 * llama.cpp, LiteLLM, ...). Enum order is the presentation order in settings; [CUSTOM] (fully
 * manual) stays last by design.
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
    OPENCODE_GO("OpenCode Go", "https://opencode.ai/zen/go", requiresApiKey = true, editableBaseUrl = false),
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
    /**
     * Whether a document is enriched automatically (existing-vocabulary tags + empty single-value
     * fields) when *this device* OCRs it. Device-local: it spends the user's own endpoint quota, so
     * it must not sync to other replicas. Off by default — it writes without a review step.
     *
     * Tied to producing the text rather than indexing it, so a document another replica already
     * OCR'd is not enriched when its sidecar arrives here. That also means enabling this on a
     * device that only receives synced documents does nothing; the enriching device is the one that
     * scans or imports.
     */
    val autoOnOcr: Boolean = false,
    /**
     * Whether the endpoint is this device's *automatic* OCR engine ([AiOcr]), i.e. every document
     * this device OCRs is transcribed by the vision model — including documents a lower-ranked
     * engine already did, which the pipeline then upgrades.
     *
     * Device-local and off by default, because it is the most expensive switch in the app: one
     * model call per page, not per document. Off does not mean unavailable — a per-document "OCR
     * with AI" request still runs (`OcrFacade.ocrWithAi`); it means nothing starts one unattended.
     */
    val ocrAuto: Boolean = false,
    /**
     * The language the app UI is running in, as a language tag (`nl`), or null when it could not be
     * determined. Not an endpoint knob: it is here because prompts need it (see [AiPromptContext])
     * and this is already the live, device-local projection of settings that every AI call reads.
     * Deliberately absent from `AiFacadeImpl`'s runtime cache key — changing the app's language
     * must not tear down an HTTP client or abort in-flight requests.
     */
    val appLanguage: String? = null,
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
 * Whether AI features can run at all right now. Shared by both AI facets ([AiChat], [AiSuggest])
 * because every consumer gates the same way: features are hidden or disabled until the endpoint
 * is configured. Only the boolean crosses this seam — [AiConfig] itself belongs to settings, and
 * no consumer of an AI facet needs its values.
 */
interface AiAvailability {
    /** True when the configured endpoint is complete enough to make model calls. */
    suspend fun configured(): Boolean
}
