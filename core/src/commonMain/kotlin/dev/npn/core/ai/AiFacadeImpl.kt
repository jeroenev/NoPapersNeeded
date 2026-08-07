package dev.npn.core.ai

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import dev.npn.core.app.AiTagging
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.NpnDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Koog-backed [AiFacade]. Reads the endpoint configuration live on every call (settings can
 * change between calls) and caches the built runtime while the config is unchanged. Purely a
 * read-side consumer of the Room index; all writes stay with MetadataWriter/JournalCoordinator.
 */
class AiFacadeImpl(
    private val db: NpnDatabase,
    private val configProvider: suspend () -> AiConfig,
) : AiFacade {
    private val runtimeMutex = Mutex()
    private var cachedRuntime: Pair<RuntimeKey, AiRuntime>? = null

    /**
     * The subset of [AiConfig] that actually shapes the built [AiRuntime] (endpoint client + model
     * — see [AiRuntime.build]). Cache identity is keyed on this so tuning a runtime-irrelevant
     * setting like [AiConfig.maxTagsPerDoc] or [AiConfig.maxNewTagsPerDoc] does NOT rebuild the
     * client and abort in-flight requests.
     */
    private data class RuntimeKey(
        val enabled: Boolean,
        val baseUrl: String,
        val apiKey: String,
        val model: String,
    )

    private fun AiConfig.runtimeKey() = RuntimeKey(enabled, baseUrl, apiKey, model)

    override suspend fun config(): AiConfig = configProvider()

    override fun ask(
        history: List<AiChatTurn>,
        question: String,
    ): Flow<AiChatEvent> =
        flow {
            // Resolve the runtime lazily inside the cold flow so config errors surface on collection
            // (as flow exceptions), matching the "throws on endpoint errors" contract.
            val runtime = runtime()
            val loop =
                AiChatLoop(
                    executor = runtime.executor,
                    model = runtime.model,
                    tools = LibraryAiTools.chatToolsFor(db),
                    systemPrompt = CHAT_SYSTEM_PROMPT,
                    maxIterations = MAX_AGENT_ITERATIONS,
                )
            emitAll(loop.run(history, question))
        }

    override suspend fun suggestTags(
        docId: String,
        maxTags: Int?,
        maxNewTags: Int?,
    ): List<TagSuggestion> {
        val runtime = runtime()
        val config = configProvider()
        val maxTags = (maxTags ?: config.maxTagsPerDoc).coerceIn(AiTagging.RANGE)
        val maxNew = (maxNewTags ?: config.maxNewTagsPerDoc).coerceIn(AiTagging.NEW_TAGS_RANGE)
        val doc = db.documentDao().getByDocId(docId) ?: return emptyList()
        val text = db.ftsDao().contentForDoc(docId)?.takeIf { it.isNotBlank() } ?: return emptyList()
        val currentTags = db.tagDao().tagsForDocument(docId)
        val vocabulary = db.tagDao().all().map { it.name }
        val request =
            prompt("npn-tag-suggest", SUGGESTION_PARAMS) {
                system(tagSystemPrompt(maxTags, maxNew))
                user(renderTagRequest(doc.fileName, doc.title, vocabulary, currentTags, text))
            }
        val response = runtime.executor.execute(request, runtime.model)
        return AiText.rankTagSuggestions(
            response.textContent(),
            currentTags,
            vocabulary,
            perDocMax = maxTags,
            maxNew = maxNew,
        )
    }

    override suspend fun suggestMetadata(docId: String): MetadataSuggestion {
        val runtime = runtime()
        val doc = db.documentDao().getByDocId(docId) ?: return MetadataSuggestion.EMPTY
        val text =
            db.ftsDao().contentForDoc(docId)?.takeIf { it.isNotBlank() }
                ?: return MetadataSuggestion.EMPTY
        val correspondents = db.documentDao().correspondents()
        val request =
            prompt("npn-metadata-suggest", SUGGESTION_PARAMS) {
                system(METADATA_SYSTEM_PROMPT)
                user(renderMetadataRequest(doc, correspondents, text))
            }
        val response = runtime.executor.execute(request, runtime.model)
        return AiText.parseMetadataSuggestion(
            response.textContent(),
            currentTitle = doc.title,
            currentCorrespondent = doc.correspondent,
            currentDocDate = doc.docDate,
            correspondents = correspondents,
        )
    }

    override suspend fun suggestEdits(
        docId: String,
        request: AiEditRequest,
    ): AiEditSuggestion {
        val runtime = runtime()
        val config = configProvider()
        val doc = db.documentDao().getByDocId(docId) ?: return AiEditSuggestion.EMPTY
        val text = db.ftsDao().contentForDoc(docId)?.takeIf { it.isNotBlank() }
        if (text == null && request.fields.isNotEmpty()) return AiEditSuggestion.EMPTY
        val currentTags = db.tagDao().tagsForDocument(docId)
        val vocabulary = db.tagDao().all().map { it.name }
        val correspondents = db.documentDao().correspondents()
        val maxTags = (request.maxTags ?: config.maxTagsPerDoc).coerceIn(AiTagging.RANGE)
        val maxNew = (request.maxNewTags ?: config.maxNewTagsPerDoc).coerceIn(AiTagging.NEW_TAGS_RANGE)
        val thePrompt =
            prompt("npn-edit-suggest", SUGGESTION_PARAMS) {
                system(editSystemPrompt(request, maxTags, maxNew))
                user(renderEditRequest(doc, request, vocabulary, currentTags, correspondents, text))
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

    private fun renderTagRequest(
        fileName: String,
        title: String?,
        vocabulary: List<String>,
        currentTags: List<String>,
        text: String,
    ): String =
        buildString {
            appendLine("File name: $fileName")
            title?.takeIf { it.isNotBlank() }?.let { appendLine("Title: $it") }
            appendLine("Tags already on this document: ${currentTags.ifEmpty { listOf("(none)") }.joinToString(", ")}")
            appendLine("Existing tags in this library: ${vocabulary.ifEmpty { listOf("(none)") }.joinToString(", ")}")
            appendLine()
            appendLine("Document text:")
            appendLine(text.take(MAX_DOC_TEXT_CHARS))
        }

    private fun renderMetadataRequest(
        doc: DocumentEntity,
        correspondents: List<String>,
        text: String,
    ): String =
        buildString {
            appendLine("File name: ${doc.fileName}")
            doc.title?.takeIf { it.isNotBlank() }?.let { appendLine("Current title: $it") }
            doc.correspondent?.takeIf { it.isNotBlank() }?.let { appendLine("Current correspondent: $it") }
            doc.docDate?.takeIf { it.isNotBlank() }?.let { appendLine("Current document date: $it") }
            appendLine(
                "Existing correspondents in this library: " +
                    correspondents.ifEmpty { listOf("(none)") }.joinToString(", "),
            )
            appendLine()
            appendLine("Document text:")
            appendLine(text.take(MAX_DOC_TEXT_CHARS))
        }

    private fun renderEditRequest(
        doc: DocumentEntity,
        request: AiEditRequest,
        vocabulary: List<String>,
        currentTags: List<String>,
        correspondents: List<String>,
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
                appendLine(
                    "Existing tags in this library: ${vocabulary.ifEmpty { listOf("(none)") }.joinToString(", ")}",
                )
            }
            if (request.fields.isNotEmpty()) {
                appendLine(
                    "Existing correspondents in this library: " +
                        correspondents.ifEmpty { listOf("(none)") }.joinToString(", "),
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
        const val MAX_DOC_TEXT_CHARS = 12_000

        /**
         * Params for the one-shot suggestion prompts (tags, metadata, edits): these are cheap
         * extraction tasks, so ask reasoning-capable models for low reasoning effort instead of
         * burning thinking tokens. Chat keeps the endpoint's default effort.
         */
        val SUGGESTION_PARAMS = OpenAIChatParams(reasoningEffort = ReasoningEffort.LOW)

        val CHAT_SYSTEM_PROMPT =
            """
            You are the assistant inside "no-papers-needed", a personal paperless document
            manager. The user's scanned documents (PDFs) are indexed locally: OCR text, tags,
            filenames, titles, correspondents, document types, dates and notes.

            Use the provided tools to find documents before answering; never invent documents
            or their contents. Prefer search_documents for content questions, list_tags /
            documents_with_tag for organisation questions, search_filenames when the user
            remembers a file name, and get_document_text to read a specific document.

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
         */
        fun editSystemPrompt(
            request: AiEditRequest,
            max: Int,
            maxNew: Int,
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
                """.trimIndent()
        }

        fun tagSystemPrompt(
            max: Int,
            maxNew: Int,
        ): String {
            val newTagRule =
                if (maxNew <= 0) {
                    "Do NOT invent any brand-new tags: use ONLY tags from the provided vocabulary."
                } else {
                    "Strongly prefer existing vocabulary tags and propose at most $maxNew brand-new tags."
                }
            return """
                You suggest tags for a document in a personal paperless archive. Strongly prefer
                reusing the library's existing tags: when an existing tag fits, copy it VERBATIM
                (exact spelling and case) from the provided vocabulary. Only invent a new tag when
                no existing one fits the document. Tags are short lowercase labels (topic, document
                kind, organisation, year). Never suggest tags already on the document.

                $newTagRule

                Reply with ONLY a JSON array of tag strings, at most $max, best first. No prose,
                no code fences.
                """.trimIndent()
        }

        val METADATA_SYSTEM_PROMPT =
            """
            You extract catalog metadata for one document in a personal paperless archive.
            Suggest:
            - "title": a short, human-readable title in the document's own language (what the
              document IS, e.g. "Electricity invoice January 2024"), not a file name.
            - "correspondent": the other party — the company, institution or person that sent or
              issued the document, never the archive owner. When an existing correspondent from
              the provided list matches, copy it VERBATIM (exact spelling and case); only
              introduce a new name when none fits.
            - "date": the document's own date (invoice date, letter date, issue date) as
              YYYY-MM-DD, never the scan or import date.

            Reply with ONLY a JSON object, no prose, no code fences:
            {"title": string|null, "correspondent": string|null, "date": "YYYY-MM-DD"|null}
            Use null for anything the document text does not clearly support.
            """.trimIndent()
    }
}
