package dev.npn.core.ai

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.npn.core.db.DocumentDao
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.DocumentFieldDao
import dev.npn.core.db.DocumentFieldEntity
import dev.npn.core.db.FtsDao
import dev.npn.core.db.FtsQuery
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.TagDao
import dev.npn.core.model.FieldDef
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
    fun toolSetFor(
        db: NpnDatabase,
        fields: List<FieldDef>,
    ): LibraryToolSet {
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
                Entry(ListFieldsTool(db.documentFieldDao(), fields), ListFieldsTool.Args.serializer()),
                Entry(
                    SearchFieldsTool(db.documentDao(), db.documentFieldDao(), fields),
                    SearchFieldsTool.Args.serializer(),
                ),
                Entry(
                    GetDocumentTool(db.documentDao(), db.tagDao(), db.documentFieldDao(), fields),
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

    /** The file name: a document column, searchable like a field but not a registry entry. */
    const val FILE_NAME_FIELD = "fileName"

    /**
     * The engine whose text is indexed: maintained by the app rather than the user. Searchable by
     * name — "which documents did the vision model read?" is a real question — but deliberately
     * kept out of "any" searches: its vocabulary is short common words, so it would answer a search
     * for "ai" with every AI-transcribed document.
     */
    const val OCR_ENGINE_FIELD = "ocrEngine"

    /** The engine labels [DocumentEntity.ocrEngine] can carry, as told to the model. */
    private val OCR_ENGINE_LABELS: String = OcrEngineId.entries.joinToString(" | ") { it.column }

    /**
     * The field vocabulary as the model is told it, built from the live registry per session. The
     * `@LLMDescription` annotations stay generic ("a key from list_fields") because annotation
     * arguments must be compile-time constants; this string goes into the tool descriptions and the
     * chat system prompt instead, which are built at runtime.
     */
    fun fieldVocabulary(fields: List<FieldDef>): String =
        (fields.map { "${it.key} (${it.label}, ${it.type.wire})" } + FILE_NAME_FIELD + OCR_ENGINE_FIELD)
            .joinToString(", ")

    private val FIELD_ALIASES: Map<String, String> =
        mapOf(
            "file" to FILE_NAME_FIELD,
            "filename" to FILE_NAME_FIELD,
            "path" to FILE_NAME_FIELD,
            "name" to FILE_NAME_FIELD,
            "engine" to OCR_ENGINE_FIELD,
            "ocr" to OCR_ENGINE_FIELD,
        )

    /**
     * Map a model-supplied field name (any casing; a key, a label or an alias) to the key the
     * index stores it under. Unknown names come back lowercased so an orphan key still matches.
     */
    private fun normalizeField(
        field: String,
        fields: List<FieldDef>,
    ): String {
        val f = field.trim()
        if (f.isEmpty()) return SearchFieldsTool.ANY_FIELD
        val lc = f.lowercase()
        if (lc == SearchFieldsTool.ANY_FIELD) return SearchFieldsTool.ANY_FIELD
        return fields.firstOrNull { it.key.lowercase() == lc || it.label.lowercase() == lc }?.key
            ?: listOf(FILE_NAME_FIELD, OCR_ENGINE_FIELD).firstOrNull { it.lowercase() == lc }
            ?: FIELD_ALIASES[lc]
            ?: lc
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
        private val fields: List<FieldDef>,
    ) : SimpleTool<ListFieldsTool.Args>(
            argsType = typeToken<Args>(),
            name = "list_fields",
            description =
                "List the metadata fields documents carry: every registered field (key, label, type, " +
                    "whether it shows on every document) with document counts, keys in use without a " +
                    "definition, and the two document columns searchable like fields: $FILE_NAME_FIELD and " +
                    "$OCR_ENGINE_FIELD. Call this to discover field keys before using search_fields.",
        ) {
        @Serializable
        class Args

        override suspend fun execute(args: Args): String {
            val counts = fieldDao.keysWithCounts().associate { it.fieldKey to it.count }
            val registered = fields.map { it.key }.toSet()
            return buildString {
                appendLine("Registered fields (key · label · type · documents with a value):")
                for (def in fields) {
                    val shown = if (def.showByDefault) ", shown on every document" else ""
                    appendLine("  ${def.key} · ${def.label} · ${def.type.wire}$shown · ${counts[def.key] ?: 0} docs")
                }
                val orphans = counts.filterKeys { it !in registered }
                if (orphans.isNotEmpty()) {
                    appendLine(
                        "Keys in use without a definition: " +
                            orphans.entries.joinToString(", ") { "${it.key} (${it.value} docs)" },
                    )
                }
                append(
                    "Also searchable: $FILE_NAME_FIELD (the file name) and $OCR_ENGINE_FIELD (the engine whose " +
                        "text is indexed for search: $OCR_ENGINE_LABELS; set by the app, not the user).",
                )
            }
        }
    }

    class SearchFieldsTool(
        private val documentDao: DocumentDao,
        private val fieldDao: DocumentFieldDao,
        private val fields: List<FieldDef>,
    ) : SimpleTool<SearchFieldsTool.Args>(
            argsType = typeToken<Args>(),
            name = "search_fields",
            description =
                "Find documents by one metadata field. Fields in this library: ${fieldVocabulary(fields)}. " +
                    "$OCR_ENGINE_FIELD is set by the app, not the user ($OCR_ENGINE_LABELS). Omit field or " +
                    "pass \"any\" to search every user-authored field at once (\"any\" does not scan " +
                    "$OCR_ENGINE_FIELD, which must be asked for by name). Leave value empty to find documents " +
                    "that simply have the field; otherwise the value is matched as a case-insensitive " +
                    "substring. For date and number fields, from/to select an inclusive range instead " +
                    "(dates as YYYY-MM-DD, numbers plain).",
        ) {
        @Serializable
        data class Args(
            @property:LLMDescription(
                "Field to search: a key (or label) from list_fields, $FILE_NAME_FIELD, $OCR_ENGINE_FIELD, " +
                    "or \"any\" for every user-authored field.",
            )
            val field: String = "any",
            @property:LLMDescription(
                "Case-insensitive substring to look for in the field's value; partial " +
                    "dates work (e.g. \"2026\"). Leave empty to find documents that have the field set.",
            )
            val value: String = "",
            @property:LLMDescription("Inclusive lower bound for a date (YYYY-MM-DD) or number field.")
            val from: String? = null,
            @property:LLMDescription("Inclusive upper bound for a date (YYYY-MM-DD) or number field.")
            val to: String? = null,
        )

        override suspend fun execute(args: Args): String {
            val key = normalizeField(args.field, fields)
            val value = args.value.trim()
            val range = args.from?.trim()?.ifEmpty { null } to args.to?.trim()?.ifEmpty { null }
            if (key == ANY_FIELD && value.isEmpty()) {
                return "Error: specify a field, or a value to search for."
            }
            val matches =
                when (key) {
                    ANY_FIELD -> searchAnyField(value)
                    FILE_NAME_FIELD -> searchColumn(FILE_NAME_FIELD, value) { it.fileName }
                    OCR_ENGINE_FIELD -> searchColumn(OCR_ENGINE_FIELD, value) { it.ocrEngine }
                    else -> searchField(key, value, range)
                }
            val lines = linesForMatches(matches)
            if (lines.isEmpty()) {
                val what =
                    if (value.isEmpty()) {
                        "a \"${args.field}\" field"
                    } else {
                        "a \"${args.field}\" value containing \"$value\""
                    }
                val hint = if (isKnownField(key)) "" else " Call list_fields to see the field keys in use."
                return "No documents have $what.$hint"
            }
            return lines.joinToString("\n")
        }

        private fun isKnownField(key: String): Boolean =
            key == ANY_FIELD || key == FILE_NAME_FIELD || key == OCR_ENGINE_FIELD || fields.any { it.key == key }

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

        /** Every user-authored value: the field rows plus the file name. Never ocrEngine. */
        private suspend fun searchAnyField(value: String): Map<String, Pair<String, String>> {
            val matches = LinkedHashMap<String, Pair<String, String>>()
            for (f in fieldDao.all()) {
                if (f.fieldValue.contains(value, ignoreCase = true)) {
                    matches.putIfAbsent(f.docId, f.fieldKey to f.fieldValue)
                }
            }
            for ((docId, kv) in searchColumn(FILE_NAME_FIELD, value) { it.fileName }) matches.putIfAbsent(docId, kv)
            return matches
        }

        private suspend fun searchColumn(
            key: String,
            value: String,
            column: (DocumentEntity) -> String?,
        ): Map<String, Pair<String, String>> =
            documentDao
                .getAllLive()
                .asSequence()
                .mapNotNull { doc ->
                    val v = column(doc)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    if (v.contains(value, ignoreCase = true)) doc.docId to (key to v) else null
                }.toMap(LinkedHashMap())

        /**
         * One `document_fields` query serves every registry field and orphan key alike. A range
         * compares the numeric shadow when the row has one and the canonical string otherwise, which
         * for ISO dates is chronological.
         */
        private suspend fun searchField(
            key: String,
            value: String,
            range: Pair<String?, String?>,
        ): Map<String, Pair<String, String>> {
            val (from, to) = range
            return fieldDao
                .all()
                .asSequence()
                .filter { it.fieldKey.equals(key, ignoreCase = true) }
                .filter { it.fieldValue.contains(value, ignoreCase = true) }
                .filter { row -> from == null || compare(row, from) >= 0 }
                .filter { row -> to == null || compare(row, to) <= 0 }
                .map { it.docId to (it.fieldKey to it.fieldValue) }
                .toMap(LinkedHashMap())
        }

        private fun compare(
            row: DocumentFieldEntity,
            bound: String,
        ): Int {
            val num = row.numValue
            val boundNum = bound.toDoubleOrNull()
            return if (num != null && boundNum != null) num.compareTo(boundNum) else row.fieldValue.compareTo(bound)
        }

        companion object {
            const val ANY_FIELD = "any"
        }
    }

    class GetDocumentTool(
        private val documentDao: DocumentDao,
        private val tagDao: TagDao,
        private val fieldDao: DocumentFieldDao,
        private val fields: List<FieldDef>,
    ) : SimpleTool<GetDocumentTool.Args>(
            argsType = typeToken<Args>(),
            name = "get_document",
            description =
                "Full metadata of one document by docId: file name, path, tags, every field value " +
                    "(registry order, then keys without a definition), page count, and which OCR " +
                    "engine's text is indexed for it.",
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
            val values = fieldDao.fieldsForDocs(listOf(args.docId)).associate { it.fieldKey to it.fieldValue }
            val order = fields.map { it.key } + (values.keys - fields.map { it.key }.toSet()).sorted()
            return buildString {
                appendLine("docId: ${doc.docId}")
                appendLine("file: ${doc.fileName}")
                appendLine("path: ${doc.relPath}")
                appendLine("tags: ${if (tags.isEmpty()) "(none)" else tags.joinToString(", ")}")
                for (key in order) values[key]?.let { appendLine("$key: $it") }
                doc.pageCount?.let { appendLine("pages: $it") }
                // Both halves, because they answer different questions: which text search actually
                // reads, and whether the user pinned that choice by hand.
                appendLine("$OCR_ENGINE_FIELD: ${doc.ocrEngine ?: "(no text indexed yet)"}")
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
