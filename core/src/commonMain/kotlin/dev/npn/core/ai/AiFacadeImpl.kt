package dev.npn.core.ai

import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import dev.npn.core.app.AiTagging
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.toFieldDef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Koog-backed implementation of the AI facets ([AiChat], [AiSuggest], [AiOcr]). Reads the endpoint
 * configuration live on every call (settings can change between calls) and caches the built
 * runtime while the config is unchanged. Purely a read-side consumer of the Room index; all
 * writes stay with MetadataWriter/JournalCoordinator.
 */
class AiFacadeImpl(
    private val db: NpnDatabase,
    private val configProvider: suspend () -> AiConfig,
) : AiChat,
    AiSuggest,
    AiOcr {
    private val chatHistoryStore = RoomChatHistoryProvider(db)
    private val chatSessionStore = RoomChatSessionStore(db)

    private val runtimeMutex = Mutex()
    private var cachedRuntime: Pair<RuntimeKey, AiRuntime>? = null

    /**
     * The subset of [AiConfig] that actually shapes the built [AiRuntime] (endpoint client + model
     * — see [AiRuntime.build]). Cache identity is keyed on this so tuning a runtime-irrelevant
     * setting like [AiConfig.maxTagsPerDoc], [AiConfig.maxNewTagsPerDoc] or the reasoning levels
     * (applied per call, not per client) does NOT rebuild the client and abort in-flight requests.
     */
    private data class RuntimeKey(
        val enabled: Boolean,
        val provider: AiProvider,
        val baseUrl: String,
        val apiKey: String,
        val model: String,
    )

    private fun AiConfig.runtimeKey() = RuntimeKey(enabled, provider, baseUrl, apiKey, model)

    override suspend fun configured(): Boolean = configProvider().configured

    override fun ask(
        sessionId: String,
        question: String,
    ): Flow<AiChatEvent> =
        flow {
            touchChatSession(sessionId, question)
            // Resolve the runtime lazily inside the cold flow so config errors surface on collection
            // (as flow exceptions), matching the "throws on endpoint errors" contract.
            val runtime = runtime()
            // The field vocabulary is read per turn so a field defined a minute ago is already known.
            val fields = db.fieldDefDao().all().mapNotNull { it.toFieldDef() }
            emitAll(
                KoogChatAgent(
                    executor = runtime.executor,
                    model = runtime.model,
                    db = db,
                    toolSet = LibraryAiTools.toolSetFor(db, fields),
                    systemPrompt = AiPrompts.chatSystem(promptContext(configProvider()), fields),
                    maxIterations = MAX_AGENT_ITERATIONS,
                    params = AiRuntime.chatParams(configProvider()),
                ).run(question, sessionId),
            )
            // Endpoints that want a conversation id per request (the OpenCode Go gateway's
            // x-opencode-session header) read it off the coroutine context, since Koog exposes no
            // seam to pass one down. flowOn puts it there for the whole turn without changing
            // dispatchers. Cheap and inert for every other provider.
        }.flowOn(AiGatewaySession(sessionId))

    override suspend fun chatTranscriptFor(sessionId: String): List<AiChatHistoryItem> =
        KoogChatHistoryMapper.map(chatHistoryStore.load(sessionId))

    override suspend fun chatSessions(): List<AiChatSession> {
        val sessions = chatSessionStore.load()
        val nonEmpty = sessions.filter { chatHistoryStore.load(it.id).isNotEmpty() }
        if (nonEmpty.size != sessions.size) chatSessionStore.save(nonEmpty)
        if (nonEmpty.isNotEmpty()) return nonEmpty.sortedByDescending { it.updatedAt }
        if (chatHistoryStore.load(CHAT_CONVERSATION_ID).isEmpty()) return emptyList()
        val legacy = AiChatSession(CHAT_CONVERSATION_ID, "Assistant chat", 0L)
        chatSessionStore.save(listOf(legacy))
        return listOf(legacy)
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun createChatSession(): AiChatSession {
        val session = AiChatSession(Uuid.random().toString(), NEW_SESSION_TITLE, now())
        chatSessionStore.save((chatSessionStore.load() + session).sortedByDescending { it.updatedAt })
        return session
    }

    override suspend fun deleteChatSession(sessionId: String) {
        chatHistoryStore.delete(sessionId)
        chatSessionStore.save(chatSessionStore.load().filterNot { it.id == sessionId })
    }

    override suspend fun clearChatSession(sessionId: String) {
        chatHistoryStore.delete(sessionId)
        val sessions = chatSessionStore.load()
        chatSessionStore.save(
            sessions.map { session ->
                if (session.id == sessionId) session.copy(title = NEW_SESSION_TITLE, updatedAt = now()) else session
            },
        )
    }

    private suspend fun touchChatSession(
        sessionId: String,
        question: String,
    ) {
        val sessions = chatSessionStore.load()
        val existing = sessions.firstOrNull { it.id == sessionId }
        val title =
            existing?.title?.takeUnless { it == NEW_SESSION_TITLE }
                ?: question
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(MAX_SESSION_TITLE_CHARS)
                    .ifBlank { NEW_SESSION_TITLE }
        val touched =
            (
                sessions.filterNot { it.id == sessionId } +
                    AiChatSession(sessionId, title, now())
            ).sortedByDescending { it.updatedAt }
        chatSessionStore.save(touched)
    }

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    override suspend fun suggestEdits(
        docId: String,
        request: AiEditRequest,
    ): AiEditSuggestion {
        val runtime = runtime()
        val config = configProvider()
        val doc = db.documentDao().getByDocId(docId) ?: return AiEditSuggestion.EMPTY
        val text = db.ftsDao().contentForDoc(docId)?.takeIf { it.isNotBlank() }
        // No OCR text yet: field extraction is impossible, but a tag-only run can still work off
        // the current tag list and vocabulary. This is the single decided rule for every flow.
        if (text == null && request.fields.isNotEmpty()) return AiEditSuggestion.EMPTY
        val currentTags = db.tagDao().tagsForDocument(docId)
        val vocabulary = db.tagDao().all().map { it.name }
        val fields = fieldSpecs(request)
        val currentFields =
            db
                .documentFieldDao()
                .fieldsForDocs(
                    listOf(docId),
                ).associate { it.fieldKey to it.fieldValue }
        val maxTags = (request.maxTags ?: config.maxTagsPerDoc).coerceIn(AiTagging.RANGE)
        val maxNew = (request.maxNewTags ?: config.maxNewTagsPerDoc).coerceIn(AiTagging.NEW_TAGS_RANGE)
        val thePrompt =
            prompt("npn-edit-suggest", AiRuntime.suggestionParams(config)) {
                system(
                    AiPrompts.editSystem(
                        request = request,
                        fields = fields,
                        max = maxTags,
                        maxNew = maxNew,
                        vocabulary = vocabulary,
                        context = promptContext(config),
                    ),
                )
                user(AiPrompts.editUser(doc.fileName, currentFields, fields, request, currentTags, text))
            }
        val response = runtime.executor.execute(thePrompt, runtime.model)
        return AiText.parseEditSuggestion(
            response.textContent(),
            currentFields = currentFields,
            fields = fields,
            currentTags = currentTags,
            vocabulary = vocabulary,
            maxTags = maxTags,
            maxNewTags = maxNew,
        )
    }

    /**
     * The requested fields resolved against the registry, in request order. A TEXT field below the
     * vocabulary threshold carries its existing values for verbatim reuse; a key the registry does
     * not know is asked for as plain text rather than dropped, so a run configured before a field
     * was deleted still gets an answer for it.
     */
    private suspend fun fieldSpecs(request: AiEditRequest): List<AiFieldSpec> {
        val defs =
            db
                .fieldDefDao()
                .all()
                .mapNotNull { it.toFieldDef() }
                .associateBy { it.key }
        return request.fields.map { cfg ->
            val def = defs[cfg.key] ?: AiFieldSpec.unregistered(cfg.key)
            val vocabulary =
                if (AiFieldSpec.wantsVocabulary(def)) {
                    db
                        .documentFieldDao()
                        .distinctValues(def.key, AiFieldSpec.VOCABULARY_THRESHOLD + 1)
                        .takeIf { it.size <= AiFieldSpec.VOCABULARY_THRESHOLD }
                        .orEmpty()
                } else {
                    emptyList()
                }
            AiFieldSpec(def, cfg.mode, vocabulary)
        }
    }

    override suspend fun ocrConfigured(): Boolean = configProvider().configured

    override suspend fun transcribePage(
        png: ByteArray,
        lang: String,
    ): String {
        val thePrompt = pagePrompt(png, lang, configProvider())
        return transcribeWithRetry(PAGE_ATTEMPTS) { executePage(thePrompt) }
    }

    private suspend fun executePage(thePrompt: Prompt): String? {
        val runtime = runtime()
        return AiText.parseTranscription(runtime.executor.execute(thePrompt, runtime.model).textContent())
    }

    private fun pagePrompt(
        png: ByteArray,
        lang: String,
        config: AiConfig,
    ): Prompt =
        prompt("npn-ocr-page", AiRuntime.ocrParams(config)) {
            system(AiPrompts.ocrSystem(promptContext(config)))
            user {
                text(AiPrompts.pageRequest(lang))
                image(
                    AttachmentSource.Image(
                        content = AttachmentContent.Binary.Bytes(png),
                        format = "png",
                        mimeType = "image/png",
                        fileName = "page.png",
                    ),
                )
            }
        }

    private suspend fun runtime(): AiRuntime {
        val config = configProvider()
        check(config.configured) { "AI endpoint is not configured (see Settings > AI assistant)" }
        val key = config.runtimeKey()
        return runtimeMutex.withLock {
            val cached = cachedRuntime
            cached?.takeIf { it.first == key }?.second ?: run {
                // Runtime-relevant config changed: close the stale runtime's HTTP client before
                // swapping. Any in-flight request still holding the old runtime will fail once its
                // client is closed — acceptable, since the endpoint changed out from under it.
                cached?.second?.close()
                AiRuntime.build(config).also { cachedRuntime = key to it }
            }
        }
    }

    /**
     * The language + date facts the prompts need (see [AiPromptContext]). Derived from the live
     * config on every call for the same reason the runtime is: the user can switch the app's
     * language between two calls, and a run started yesterday must not keep yesterday's date.
     */
    private fun promptContext(config: AiConfig): AiPromptContext = AiPromptContext.of(config.appLanguage)

    private companion object {
        const val MAX_AGENT_ITERATIONS = 15

        const val CHAT_CONVERSATION_ID = "main"
        const val NEW_SESSION_TITLE = "New chat"
        const val MAX_SESSION_TITLE_CHARS = 60

        /**
         * Calls one page may cost: the attempt plus a single retry (see [transcribeWithRetry]).
         *
         * One retry, and deliberately only one. Vision models do occasionally answer a page with a
         * refusal, an empty message or a transient endpoint error, and asking again fixes it — but
         * this runs once per PAGE, under a pipeline that already retries the whole document
         * ([dev.npn.core.ocr.OcrConfig.maxAttempts]), so anything more multiplies a systematically
         * failing endpoint into a bill instead of an error.
         */
        const val PAGE_ATTEMPTS = 2
    }
}
