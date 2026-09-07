package dev.npn.core.ai

import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import dev.npn.core.app.AiTagging
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.NpnDatabase
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
            emitAll(
                KoogChatAgent(
                    executor = runtime.executor,
                    model = runtime.model,
                    db = db,
                    systemPrompt = CHAT_SYSTEM_PROMPT,
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
        val correspondents = db.documentDao().correspondents()
        val maxTags = (request.maxTags ?: config.maxTagsPerDoc).coerceIn(AiTagging.RANGE)
        val maxNew = (request.maxNewTags ?: config.maxNewTagsPerDoc).coerceIn(AiTagging.NEW_TAGS_RANGE)
        val thePrompt =
            prompt("npn-edit-suggest", AiRuntime.suggestionParams(config)) {
                system(editSystemPrompt(request, maxTags, maxNew, vocabulary, correspondents))
                user(renderEditRequest(doc, request, currentTags, text))
            }
        val response = runtime.executor.execute(thePrompt, runtime.model)
        return AiText.parseEditSuggestion(
            response.textContent(),
            currentTitle = doc.title,
            currentCorrespondent = doc.correspondent,
            currentDocDate = doc.docDate,
            correspondents = correspondents,
            currentTags = currentTags,
            vocabulary = vocabulary,
            fields = request.fields,
            maxTags = maxTags,
            maxNewTags = maxNew,
        )
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
            system(OCR_SYSTEM_PROMPT)
            user {
                text("Language hint: $lang. Transcribe this page.")
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

    private fun renderEditRequest(
        doc: DocumentEntity,
        request: AiEditRequest,
        currentTags: List<String>,
        text: String?,
    ): String =
        buildString {
            appendLine("File name: ${doc.fileName}")
            doc.title?.takeIf { it.isNotBlank() }?.let { appendLine("Current title: $it") }
            doc.correspondent?.takeIf { it.isNotBlank() }?.let { appendLine("Current correspondent: $it") }
            doc.docDate?.takeIf { it.isNotBlank() }?.let { appendLine("Current document date: $it") }
            if (request.tags || request.tagRemovals) {
                appendLine(
                    "Tags already on this document: ${currentTags.ifEmpty { listOf("(none)") }.joinToString(", ")}",
                )
            }
            appendLine()
            if (text != null) {
                appendLine("Document text:")
                appendLine(text.take(MAX_DOC_TEXT_CHARS))
            } else {
                appendLine("Document text: (no OCR text available)")
            }
            request.customPrompt?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine("User's extra instructions:")
                appendLine(it.trim())
            }
        }

    private companion object {
        const val MAX_AGENT_ITERATIONS = 15
        const val CHAT_CONVERSATION_ID = "main"
        const val NEW_SESSION_TITLE = "New chat"
        const val MAX_SESSION_TITLE_CHARS = 60
        const val MAX_DOC_TEXT_CHARS = 12_000

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

        /**
         * The page-transcription prompt. Written to make the reply *usable as archived text*, which
         * is a stricter goal than "read the page": every instruction here exists to stop the model
         * doing something a local OCR engine cannot do — summarising, translating, correcting,
         * describing, or introducing itself. The output goes verbatim into a write-once sidecar and
         * into the search index, so invented or reflowed text is not a cosmetic problem.
         */
        val OCR_SYSTEM_PROMPT =
            """
            You are an OCR engine. You transcribe one scanned page image into plain text.

            Rules:
            - Output ONLY the page's text. No preamble, no commentary, no explanation of what the
              document is, no markdown code fences.
            - Transcribe VERBATIM in the page's own language. Never translate, summarise, rephrase,
              correct spelling, or fix what looks like an error: reproduce what is printed.
            - Keep the natural reading order and the line and paragraph breaks of the page.
            - Render tables as plain text rows, keeping columns separated by spaces or tabs.
            - Transcribe handwriting too. For a word you genuinely cannot read, write [?] in its
              place rather than guessing.
            - Do not describe images, logos or stamps; transcribe only text that appears in them.
            - If the page contains no text at all (blank, or purely an image), reply with exactly
              ${AiText.BLANK_PAGE_MARKER} and nothing else.
            """.trimIndent()

        val CHAT_SYSTEM_PROMPT =
            """
            You are the assistant inside "no-papers-needed", a personal paperless document
            manager. The user's scanned documents (PDFs) are indexed locally: OCR text, tags,
            filenames, and metadata fields. Documents carry built-in default fields (title,
            correspondent, docType, docDate, notes, fileName) plus user-defined flexible (custom)
            fields such as "amount" or "invoice number".

            Use the provided tools to find documents before answering; never invent documents
            or their contents. Prefer search_documents for content questions, list_tags /
            documents_with_tag for organisation questions, search_filenames when the user
            remembers a file name, search_fields to look documents up by a specific default or
            flexible field value — including "has a field" queries like "documents with a test2
            field" (call list_fields first to discover flexible field keys; leave search_fields'
            value empty to match any document that has the field), and get_document_text to read
            a specific document. Remember that custom (flexible) field keys and values are not
            part of the full-text search_documents index.

            When you mention a specific document, cite it inline exactly as [doc:<docId>] using
            the full docId from the tool results, so the app can link it. Answer in the language
            the user writes in. Be concise. If nothing matches, say so plainly.
            """.trimIndent()

        /** JSON key a field is expected under, and its null-able literal in the reply contract. */
        private fun fieldKey(field: AiField): Pair<String, String> =
            when (field) {
                AiField.TITLE -> "title" to "string|null"
                AiField.CORRESPONDENT -> "correspondent" to "string|null"
                AiField.DOC_DATE -> "date" to "\"YYYY-MM-DD\"|null"
            }

        private fun fieldDescription(field: AiField): String =
            when (field) {
                AiField.TITLE -> {
                    "a short, human-readable title in the document's own language (what the " +
                        "document IS, e.g. \"Electricity invoice January 2024\"), never a file name"
                }

                AiField.CORRESPONDENT -> {
                    "the other party — the company, institution or person that sent or issued the " +
                        "document, never the archive owner. When an existing correspondent from the " +
                        "provided list matches, copy it VERBATIM (exact spelling and case); only " +
                        "introduce a new name when none fits"
                }

                AiField.DOC_DATE -> {
                    "the document's own date (invoice date, letter date, issue date) as YYYY-MM-DD, " +
                        "never the scan or import date"
                }
            }

        /**
         * The single-request system prompt for the AI-edit flow. Sections are included only for
         * what the run actually asked for, so a tags-only run gets a tags-only contract and a
         * custom-prompt run still gets the same structured reply shape.
         *
         * Library-wide context (tag vocabulary, correspondents) lives HERE, not in the per-doc
         * user message: it is identical for every document of a batch, so keeping the whole
         * system message batch-constant lets prefix/KV caches on the endpoint skip re-evaluating
         * the (potentially large) vocabulary for every document. Only per-doc data goes in the
         * user message.
         */
        fun editSystemPrompt(
            request: AiEditRequest,
            max: Int,
            maxNew: Int,
            vocabulary: List<String>,
            correspondents: List<String>,
        ): String {
            val rules =
                buildList {
                    if (request.tags) {
                        val newTagRule =
                            if (maxNew <= 0) {
                                "Do NOT invent brand-new tags: use ONLY tags from the provided vocabulary."
                            } else {
                                "Strongly prefer existing vocabulary tags and propose at most $maxNew brand-new tags."
                            }
                        add(
                            "\"add_tags\": tags to ADD to this document (must not already be on it). " +
                                "When an existing library tag fits, copy it VERBATIM (exact spelling and case); " +
                                "only invent a new tag when none fits. Tags are short lowercase labels (topic, " +
                                "document kind, organisation, year). $newTagRule At most $max, best first.",
                        )
                    }
                    if (request.tagRemovals) {
                        add(
                            "\"remove_tags\": tags currently on this document that should be REMOVED. " +
                                "Only list tags from the document's current tag list, verbatim. Leave empty " +
                                "unless the document genuinely should no longer have those tags.",
                        )
                    }
                    for (cfg in request.fields) {
                        val (key, _) = fieldKey(cfg.field)
                        val modeRule =
                            when (cfg.mode) {
                                AiFieldMode.ALL -> {
                                    "Suggest a value even when one already exists (you may propose a better replacement)."
                                }

                                AiFieldMode.UNFILLED -> {
                                    "Only suggest a value when the field is currently empty; otherwise use null."
                                }
                            }
                        add("\"$key\": ${fieldDescription(cfg.field)}. $modeRule")
                    }
                }
            val contract =
                buildList {
                    if (request.tags) add("\"add_tags\": [\"tag\"]")
                    if (request.tagRemovals) add("\"remove_tags\": [\"tag\"]")
                    for (cfg in request.fields) {
                        val (key, literal) = fieldKey(cfg.field)
                        add("\"$key\": $literal")
                    }
                }
            val libraryContext =
                buildList {
                    if (request.tags || request.tagRemovals) {
                        add(
                            "Existing tags in this library: " +
                                vocabulary.ifEmpty { listOf("(none)") }.joinToString(", "),
                        )
                    }
                    if (request.fields.isNotEmpty()) {
                        add(
                            "Existing correspondents in this library: " +
                                correspondents.ifEmpty { listOf("(none)") }.joinToString(", "),
                        )
                    }
                }
            return """
                You propose edits for one document in a personal paperless archive. Read the
                provided document text and current metadata carefully; never invent facts the
                document text does not support.

                ${rules.joinToString("\n")}

                Reply with ONLY a JSON object containing exactly these keys, no prose, no code
                fences: {${contract.joinToString(", ")}}
                Use null / empty arrays for anything the document does not clearly support. Follow
                the user's extra instructions when they are provided, mapping their intent onto the
                allowed keys.

                ${libraryContext.joinToString("\n")}
                """.trimIndent()
        }
    }
}
