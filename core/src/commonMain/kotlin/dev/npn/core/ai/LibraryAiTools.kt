package dev.npn.core.ai

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.npn.core.db.DocumentDao
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.DocumentFieldDao
import dev.npn.core.db.FtsDao
import dev.npn.core.db.FtsQuery
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.TagDao
import dev.npn.core.model.FieldKeys
import dev.npn.core.ocr.OcrEngineId
import kotlinx.coroutines.flow.first
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The library tool set in the two shapes one chat run needs, over a single construction of the
 * tools: [tools] is what Koog advertises to the model through its `ToolRegistry`, [chatTools] is
 * the name-keyed dispatcher the streaming loop calls. The chat view hand-rolls argument decoding
 * instead of going through the registry because the streaming API hands us the raw args JSON, and
 * decoding it against our own `@Serializable Args` types is the smaller, type-safe path.
 */
class LibraryToolSet internal constructor(
    val tools: List<ToolBase<*, *>>,
    val chatTools: List<AiChatTool>,
)

/**
 * Read-only tools the chat agent can call to search the library index: OCR/embedded text,
 * tags, filenames, and single-value metadata. Tools never write; every result line carries
 * the full docId so the model can cite documents as `[doc:<docId>]`.
 */
object LibraryAiTools {
    const val MAX_SEARCH_RESULTS = 15
    const val MAX_LISTED_DOCS = 50
    const val TEXT_CHUNK_CHARS = 4_000

