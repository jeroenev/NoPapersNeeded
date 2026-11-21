package dev.npn.core.ai

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolBase
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
import dev.npn.core.db.isLive
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.ocr.OcrEngineId
import dev.npn.core.util.NpnJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The library tools as one chat run needs them, built from one construction of each tool: [tools]
 * is what Koog advertises to the model through its `ToolRegistry`, [calls] runs a tool by name on
 * the raw arguments JSON the model streamed back. The loop calls tools itself instead of going
 * through the registry because the streaming API hands it that raw JSON, and decoding it against
 * the tools' own `@Serializable Args` types is the smaller, type-safe path.
 */
internal class LibraryToolSet(
    val tools: List<ToolBase<*, *>>,
    val calls: Map<String, suspend (argsJson: String) -> String>,
)

/**
 * The names the chat tools are advertised under, and the argument keys a transcript labels a call
 * by: the model calls tools by these, and the chat screen shows its rows by them.
 */
object ChatTools {
    const val SEARCH_DOCUMENTS = "search_documents"
    const val LIST_TAGS = "list_tags"
    const val DOCUMENTS_WITH_TAG = "documents_with_tag"
    const val SEARCH_FILENAMES = "search_filenames"
    const val LIST_FIELDS = "list_fields"
    const val SEARCH_FIELDS = "search_fields"
    const val GET_DOCUMENT = "get_document"
    const val GET_DOCUMENT_TEXT = "get_document_text"

    /** [SEARCH_DOCUMENTS]' search terms. */
    const val ARG_QUERY = "query"

    /** [DOCUMENTS_WITH_TAG]'s tag name. */
    const val ARG_TAG = "tag"

    /** [SEARCH_FILENAMES]' substring. */
    const val ARG_PATTERN = "pattern"

    /** The document [GET_DOCUMENT] and [GET_DOCUMENT_TEXT] read. */
    const val ARG_DOC_ID = "docId"

    /** The field [SEARCH_FIELDS] searches: a key or label, or [ANY_FIELD]. */
    const val ARG_FIELD = "field"

    /** What [SEARCH_FIELDS] looks for in the field's value. */
    const val ARG_VALUE = "value"

    /** [SEARCH_FIELDS]' inclusive range bounds, for a date or number field. */
    const val ARG_FROM = "from"
    const val ARG_TO = "to"

    /** [ARG_FIELD]'s value for every user-authored field at once (also what an omitted field means). */
    const val ANY_FIELD = "any"
}

/**
 * Read-only tools the chat agent can call to search the library index: OCR/embedded text,
 * tags, filenames, and single-value metadata. Tools never write; every result line carries
 * the full docId so the model can cite documents as `[doc:<docId>]`.
 */
internal object LibraryAiTools {
    const val MAX_SEARCH_RESULTS = 15
    const val MAX_LISTED_DOCS = 50
    const val TEXT_CHUNK_CHARS = 4_000

