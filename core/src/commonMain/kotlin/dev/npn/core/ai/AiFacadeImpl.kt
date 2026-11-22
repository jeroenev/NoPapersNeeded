package dev.npn.core.ai

import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.ResponseMetaInfo
import dev.npn.core.db.KeyValueDao
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.defs
import dev.npn.core.ocr.OcrEngine
import dev.npn.core.ocr.OcrEngineId
import dev.npn.core.util.EpochClock
import dev.npn.core.util.NpnLog
import dev.npn.core.util.SystemEpochClock
import dev.npn.core.util.runLogged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
internal class AiFacadeImpl(
    private val db: NpnDatabase,
    chats: KeyValueDao,
    ownWrite: suspend (suspend () -> Unit) -> Unit = { it() },
    private val clock: EpochClock = SystemEpochClock,
    /** Where every paid request is recorded; the device-local ledger, or [AiLedger.None] in tests. */
    private val ledger: AiLedger = AiLedger.None,
    private val log: NpnLog = NpnLog.None,
    private val configProvider: suspend () -> AiConfig,
) : AiChat,
    AiSuggest {
    private val chatHistoryStore = RoomChatHistoryProvider(chats, ownWrite)
    private val sessions = ChatSessions(chats, ownWrite, chatHistoryStore, clock)

    private val runtimes = AiRuntimeCache()

    /**
     * The one ceiling on [suggestEdits] calls in flight, whichever path asks: interactive and
     * queued edit runs, automatic enrichment and single-document requests together never hold
     * more than [MAX_CONCURRENT_SUGGESTIONS] requests open at the endpoint.
     */
    private val suggesting = Semaphore(MAX_CONCURRENT_SUGGESTIONS)

    override fun ask(
        sessionId: String,
        question: String,
    ): Flow<AiChatEvent> =
        flow {
            sessions.touch(sessionId, question)
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
                ).run(question, sessionId, onUsage = { usage ->
                    record(
                        config = config,
                        kind = AiUsageKind.CHAT,
                        runId = sessionId,
                        reported = usage.metaInfo,
                        estimatedInput = usage.promptChars / CHARS_PER_TOKEN.toLong(),
                        estimatedOutput = usage.answerChars / CHARS_PER_TOKEN.toLong(),
                    )
                }),
            )
            // Endpoints that want a conversation id per request (the OpenCode Go gateway's
            // x-opencode-session header) read it off the coroutine context, since Koog exposes no
            // seam to pass one down. flowOn puts it there for the whole turn without changing
            // dispatchers. Cheap and inert for every other provider.
        }.flowOn(AiGatewaySession(sessionId))

    override suspend fun chatTranscriptFor(sessionId: String): List<AiChatHistoryItem> =
        KoogChatHistoryMapper.map(chatHistoryStore.load(sessionId))

    override suspend fun chatSessions(): List<AiChatSession> = sessions.list()

    override suspend fun createChatSession(): AiChatSession = sessions.create()

    override suspend fun deleteChatSession(sessionId: String) = sessions.delete(sessionId)

    override suspend fun clearChatSession(sessionId: String) = sessions.clear(sessionId)

    override suspend fun suggestEdits(
        docId: String,
        request: AiEditRequest,
        runId: String?,
    ): AiEditSuggestion {
        val doc = db.documentDao().getByDocId(docId) ?: return AiEditSuggestion.EMPTY
        val text = db.ftsDao().textOf(docId)
        // No OCR text yet: field extraction is impossible, but a tag-only run can still work off
        // the current tag list and vocabulary. This is the single decided rule for every flow.
        if (text == null && request.fields.isNotEmpty()) return AiEditSuggestion.EMPTY
        val config = configProvider()
        val runtime = runtimes.runtimeFor(config)
        val currentTags = db.tagDao().tagsForDocument(docId)
        // Sorted, so the system message (which carries it) is byte-identical across a batch.
        val vocabulary = db.tagDao().names()
        val fields = fieldSpecs(request)
        val currentFields = db.documentFieldDao().valuesOf(docId)
        val caps = TagCaps.of(request, config)
        val thePrompt =
            prompt("npn-edit-suggest", AiRuntime.suggestionParams(config)) {
                system(
                    AiPrompts.editSystem(
                        request = request,
                        fields = fields,
                        caps = caps,
                        vocabulary = vocabulary,
                        context = promptContext(config),
                    ),
                )
                user(AiPrompts.editUser(doc.fileName, currentFields, fields, request, currentTags, text))
            }
        val response = suggesting.withPermit { runtime.executor.execute(thePrompt, runtime.model) }
        record(
            config = config,
            kind = AiUsageKind.SUGGEST,
            runId = runId,
            reported = response.metaInfo,
            estimatedInput = promptChars(thePrompt) / CHARS_PER_TOKEN.toLong(),
            estimatedOutput = response.textContent().length / CHARS_PER_TOKEN.toLong(),
        )
        return AiText.parseEditSuggestion(
            response.textContent(),
            request = request,
            caps = caps,
            currentFields = currentFields,
            fields = fields,
            currentTags = currentTags,
            vocabulary = vocabulary,
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

            override suspend fun available(): Boolean = configProvider().configured

            override suspend fun recognizePng(
                png: ByteArray,
                lang: String,
                runId: String?,
            ): String {
                // One config read for the page, so its prompt and the client that sends it agree.
                val config = configProvider()
                val thePrompt = pagePrompt(png, lang, config)
                val runtime = runtimes.runtimeFor(config)
                return transcribeWithRetry(PAGE_ATTEMPTS) { executePage(runtime, thePrompt, config, runId) }
            }
        }

    private suspend fun executePage(
        runtime: AiRuntime,
        thePrompt: Prompt,
        config: AiConfig,
        runId: String?,
    ): String? {
        val response = runtime.executor.execute(thePrompt, runtime.model)
        record(
            config = config,
            kind = AiUsageKind.OCR,
            runId = runId,
            reported = response.metaInfo,
            estimatedInput =
                AiEstimator.OCR_INPUT_TOKENS_PER_PAGE + promptChars(thePrompt) / CHARS_PER_TOKEN.toLong(),
            estimatedOutput = AiEstimator.OCR_OUTPUT_TOKENS_PER_PAGE.toLong(),
        )
        return AiText.parseTranscription(response.textContent())
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

    /** Release the cached endpoint client; see [AiRuntimeCache.close]. */
    suspend fun close() = runtimes.close()

    /**
     * Record one request in the device-local ledger. Best effort: the call has already been paid
     * for, and a ledger write that fails must not turn a successful answer into an error.
     */
    private suspend fun record(
        config: AiConfig,
        kind: AiUsageKind,
        runId: String?,
        reported: ResponseMetaInfo?,
        estimatedInput: Long,
        estimatedOutput: Long,
    ) {
        val reportedInput = reported?.inputTokensCount?.toLong()
        val reportedOutput = reported?.outputTokensCount?.toLong()
        val input = reportedInput ?: estimatedInput
        val output = reportedOutput ?: estimatedOutput
        val price = AiPricing.of(config.provider, config.model)
        val record =
            AiUsageRecord(
                provider = config.provider.name,
                model = config.model,
                kind = kind,
                inputTokens = input,
                outputTokens = output,
                estimatedCostUsd = AiPricing.cost(price, input, output),
                estimated = reportedInput == null || reportedOutput == null,
                recordedAt = clock.nowMillis(),
                runId = runId,
            )
        log.runLogged("record AI usage") { ledger.record(record) }
    }

    /** The text a prompt carries, for an estimate when the provider reports no usage. */
    private fun promptChars(prompt: Prompt): Long =
        prompt.messages.sumOf { message -> message.textContent().length.toLong() }

    /**
     * The language + date facts the prompts need (see [AiPromptContext]). Derived from the live
     * config on every call for the same reason the runtime is: the user can switch the app's
     * language between two calls, and a run started yesterday must not keep yesterday's date.
     */
    private fun promptContext(config: AiConfig): AiPromptContext = AiPromptContext.of(config.appLanguage, clock)

    companion object {
        /**
         * Edit suggestions asked of the endpoint at once, across every path that asks. The calls are
         * mostly IO-bound, so overlapping them is a large speedup over asking one at a time; the
         * ceiling bounds the load on the endpoint.
         */
        const val MAX_CONCURRENT_SUGGESTIONS = 12

        /** The same rough characters-per-token ratio [AiEstimator] uses for pre-flight estimates. */
        private const val CHARS_PER_TOKEN = 4

        private const val MAX_AGENT_ITERATIONS = 15

        /**
         * Calls one page may cost per attempt at its document: the call plus a single retry (see
         * [transcribeWithRetry]).
         *
         * One retry, and deliberately only one: this is the only immediate retry on the paid OCR
         * path. Vision models do occasionally answer a page with a refusal, an empty message or a
         * transient endpoint error, and asking again fixes it. The pipeline's own retries of the
         * document ([dev.npn.core.ocr.OcrPipeline.MAX_ATTEMPTS]) wait out a backoff and resume from
         * the pages already read ([dev.npn.core.db.OcrPageCacheEntity]), so a page that was read is
         * never paid for twice; anything more here would turn a systematically failing endpoint
         * into a bill instead of an error.
         */
        private const val PAGE_ATTEMPTS = 2
    }
}
