package dev.npn.core.ai

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.npn.core.db.DocumentDao
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.FtsDao
import dev.npn.core.db.FtsQuery
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.TagDao
import kotlinx.coroutines.flow.first
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Read-only tools the chat agent can call to search the library index: OCR/embedded text,
 * tags, filenames, and single-value metadata. Tools never write; every result line carries
 * the full docId so the model can cite documents as `[doc:<docId>]`.
 */
object LibraryAiTools {

    const val MAX_SEARCH_RESULTS = 15
    const val MAX_LISTED_DOCS = 50
    const val TEXT_CHUNK_CHARS = 4_000

    fun forDatabase(db: NpnDatabase): List<ToolBase<*, *>> = listOf(
        SearchDocumentsTool(db.ftsDao(), db.documentDao()),
        ListTagsTool(db.tagDao()),
        DocumentsWithTagTool(db.documentDao()),
        SearchFileNamesTool(db.documentDao()),
        GetDocumentTool(db.documentDao(), db.tagDao()),
        GetDocumentTextTool(db.ftsDao()),
    )

    /**
     * The same tools wrapped as [AiChatTool]s the streaming loop can drive: each exposes its
     * Koog [ToolDescriptor] (advertised to the model) and a name-keyed dispatcher that decodes
     * a raw arguments JSON string with kotlinx.serialization and runs the tool. We hand-roll the
     * decode instead of going through Koog's `ToolRegistry` because the streaming API hands us the
     * raw args JSON, and decoding it against our own `@Serializable Args` types is the smaller,
     * type-safe path.
     */
    fun chatToolsFor(db: NpnDatabase): List<AiChatTool> {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        return listOf(
            SearchDocumentsTool(db.ftsDao(), db.documentDao())
                .asChatTool(SearchDocumentsTool.Args.serializer(), json),
            ListTagsTool(db.tagDao()).asChatTool(ListTagsTool.Args.serializer(), json),
            DocumentsWithTagTool(db.documentDao())
                .asChatTool(DocumentsWithTagTool.Args.serializer(), json),
            SearchFileNamesTool(db.documentDao())
                .asChatTool(SearchFileNamesTool.Args.serializer(), json),
            GetDocumentTool(db.documentDao(), db.tagDao())
                .asChatTool(GetDocumentTool.Args.serializer(), json),
            GetDocumentTextTool(db.ftsDao()).asChatTool(GetDocumentTextTool.Args.serializer(), json),
        )
    }

    private fun <A> SimpleTool<A>.asChatTool(serializer: KSerializer<A>, json: Json): AiChatTool =
        object : AiChatTool {
            override val name: String = this@asChatTool.name
            override val descriptor: ToolDescriptor = this@asChatTool.descriptor

            override suspend fun call(argsJson: String): String {
                val args = json.decodeFromString(serializer, argsJson.ifBlank { "{}" })
                return execute(args)
            }
        }

    internal fun docLine(doc: DocumentEntity, snippet: String? = null): String = buildString {
        append("- [doc:").append(doc.docId).append("] file=\"").append(doc.fileName).append('"')
        doc.title?.takeIf { it.isNotBlank() }?.let { append(" title=\"").append(it).append('"') }
        doc.docDate?.let { append(" date=").append(it) }
        doc.correspondent?.takeIf { it.isNotBlank() }?.let { append(" correspondent=\"").append(it).append('"') }
        snippet?.takeIf { it.isNotBlank() }?.let {
            append(" snippet=\"").append(stripSnippetMarkers(it)).append('"')
        }
    }

    /** FTS snippets mark matches with control chars \u0001/\u0002; the model needs plain text. */
    internal fun stripSnippetMarkers(snippet: String): String =
        snippet.replace("\u0001", "").replace("\u0002", "")

    private fun DocumentEntity.isLive(): Boolean = !trashed && !deleted && !missing

