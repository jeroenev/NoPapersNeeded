package dev.npn.core.ai

import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import dev.npn.core.app.AiTagging
import dev.npn.core.db.KeyValueDao
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.defs
import dev.npn.core.journal.EpochClock
import dev.npn.core.journal.SystemEpochClock
import dev.npn.core.ocr.OcrEngine
import dev.npn.core.ocr.OcrEngineId
import dev.npn.core.work.EndpointHealth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Koog-backed implementation of the AI facets ([AiChat], [AiSuggest]) and of the vision OCR engine
 * ([ocrEngine]). Reads the endpoint
 * configuration live on every call (settings can change between calls) and caches the built
 * runtime while the config is unchanged. Purely a read-side consumer of the Room index; all
 * writes stay with MetadataWriter/JournalCoordinator.
 *
 * @param chats where chat sessions and conversations are kept: this device's own state, which an
 *  index rebuild must not take (the session passes `DeviceDatabase.keyValueDao`).
 * @param ownWrite runs each write to the chat stores as work the session owns, so closing the
 *  session waits for it instead of closing the database under it.
 */
class AiFacadeImpl(
    private val db: NpnDatabase,
    chats: KeyValueDao,
    ownWrite: suspend (suspend () -> Unit) -> Unit = { it() },
    private val clock: EpochClock = SystemEpochClock,
    private val configProvider: suspend () -> AiConfig,
) : AiChat,
    AiSuggest {
    /**
     * The one cooldown of this facade's endpoint, shared by every queue that calls it (automatic
     * enrichment and queued edit runs), so they do not each probe an endpoint that is down.
     */
    val endpointHealth = EndpointHealth()

    private val chatHistoryStore = RoomChatHistoryProvider(chats, ownWrite)
    private val chatSessionStore = RoomChatSessionStore(chats, ownWrite, chatHistoryStore)

    /** Serializes the read-modify-write of the session list across ask, create, clear and delete. */
    private val sessionsMutex = Mutex()

    private val runtimes = AiRuntimeCache()

    override suspend fun configured(): Boolean = configProvider().configured

    override fun ask(
        sessionId: String,
        question: String,
    ): Flow<AiChatEvent> =
        flow {
            touchChatSession(sessionId, question)
            // One config read for the whole turn, so the client, prompt and parameters agree. Inside
            // the cold flow, so config errors surface on collection (as flow exceptions), matching
            // the "throws on endpoint errors" contract.
            val config = configProvider()
            val runtime = runtimes.runtimeFor(config)
            // The field vocabulary is read per turn so a field defined a minute ago is already known.
            val fields = db.fieldDefDao().defs()
            emitAll(
                KoogChatAgent(
                    executor = runtime.executor,
                    model = runtime.model,
                    historyProvider = chatHistoryStore,
                    toolSet = LibraryAiTools.toolSetFor(db, fields),
                    systemPrompt = AiPrompts.chatSystem(promptContext(config), fields),
                    maxIterations = MAX_AGENT_ITERATIONS,
                    params = AiRuntime.chatParams(config),
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
        // Every stored session is listed: one is stored once asked in, and stays (untitled once
        // cleared) until it is deleted.
        val stored = chatSessionStore.load()
        // The one conversation of a build from before sessions existed, listed untitled beside the
        // others while it has history, until it is deleted or asked in (ask stores it then).
        val legacy =
            if (stored.none { it.id == LEGACY_CONVERSATION_ID } && chatHistoryStore.has(LEGACY_CONVERSATION_ID)) {
                listOf(AiChatSession(LEGACY_CONVERSATION_ID, title = null, updatedAt = 0L))
            } else {
                emptyList()
            }
        return (stored + legacy).sortedByDescending { it.updatedAt }
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun createChatSession(): AiChatSession = AiChatSession(Uuid.random().toString(), null, now())

    override suspend fun deleteChatSession(sessionId: String) {
        chatHistoryStore.delete(sessionId)
        editSessions { sessions -> sessions.filterNot { it.id == sessionId } }
    }

    override suspend fun clearChatSession(sessionId: String) {
        // Stored untitled, so it stays listed; that includes the pre-sessions conversation, which
        // is listed by its history alone until now.
        val hadConversation = chatHistoryStore.has(sessionId)
        chatHistoryStore.delete(sessionId)
        editSessions { sessions ->
            if (!hadConversation && sessions.none { it.id == sessionId }) {
                sessions
            } else {
                sessions.filterNot { it.id == sessionId } + AiChatSession(sessionId, title = null, updatedAt = now())
            }
        }
    }

    /** Store [sessionId] as just used, titled by its first question while it has no title yet. */
    private suspend fun touchChatSession(
        sessionId: String,
        question: String,
    ) = editSessions { sessions ->
        val title = sessions.firstOrNull { it.id == sessionId }?.title ?: AiChatSession.titleFrom(question)
        sessions.filterNot { it.id == sessionId } + AiChatSession(sessionId, title, now())
    }

    /** Read, change and store the session list as one step, newest first. */
    private suspend fun editSessions(change: (List<AiChatSession>) -> List<AiChatSession>) =
        sessionsMutex.withLock {
            chatSessionStore.save(change(chatSessionStore.load()).sortedByDescending { it.updatedAt })
        }

    private fun now(): Long = clock.nowMillis()

    override suspend fun suggestEdits(
        docId: String,
        request: AiEditRequest,
    ): AiEditSuggestion {
        val config = configProvider()
        val runtime = runtimes.runtimeFor(config)
        val doc = db.documentDao().getByDocId(docId) ?: return AiEditSuggestion.EMPTY
        val text = db.ftsDao().contentForDoc(docId)?.takeIf { it.isNotBlank() }
        // No OCR text yet: field extraction is impossible, but a tag-only run can still work off
        // the current tag list and vocabulary. This is the single decided rule for every flow.
        if (text == null && request.fields.isNotEmpty()) return AiEditSuggestion.EMPTY
        val currentTags = db.tagDao().tagsForDocument(docId)
        val vocabulary = db.tagDao().all().map { it.name }
        val fields = fieldSpecs(request)
        val currentFields = db.documentFieldDao().valuesOf(docId)
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
            tags = request.tags,
            tagRemovals = request.tagRemovals,
        )
    }

    /**
     * The requested fields resolved against the registry, in request order. A TEXT field below the
     * vocabulary threshold carries its existing values for verbatim reuse; a key the registry does
     * not know is asked for as plain text rather than dropped, so a run configured before a field
     * was deleted still gets an answer for it.
     */
    private suspend fun fieldSpecs(request: AiEditRequest): List<AiFieldSpec> {
        val defs = db.fieldDefDao().defs().associateBy { it.key }
        return request.fields.map { cfg ->
            val def = defs[cfg.key] ?: AiFieldSpec.unregistered(cfg.key)
            val vocabulary =
                if (def.sharesVocabulary) {
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

    /**
     * Page transcription by the endpoint's vision model: OCR done by the LLM instead of a local
     * engine, for scans a local engine reads badly (handwriting, tables, poor contrast, scripts the
     * bundled models do not cover). The OCR pipeline ranks and selects it like any other engine, so
     * its sidecar (`.npn/ocr/<hash>.<device>.ai.json`) is a normal OCR version that other replicas
     * adopt without an endpoint of their own; and it shares this facade's cached client. It costs
     * one call per *page*, so nothing starts it unattended without [UnattendedAiPolicy] (see
     * `OcrEngineSelector`).
     *
     * [OcrEngine.recognizePng] returns the page's text, or `""` for a page the model reports as
     * carrying none; `lang` is a hint only, since the model reads whatever the page is written in.
     * It throws on endpoint and network errors, so a failed page fails its document's OCR run rather
     * than silently storing a hole in the archive's text.
     */
    val ocrEngine: OcrEngine =
        object : OcrEngine {
            override val name: String = OcrEngineId.AI.column

            override suspend fun available(): Boolean = configured()

            override suspend fun recognizePng(
                png: ByteArray,
                lang: String,
            ): String {
                val thePrompt = pagePrompt(png, lang, configProvider())
                return transcribeWithRetry(PAGE_ATTEMPTS) { executePage(thePrompt) }
            }
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
                image(AiPrompts.pageImage(png))
            }
        }

    private suspend fun runtime(): AiRuntime = runtimes.runtimeFor(configProvider())

    /** Release the cached endpoint client; see [AiRuntimeCache.close]. */
    suspend fun close() = runtimes.close()

    /**
     * The language + date facts the prompts need (see [AiPromptContext]). Derived from the live
     * config on every call for the same reason the runtime is: the user can switch the app's
     * language between two calls, and a run started yesterday must not keep yesterday's date.
     */
    private fun promptContext(config: AiConfig): AiPromptContext = AiPromptContext.of(config.appLanguage, clock)

    private companion object {
        const val MAX_AGENT_ITERATIONS = 15

        /** The conversation id builds from before sessions existed stored their one chat under. */
        const val LEGACY_CONVERSATION_ID = "main"

        /**
         * Calls one page may cost per attempt at its document: the call plus a single retry (see
         * [transcribeWithRetry]).
         *
         * One retry, and deliberately only one: this is the only immediate retry on the paid OCR
         * path. Vision models do occasionally answer a page with a refusal, an empty message or a
         * transient endpoint error, and asking again fixes it. The pipeline's own retries of the
         * document ([dev.npn.core.ocr.OcrConfig.maxAttempts]) wait out a backoff and resume from
         * the pages already read ([dev.npn.core.db.OcrPageCacheEntity]), so a page that was read is
         * never paid for twice; anything more here would turn a systematically failing endpoint
         * into a bill instead of an error.
         */
        const val PAGE_ATTEMPTS = 2
    }
}
