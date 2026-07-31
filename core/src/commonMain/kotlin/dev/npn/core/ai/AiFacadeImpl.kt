package dev.npn.core.ai

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.prompt
import dev.npn.core.db.NpnDatabase
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
    private var cachedRuntime: Pair<AiConfig, AiRuntime>? = null

    override suspend fun config(): AiConfig = configProvider()

    override suspend fun ask(history: List<AiChatTurn>, question: String): String {
        val runtime = runtime()
        val agent = AIAgent(
            promptExecutor = runtime.executor,
            llmModel = runtime.model,
            systemPrompt = CHAT_SYSTEM_PROMPT,
            toolRegistry = ToolRegistry { tools(LibraryAiTools.forDatabase(db)) },
            maxIterations = MAX_AGENT_ITERATIONS,
        )
        return agent.run(AiText.renderChatInput(history, question))
    }

    override suspend fun suggestTags(docId: String): List<String> {
        val runtime = runtime()
        val doc = db.documentDao().getByDocId(docId) ?: return emptyList()
        val text = db.ftsDao().contentForDoc(docId)?.takeIf { it.isNotBlank() } ?: return emptyList()
        val currentTags = db.tagDao().tagsForDocument(docId)
        val vocabulary = db.tagDao().all().map { it.name }
        val request = prompt("npn-tag-suggest") {
            system(TAG_SYSTEM_PROMPT)
            user(renderTagRequest(doc.fileName, doc.title, vocabulary, currentTags, text))
        }
        val response = runtime.executor.execute(request, runtime.model)
        return AiText.parseTagSuggestions(response.textContent(), currentTags)
    }

    private suspend fun runtime(): AiRuntime {
        val config = configProvider()
        check(config.configured) { "AI endpoint is not configured (see Settings > AI assistant)" }
        return runtimeMutex.withLock {
            cachedRuntime?.takeIf { it.first == config }?.second
                ?: AiRuntime.build(config).also { cachedRuntime = config to it }
        }
    }

    private fun renderTagRequest(
        fileName: String,
        title: String?,
        vocabulary: List<String>,
        currentTags: List<String>,
        text: String,
    ): String = buildString {
        appendLine("File name: $fileName")
        title?.takeIf { it.isNotBlank() }?.let { appendLine("Title: $it") }
        appendLine("Tags already on this document: ${currentTags.ifEmpty { listOf("(none)") }.joinToString(", ")}")
        appendLine("Existing tags in this library: ${vocabulary.ifEmpty { listOf("(none)") }.joinToString(", ")}")
        appendLine()
        appendLine("Document text:")
        appendLine(text.take(MAX_TAG_TEXT_CHARS))
    }

    private companion object {
        const val MAX_AGENT_ITERATIONS = 15
        const val MAX_TAG_TEXT_CHARS = 12_000

        val CHAT_SYSTEM_PROMPT = """
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

        val TAG_SYSTEM_PROMPT = """
            You suggest tags for a document in a personal paperless archive. Prefer reusing the
            library's existing tags when they fit; only propose new tags for clearly missing
            concepts. Tags are short lowercase labels (topic, document kind, organisation,
            year). Never suggest tags already on the document.

            Reply with ONLY a JSON array of tag strings, at most ${AiText.MAX_TAG_SUGGESTIONS},
            best first. No prose, no code fences.
        """.trimIndent()
    }
}