    class SearchDocumentsTool(
        private val ftsDao: FtsDao,
        private val documentDao: DocumentDao,
    ) : SimpleTool<SearchDocumentsTool.Args>(
        argsType = typeToken<Args>(),
        name = "search_documents",
        description = "Full-text search over all documents (OCR text, embedded text, titles, " +
            "filenames, tags, notes). Terms are AND-combined. Returns matching documents " +
            "ranked by relevance, each with a docId and a snippet.",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Search terms, e.g. \"invoice 2024 electricity\"")
            val query: String,
        )

        override suspend fun execute(args: Args): String {
            if (args.query.isBlank()) return "Error: query must not be blank."
            val hits = ftsDao.search(FtsQuery.sanitize(args.query)).take(MAX_SEARCH_RESULTS)
            val lines = hits.mapNotNull { hit ->
                documentDao.getByDocId(hit.docId)
                    ?.takeIf { it.isLive() }
                    ?.let { docLine(it, hit.snippet) }
            }
            return if (lines.isEmpty()) {
                "No documents match \"${args.query}\". Try fewer or different terms."
            } else {
                lines.joinToString("\n")
            }
        }
    }

    class ListTagsTool(
        private val tagDao: TagDao,
    ) : SimpleTool<ListTagsTool.Args>(
        argsType = typeToken<Args>(),
        name = "list_tags",
        description = "List every tag in the library with its document count.",
    ) {
        @Serializable
        class Args

        override suspend fun execute(args: Args): String {
            val tags = tagDao.observeAllWithCounts().first()
            return if (tags.isEmpty()) {
                "The library has no tags yet."
            } else {
                tags.joinToString("\n") { "- ${it.name} (${it.count} docs)" }
            }
        }
    }

    class DocumentsWithTagTool(
        private val documentDao: DocumentDao,
    ) : SimpleTool<DocumentsWithTagTool.Args>(
        argsType = typeToken<Args>(),
        name = "documents_with_tag",
        description = "List documents carrying an exact tag name (use list_tags to discover names).",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Exact tag name")
            val tag: String,
        )

        override suspend fun execute(args: Args): String {
            val lines = documentDao.getLiveWithTag(args.tag, MAX_LISTED_DOCS).map { docLine(it) }
            return if (lines.isEmpty()) {
                "No documents have the tag \"${args.tag}\"."
            } else {
                lines.joinToString("\n")
            }
        }
    }

    class SearchFileNamesTool(
        private val documentDao: DocumentDao,
    ) : SimpleTool<SearchFileNamesTool.Args>(
        argsType = typeToken<Args>(),
        name = "search_filenames",
        description = "Find documents whose file name or folder path contains a substring " +
            "(case-insensitive). Useful when the user remembers part of a file name.",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Substring to look for in file names and paths")
            val pattern: String,
        )

        override suspend fun execute(args: Args): String {
            if (args.pattern.isBlank()) return "Error: pattern must not be blank."
            val lines = documentDao.getAllLive().asSequence()
                .filter {
                    it.fileName.contains(args.pattern, ignoreCase = true) ||
                        it.relPath.contains(args.pattern, ignoreCase = true)
                }
                .take(MAX_LISTED_DOCS)
                .map { docLine(it) }
                .toList()
            return if (lines.isEmpty()) {
                "No file names or paths contain \"${args.pattern}\"."
            } else {
                lines.joinToString("\n")
            }
        }
    }

    class GetDocumentTool(
        private val documentDao: DocumentDao,
        private val tagDao: TagDao,
    ) : SimpleTool<GetDocumentTool.Args>(
        argsType = typeToken<Args>(),
        name = "get_document",
        description = "Full metadata of one document by docId: file name, path, title, tags, " +
            "correspondent, document type, date, notes, page count.",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("The docId (64-char hash) from a previous search result")
            val docId: String,
        )

        override suspend fun execute(args: Args): String {
            val doc = documentDao.getByDocId(args.docId)
                ?: return "No document with docId ${args.docId}."
            val tags = tagDao.tagsForDocument(args.docId)
            return buildString {
                appendLine("docId: ${doc.docId}")
                appendLine("file: ${doc.fileName}")
                appendLine("path: ${doc.relPath}")
                doc.title?.let { appendLine("title: $it") }
                appendLine("tags: ${if (tags.isEmpty()) "(none)" else tags.joinToString(", ")}")
                doc.correspondent?.let { appendLine("correspondent: $it") }
                doc.docType?.let { appendLine("docType: $it") }
                doc.docDate?.let { appendLine("docDate: $it") }
                doc.pageCount?.let { appendLine("pages: $it") }
                doc.notes?.takeIf { it.isNotBlank() }?.let { appendLine("notes: $it") }
                if (doc.trashed) appendLine("status: in trash")
                if (doc.missing) appendLine("status: file missing")
            }.trimEnd()
        }
    }

    class GetDocumentTextTool(
        private val ftsDao: FtsDao,
    ) : SimpleTool<GetDocumentTextTool.Args>(
        argsType = typeToken<Args>(),
        name = "get_document_text",
        description = "Read the extracted text (OCR or embedded) of one document, in chunks of " +
            "$TEXT_CHUNK_CHARS characters. Use chunk 0 first; the output says how many chunks exist.",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("The docId (64-char hash) from a previous search result")
            val docId: String,
            @property:LLMDescription("0-based chunk index, defaults to 0")
            val chunk: Int = 0,
        )

        override suspend fun execute(args: Args): String {
            val text = ftsDao.contentForDoc(args.docId)?.takeIf { it.isNotBlank() }
                ?: return "Document ${args.docId} has no extracted text (not OCRed yet?)."
            val chunks = (text.length + TEXT_CHUNK_CHARS - 1) / TEXT_CHUNK_CHARS
            val index = args.chunk.coerceIn(0, chunks - 1)
            val start = index * TEXT_CHUNK_CHARS
            val end = minOf(start + TEXT_CHUNK_CHARS, text.length)
            return "Chunk ${index + 1} of $chunks (chars $start until $end of ${text.length}):\n" +
                text.substring(start, end)
        }
    }
}