    /**
     * Build the library tool set once, in both shapes the chat agent needs (see [LibraryToolSet]).
     * Each tool is constructed a single time and shared by the two views, so a tool the model is
     * advertised is the same instance the loop dispatches to.
     */
    fun toolSetFor(db: NpnDatabase): LibraryToolSet {
        val json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }
        val entries =
            listOf(
                Entry(SearchDocumentsTool(db.ftsDao(), db.documentDao()), SearchDocumentsTool.Args.serializer()),
                Entry(ListTagsTool(db.tagDao()), ListTagsTool.Args.serializer()),
                Entry(DocumentsWithTagTool(db.documentDao()), DocumentsWithTagTool.Args.serializer()),
                Entry(SearchFileNamesTool(db.documentDao()), SearchFileNamesTool.Args.serializer()),
                Entry(ListFieldsTool(db.documentFieldDao()), ListFieldsTool.Args.serializer()),
                Entry(
                    SearchFieldsTool(db.documentDao(), db.documentFieldDao()),
                    SearchFieldsTool.Args.serializer(),
                ),
                Entry(
                    GetDocumentTool(db.documentDao(), db.tagDao(), db.documentFieldDao()),
                    GetDocumentTool.Args.serializer(),
                ),
                Entry(GetDocumentTextTool(db.ftsDao()), GetDocumentTextTool.Args.serializer()),
            )
        return LibraryToolSet(
            tools = entries.map { it.tool },
            chatTools = entries.map { it.chatTool(json) },
        )
    }

    /** One tool with the serializer for its `Args`, so both views can be derived from it. */
    private class Entry<A>(
        val tool: SimpleTool<A>,
        private val serializer: KSerializer<A>,
    ) {
        fun chatTool(json: Json): AiChatTool = tool.asChatTool(serializer, json)
    }

    private fun <A> SimpleTool<A>.asChatTool(
        serializer: KSerializer<A>,
        json: Json,
    ): AiChatTool =
        object : AiChatTool {
            override val name: String = this@asChatTool.name
            override val descriptor: ToolDescriptor = this@asChatTool.descriptor

            override suspend fun call(argsJson: String): String {
                val args = json.decodeFromString(serializer, argsJson.ifBlank { "{}" })
                return execute(args)
            }
        }

    internal fun docLine(
        doc: DocumentEntity,
        snippet: String? = null,
        field: Pair<String, String>? = null,
    ): String =
        buildString {
            append("- [doc:")
                .append(doc.docId)
                .append("] file=\"")
                .append(doc.fileName)
                .append('"')
            doc.title?.takeIf { it.isNotBlank() }?.let { append(" title=\"").append(it).append('"') }
            doc.docDate?.let { append(" date=").append(it) }
            doc.correspondent?.takeIf { it.isNotBlank() }?.let { append(" correspondent=\"").append(it).append('"') }
            field?.let {
                append(" field=\"")
                    .append(it.first)
                    .append("=")
                    .append(it.second)
                    .append('"')
            }
            snippet?.takeIf { it.isNotBlank() }?.let {
                append(" snippet=\"").append(stripSnippetMarkers(it)).append('"')
            }
        }

    /** FTS snippets mark matches with control chars \u0001/\u0002; the model needs plain text. */
    internal fun stripSnippetMarkers(snippet: String): String = snippet.replace("\u0001", "").replace("\u0002", "")

    private fun DocumentEntity.isLive(): Boolean = !trashed && !deleted && !missing

    /**
     * Built-in fields holding what a person wrote about a document. These are what an unqualified
     * ("any") field search scans, because they are the only ones a user phrases a search against.
     */
    private const val USER_FIELD_LIST = "title, correspondent, docType, docDate, notes, fileName"

    /**
     * Built-in fields the app maintains rather than the user. Searchable by name — "which documents
     * did the vision model read?" is a real question — but deliberately kept out of "any" searches:
     * their vocabularies are short common words, so `ocrEngine` would answer a search for "ai" with
     * every AI-transcribed document.
     */
    private const val SYSTEM_FIELD_LIST = "ocrEngine"

    /**
     * The default fields exactly as the model is told them. This literal is the source of truth for
     * the vocabulary — the sets below are parsed from it — because it has to be a compile-time
     * constant to appear in an [LLMDescription], and a field the tools accept but never advertise
     * is a field the model will not use.
     */
    internal const val DEFAULT_FIELD_LIST = "$USER_FIELD_LIST, $SYSTEM_FIELD_LIST"

    private fun fieldNames(list: String): Set<String> = list.split(", ").toSet()

    private val USER_FIELD_NAMES: Set<String> = fieldNames(USER_FIELD_LIST)

    private val SYSTEM_FIELD_NAMES: Set<String> = fieldNames(SYSTEM_FIELD_LIST)

    /**
     * Canonical names of the built-in single-value document fields the model can search with
     * [SearchFieldsTool].
     */
    internal val DEFAULT_FIELD_NAMES: Set<String> = USER_FIELD_NAMES + SYSTEM_FIELD_NAMES

    /** The engine labels [DocumentEntity.ocrEngine] can carry, as told to the model. */
    private val OCR_ENGINE_LABELS: String = OcrEngineId.entries.joinToString(" | ") { it.column }

    private val FIELD_ALIASES: Map<String, String> =
        mapOf(
            "file" to "fileName",
            "filename" to "fileName",
            "path" to "fileName",
            "name" to "fileName",
            "type" to "docType",
            "documenttype" to "docType",
            "date" to "docDate",
            "documentdate" to "docDate",
            "note" to "notes",
            "engine" to "ocrEngine",
            "ocr" to "ocrEngine",
        )

    /** Map a model-supplied field name (any casing) to its canonical default-field name. */
    private fun normalizeField(field: String): String {
        val f = field.trim()
        if (f.isEmpty()) return SearchFieldsTool.ANY_FIELD
        val lc = f.lowercase()
        if (lc == SearchFieldsTool.ANY_FIELD) return SearchFieldsTool.ANY_FIELD
        return DEFAULT_FIELD_NAMES.firstOrNull { it.lowercase() == lc } ?: FIELD_ALIASES[lc] ?: lc
    }

    class SearchDocumentsTool(
        private val ftsDao: FtsDao,
        private val documentDao: DocumentDao,
    ) : SimpleTool<SearchDocumentsTool.Args>(
            argsType = typeToken<Args>(),
            name = "search_documents",
            description =
                "Full-text search over all documents (OCR text, embedded text, titles, " +
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
            val lines =
                hits.mapNotNull { hit ->
                    documentDao
                        .getByDocId(hit.docId)
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
            description =
                "Find documents whose file name or folder path contains a substring " +
                    "(case-insensitive). Useful when the user remembers part of a file name.",
        ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Substring to look for in file names and paths")
            val pattern: String,
        )

        override suspend fun execute(args: Args): String {
            if (args.pattern.isBlank()) return "Error: pattern must not be blank."
            val lines =
                documentDao
                    .getAllLive()
                    .asSequence()
                    .filter {
                        it.fileName.contains(args.pattern, ignoreCase = true) ||
                            it.relPath.contains(args.pattern, ignoreCase = true)
                    }.take(MAX_LISTED_DOCS)
                    .map { docLine(it) }
                    .toList()
            return if (lines.isEmpty()) {
                "No file names or paths contain \"${args.pattern}\"."
            } else {
                lines.joinToString("\n")
            }
        }
    }

    class ListFieldsTool(
        private val fieldDao: DocumentFieldDao,
    ) : SimpleTool<ListFieldsTool.Args>(
            argsType = typeToken<Args>(),
            name = "list_fields",
            description =
                "List the metadata fields available for field search: the built-in default " +
                    "fields ($DEFAULT_FIELD_LIST) plus every custom " +
                    "(flexible) field key in use across the library, with document counts. Call this to " +
                    "discover flexible field keys before using search_fields.",
        ) {
        @Serializable
        class Args

        override suspend fun execute(args: Args): String {
            // document_fields now also holds the default fields; until the tools speak the unified
            // registry vocabulary (plan P5), keep them out of the "custom" list.
            val keys = fieldDao.keysWithCounts().filter { it.fieldKey !in FieldKeys.WELL_KNOWN }
            return buildString {
                appendLine("Default fields: $DEFAULT_FIELD_LIST")
                appendLine(
                    "ocrEngine is the engine whose text is indexed for search ($OCR_ENGINE_LABELS); " +
                        "it is set by the app, not the user.",
                )
                if (keys.isEmpty()) {
                    append("No custom (flexible) fields are in use yet.")
                } else {
                    append(
                        "Custom (flexible) fields: " +
                            keys.joinToString(", ") { "${it.fieldKey} (${it.count} docs)" },
                    )
                }
            }
        }
    }

    class SearchFieldsTool(
        private val documentDao: DocumentDao,
        private val fieldDao: DocumentFieldDao,
    ) : SimpleTool<SearchFieldsTool.Args>(
            argsType = typeToken<Args>(),
            name = "search_fields",
            description =
                "Find documents by one metadata field. The field may be a " +
                    "default field ($DEFAULT_FIELD_LIST) or a custom " +
                    "(flexible) field key discovered via list_fields. ocrEngine is set by the app, not " +
                    "the user: it names the engine whose transcription is indexed for search " +
                    "($OCR_ENGINE_LABELS). Omit field or pass \"any\" to search " +
                    "every user-authored field at once (\"any\" does not scan ocrEngine, which must be " +
                    "asked for by name). Leave value empty to find documents that simply have the field; " +
                    "otherwise the value is matched as a case-insensitive substring.",
        ) {
        @Serializable
        data class Args(
            @property:LLMDescription(
                "Field to search: $DEFAULT_FIELD_LIST, " +
                    "a custom field key from list_fields, or \"any\" for every user-authored field.",
            )
            val field: String = "any",
            @property:LLMDescription(
                "Case-insensitive substring to look for in the field's value; partial " +
                    "dates work (e.g. \"2026\"). Leave empty to find documents that have the field set.",
            )
            val value: String = "",
        )

        override suspend fun execute(args: Args): String {
            val normalized = normalizeField(args.field)
            val value = args.value.trim()
            if (normalized == ANY_FIELD && value.isEmpty()) {
                return "Error: specify a field, or a value to search for."
            }
            val matches =
                when {
                    normalized == ANY_FIELD -> searchAnyField(value)
                    normalized in DEFAULT_FIELD_NAMES -> searchDefaultField(normalized, value)
                    else -> searchCustomField(normalized, value)
                }
            val lines = linesForMatches(matches)
            if (lines.isEmpty()) {
                return if (normalized == ANY_FIELD || normalized in DEFAULT_FIELD_NAMES) {
                    if (value.isEmpty()) {
                        "No documents have a \"${args.field}\" field."
                    } else {
                        "No documents have a \"${args.field}\" value containing \"$value\"."
                    }
                } else if (value.isEmpty()) {
                    "No documents have a custom field \"${args.field}\"." +
                        " Default fields: $DEFAULT_FIELD_LIST." +
                        " Call list_fields to see the custom field keys in use."
                } else {
                    "No documents have a custom field \"${args.field}\" containing \"$value\"." +
                        " Default fields: $DEFAULT_FIELD_LIST." +
                        " Call list_fields to see the custom field keys in use."
                }
            }
            return lines.joinToString("\n")
        }

        /** Resolve docId -> matched (key, value) lines, deduped per document. */
        private suspend fun linesForMatches(matches: Map<String, Pair<String, String>>): List<String> {
            if (matches.isEmpty()) return emptyList()
            val docs = documentDao.getByDocIds(matches.keys.toList()).associateBy { it.docId }
            return matches
                .asSequence()
                .mapNotNull { (docId, kv) -> docs[docId]?.takeIf { it.isLive() }?.let { docLine(it, field = kv) } }
                .take(MAX_LISTED_DOCS)
                .toList()
        }

        private suspend fun searchAnyField(value: String): Map<String, Pair<String, String>> {
            val matches = LinkedHashMap<String, Pair<String, String>>()
            for (doc in documentDao.getAllLive()) {
                defaultFieldValues(doc)
                    .firstOrNull { it.first !in SYSTEM_FIELD_NAMES && it.second.contains(value, ignoreCase = true) }
                    ?.let { matches.putIfAbsent(doc.docId, it) }
            }
            for (f in fieldDao.all()) {
                if (f.fieldKey in FieldKeys.WELL_KNOWN) continue // already covered via the doc row above
                if (f.fieldValue.contains(value, ignoreCase = true)) {
                    matches.putIfAbsent(f.docId, f.fieldKey to f.fieldValue)
                }
            }
            return matches
        }

        private suspend fun searchDefaultField(
            key: String,
            value: String,
        ): Map<String, Pair<String, String>> =
            documentDao
                .getAllLive()
                .asSequence()
                .mapNotNull { doc ->
                    val matched =
                        defaultFieldValues(doc).firstOrNull {
                            it.first == key && it.second.contains(value, ignoreCase = true)
                        }
                    matched?.let { doc.docId to it }
                }.toMap(LinkedHashMap())

        private suspend fun searchCustomField(
            key: String,
            value: String,
        ): Map<String, Pair<String, String>> {
            val fields = fieldDao.all().filter { it.fieldKey.lowercase() == key }
            return fields
                .asSequence()
                .filter { it.fieldValue.contains(value, ignoreCase = true) }
                .map { it.docId to (it.fieldKey to it.fieldValue) }
                .toMap(LinkedHashMap())
        }

        private fun defaultFieldValues(doc: DocumentEntity): List<Pair<String, String>> =
            buildList {
                doc.title?.takeIf { it.isNotBlank() }?.let { add("title" to it) }
                doc.correspondent?.takeIf { it.isNotBlank() }?.let { add("correspondent" to it) }
                doc.docType?.takeIf { it.isNotBlank() }?.let { add("docType" to it) }
                doc.docDate?.takeIf { it.isNotBlank() }?.let { add("docDate" to it) }
                doc.notes?.takeIf { it.isNotBlank() }?.let { add("notes" to it) }
                add("fileName" to doc.fileName)
                doc.ocrEngine?.takeIf { it.isNotBlank() }?.let { add("ocrEngine" to it) }
            }

        companion object {
            const val ANY_FIELD = "any"
        }
    }

    class GetDocumentTool(
        private val documentDao: DocumentDao,
        private val tagDao: TagDao,
        private val fieldDao: DocumentFieldDao,
    ) : SimpleTool<GetDocumentTool.Args>(
            argsType = typeToken<Args>(),
            name = "get_document",
            description =
                "Full metadata of one document by docId: file name, path, title, tags, " +
                    "correspondent, document type, date, notes, custom (flexible) fields, page count, " +
                    "and which OCR engine's text is indexed for it.",
        ) {
        @Serializable
        data class Args(
            @property:LLMDescription("The docId (64-char hash) from a previous search result")
            val docId: String,
        )

        override suspend fun execute(args: Args): String {
            val doc =
                documentDao.getByDocId(args.docId)
                    ?: return "No document with docId ${args.docId}."
            val tags = tagDao.tagsForDocument(args.docId)
            val fields =
                fieldDao
                    .fieldsForDocs(listOf(args.docId))
                    .filter { it.fieldKey !in FieldKeys.WELL_KNOWN }
                    .sortedBy { it.fieldKey.lowercase() }
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
                if (fields.isNotEmpty()) {
                    appendLine("customFields:")
                    for (f in fields) appendLine("  ${f.fieldKey}: ${f.fieldValue}")
                }
                // Both halves, because they answer different questions: which text search actually
                // reads, and whether the user pinned that choice by hand.
                appendLine("ocrEngine: ${doc.ocrEngine ?: "(no text indexed yet)"}")
                doc.ocrEngineOverride?.let { appendLine("ocrEnginePinnedByUser: $it") }
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
            description =
                "Read the extracted text (OCR or embedded) of one document, in chunks of " +
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
            val text =
                ftsDao.contentForDoc(args.docId)?.takeIf { it.isNotBlank() }
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