    /**
     * Build the library tool set (see [LibraryToolSet]). Each tool is constructed once, so a tool the
     * model is advertised is the same instance the loop calls.
     */
    fun toolSetFor(
        db: NpnDatabase,
        fields: List<FieldDef>,
    ): LibraryToolSet {
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
            calls = entries.associate { it.tool.name to it::call },
        )
    }

    /** One tool with the serializer for its `Args`, so it can be called on the model's raw JSON. */
    private class Entry<A>(
        val tool: SimpleTool<A>,
        private val serializer: KSerializer<A>,
    ) {
        suspend fun call(argsJson: String): String =
            tool.execute(NpnJson.Lenient.decodeFromString(serializer, argsJson.ifBlank { "{}" }))
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
                .append(escapeQuoted(doc.fileName))
                .append('"')
            doc.title?.takeIf { it.isNotBlank() }?.let { append(" title=\"").append(escapeQuoted(it)).append('"') }
            doc.docDate?.let { append(" date=").append(it) }
            doc.correspondent
                ?.takeIf { it.isNotBlank() }
                ?.let { append(" correspondent=\"").append(escapeQuoted(it)).append('"') }
            field?.let {
                append(" field=\"")
                    .append(escapeQuoted(it.first))
                    .append("=")
                    .append(escapeQuoted(it.second))
                    .append('"')
            }
            snippet?.takeIf { it.isNotBlank() }?.let {
                append(" snippet=\"").append(escapeQuoted(stripSnippetMarkers(it))).append('"')
            }
        }

    /** Escape values embedded in the tool's quoted, one-document-per-line format. */
    internal fun escapeQuoted(value: String): String =
        buildString(value.length) {
            for (char in value) {
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
        }

    /** FTS snippets mark matches with control chars \u0001/\u0002; the model needs plain text. */
    internal fun stripSnippetMarkers(snippet: String): String = snippet.replace("\u0001", "").replace("\u0002", "")

    /** The file name: a document column, searchable like a field but not a registry entry. */
    const val FILE_NAME_FIELD = "fileName"

    /** The library-relative path, distinct from the base [FILE_NAME_FIELD]. */
    const val PATH_FIELD = "path"

    /**
     * The engine whose text is indexed: maintained by the app rather than the user. Searchable by
     * name — "which documents did the vision model read?" is a real question — but deliberately
     * kept out of "any" searches: its vocabulary is short common words, so it would answer a search
     * for "ai" with every AI-transcribed document.
     */
    const val OCR_ENGINE_FIELD = "ocrEngine"

    /** The document columns searchable like fields, each with how a row's value is read. */
    private val COLUMN_FIELDS: Map<String, (DocumentEntity) -> String?> =
        linkedMapOf(
            FILE_NAME_FIELD to { it.fileName },
            PATH_FIELD to { it.relPath },
            OCR_ENGINE_FIELD to { it.ocrEngine },
        )

    /** The engine labels [DocumentEntity.ocrEngine] can carry, as told to the model. */
    private val OCR_ENGINE_LABELS: String = OcrEngineId.entries.joinToString(" | ") { it.column }

    /**
     * The field vocabulary as the model is told it, built from the live registry per session. The
     * `@LLMDescription` annotations stay generic ("a key from list_fields") because annotation
     * arguments must be compile-time constants; this string goes into the tool descriptions and the
     * chat system prompt instead, which are built at runtime.
     */
    fun fieldVocabulary(fields: List<FieldDef>): String =
        (fields.map { "${it.key} (${it.label}, ${it.type.wire})" } + COLUMN_FIELDS.keys).joinToString(", ")

    private val FIELD_ALIASES: Map<String, String> =
        mapOf(
            "file" to FILE_NAME_FIELD,
            "filename" to FILE_NAME_FIELD,
            "path" to PATH_FIELD,
            "name" to FILE_NAME_FIELD,
            "engine" to OCR_ENGINE_FIELD,
            "ocr" to OCR_ENGINE_FIELD,
        )

    /**
     * Map a model-supplied field name (any casing; a key, a label or an alias) to the key the
     * index stores it under. A registered field resolves to its defined key, casing included;
     * an unregistered name comes back lowercased, which still finds an orphan row of any casing
     * because `SearchFieldsTool.searchField` compares keys case-insensitively.
     */
    private fun normalizeField(
        field: String,
        fields: List<FieldDef>,
    ): String {
        val f = field.trim()
        if (f.isEmpty()) return SearchFieldsTool.ANY_FIELD
        val lc = f.lowercase()
        if (lc == SearchFieldsTool.ANY_FIELD) return SearchFieldsTool.ANY_FIELD
        return fields.firstOrNull { FieldKeys.sameKey(it.key, f) || it.label.equals(f, ignoreCase = true) }?.key
            ?: COLUMN_FIELDS.keys.firstOrNull { it.equals(f, ignoreCase = true) }
            ?: FIELD_ALIASES[lc]
            ?: lc
    }

    /**
     * Every live document with a non-blank value in one of [columns] that contains [needle],
     * case-insensitively, paired with the first such value; an empty [needle] matches any value.
     */
    private suspend fun liveDocsMatching(
        documentDao: DocumentDao,
        needle: String,
        vararg columns: (DocumentEntity) -> String?,
    ): Sequence<Pair<DocumentEntity, String>> =
        documentDao.getAllLive().asSequence().mapNotNull { doc ->
            columns
                .firstNotNullOfOrNull { column ->
                    column(doc)?.takeIf { it.isNotBlank() && it.contains(needle, ignoreCase = true) }
                }?.let { doc to it }
        }

    class SearchDocumentsTool(
        private val ftsDao: FtsDao,
        private val documentDao: DocumentDao,
    ) : SimpleTool<SearchDocumentsTool.Args>(
            argsType = typeToken<Args>(),
            name = ChatTools.SEARCH_DOCUMENTS,
            description =
                "Full-text search over all documents (OCR text, embedded text, titles, " +
                    "filenames, tags, notes). Terms are AND-combined. Returns matching documents " +
                    "ranked by relevance, each with a docId and a snippet.",
        ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Search terms, e.g. \"invoice 2024 electricity\"")
            @SerialName(ChatTools.ARG_QUERY)
            val query: String,
        )

        override suspend fun execute(args: Args): String {
            if (args.query.isBlank()) return "Error: query must not be blank."
            val match = FtsQuery.sanitize(args.query)
            val hits = ftsDao.search(match)
            val docs = documentDao.getByDocIds(hits).associateBy { it.docId }
            val shown = hits.mapNotNull { docs[it]?.takeIf { doc -> doc.isLive } }.take(MAX_SEARCH_RESULTS)
            val snippets = ftsDao.snippetsFor(match, shown.map { it.docId })
            val lines = shown.map { docLine(it, snippets[it.docId]) }
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
            name = ChatTools.LIST_TAGS,
            description = "List every tag in the library with its document count.",
        ) {
        @Serializable
        class Args

        override suspend fun execute(args: Args): String {
            val tags = tagDao.allWithLiveCounts()
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
            name = ChatTools.DOCUMENTS_WITH_TAG,
            description = "List documents carrying an exact tag name (use ${ChatTools.LIST_TAGS} to discover names).",
        ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Exact tag name")
            @SerialName(ChatTools.ARG_TAG)
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
            name = ChatTools.SEARCH_FILENAMES,
            description =
                "Find documents whose file name or folder path contains a substring " +
                    "(case-insensitive). Useful when the user remembers part of a file name.",
        ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Substring to look for in file names and paths")
            @SerialName(ChatTools.ARG_PATTERN)
            val pattern: String,
        )

        override suspend fun execute(args: Args): String {
            if (args.pattern.isBlank()) return "Error: pattern must not be blank."
            val lines =
                liveDocsMatching(documentDao, args.pattern, { it.fileName }, { it.relPath })
                    .take(MAX_LISTED_DOCS)
                    .map { (doc, _) -> docLine(doc) }
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
            name = ChatTools.LIST_FIELDS,
            description =
                "List the metadata fields documents carry: every registered field (key, label, type, " +
                    "whether it shows on every document) with document counts, keys in use without a " +
                    "definition, and the document columns searchable like fields: $FILE_NAME_FIELD, $PATH_FIELD, " +
                    "and $OCR_ENGINE_FIELD. Call this to discover field keys before using ${ChatTools.SEARCH_FIELDS}.",
        ) {
        @Serializable
        class Args

        override suspend fun execute(args: Args): String {
            val counts = fieldDao.keysWithLiveCounts().associate { it.fieldKey to it.count }
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
                    "Also searchable: $FILE_NAME_FIELD (the base file name), " +
                        "$PATH_FIELD (the library-relative path), " +
                        "and $OCR_ENGINE_FIELD (the engine whose " +
                        "text is indexed for search: $OCR_ENGINE_LABELS; " +
                        "set by the app, not the user).",
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
            name = ChatTools.SEARCH_FIELDS,
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
                "Field to search: a key (or label) from ${ChatTools.LIST_FIELDS}, $FILE_NAME_FIELD, $PATH_FIELD, " +
                    "$OCR_ENGINE_FIELD, " +
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
                    in COLUMN_FIELDS -> searchColumn(key, value, COLUMN_FIELDS.getValue(key))
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
                val hint = if (isKnownField(key)) "" else " Call ${ChatTools.LIST_FIELDS} to see the field keys in use."
                return "No documents have $what.$hint"
            }
            return lines.joinToString("\n")
        }

        private fun isKnownField(key: String): Boolean =
            key == ANY_FIELD || key in COLUMN_FIELDS || fields.any { it.key == key }

        /** Resolve docId -> matched (key, value) lines, deduped per document. */
        private suspend fun linesForMatches(matches: Map<String, Pair<String, String>>): List<String> {
            if (matches.isEmpty()) return emptyList()
            val docs = documentDao.getByDocIds(matches.keys.toList()).associateBy { it.docId }
            return matches
                .asSequence()
                .mapNotNull { (docId, kv) -> docs[docId]?.takeIf { it.isLive }?.let { docLine(it, field = kv) } }
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
            liveDocsMatching(documentDao, value, column)
                .map { (doc, v) -> doc.docId to (key to v) }
                .toMap(LinkedHashMap())

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
            name = ChatTools.GET_DOCUMENT,
            description =
                "Full metadata of one document by docId: file name, path, tags, every field value " +
                    "(registry order, then keys without a definition), page count, and which OCR " +
                    "engine's text is indexed for it.",
        ) {
        @Serializable
        data class Args(
            @property:LLMDescription("The docId (64-char hash) from a previous search result")
            @SerialName(ChatTools.ARG_DOC_ID)
            val docId: String,
        )

        override suspend fun execute(args: Args): String {
            val doc =
                documentDao.getByDocId(args.docId)
                    ?: return "No document with docId ${args.docId}."
            val tags = tagDao.tagsForDocument(args.docId)
            val values = fieldDao.valuesOf(args.docId)
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
                if (doc.deleted) appendLine("status: permanently deleted")
            }.trimEnd()
        }
    }

    class GetDocumentTextTool(
        private val ftsDao: FtsDao,
    ) : SimpleTool<GetDocumentTextTool.Args>(
            argsType = typeToken<Args>(),
            name = ChatTools.GET_DOCUMENT_TEXT,
            description =
                "Read the extracted text (OCR or embedded) of one document, in chunks of " +
                    "$TEXT_CHUNK_CHARS characters. Use chunk 0 first; the output says how many chunks exist.",
        ) {
        @Serializable
        data class Args(
            @property:LLMDescription("The docId (64-char hash) from a previous search result")
            @SerialName(ChatTools.ARG_DOC_ID)
            val docId: String,
            @property:LLMDescription("0-based chunk index, defaults to 0")
            val chunk: Int = 0,
        )

        override suspend fun execute(args: Args): String {
            val text =
                ftsDao.textOf(args.docId)
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
