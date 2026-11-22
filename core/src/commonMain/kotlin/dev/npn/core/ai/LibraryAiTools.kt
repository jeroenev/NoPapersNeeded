package dev.npn.core.ai

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.npn.core.app.HistoryEntry
import dev.npn.core.db.DocumentDao
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.DocumentFieldDao
import dev.npn.core.db.DocumentFieldEntity
import dev.npn.core.db.FtsDao
import dev.npn.core.db.FtsQuery
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.TagDao
import dev.npn.core.db.isLive
import dev.npn.core.journal.EventDecoderRegistry
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.ReviewMode
import dev.npn.core.model.reviewMarkerIsAuthoritative
import dev.npn.core.ocr.OcrEngineId
import dev.npn.core.ocr.OcrState
import dev.npn.core.util.NpnJson
import dev.npn.core.util.runCatchingCancellable
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

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
    const val GET_DOCUMENT_HISTORY = "get_document_history"
    const val LIBRARY_STATS = "library_stats"

    /** [SEARCH_DOCUMENTS]' search terms. */
    const val ARG_QUERY = "query"

    /** [DOCUMENTS_WITH_TAG]'s tag name. */
    const val ARG_TAG = "tag"

    /** [SEARCH_FILENAMES]' substring. */
    const val ARG_PATTERN = "pattern"

    /** The document [GET_DOCUMENT], [GET_DOCUMENT_TEXT] and [GET_DOCUMENT_HISTORY] read. */
    const val ARG_DOC_ID = "docId"

    /** The field [SEARCH_FIELDS] searches: a key or label, or [ANY_FIELD]. */
    const val ARG_FIELD = "field"

    /** What [SEARCH_FIELDS] looks for in the field's value. */
    const val ARG_VALUE = "value"

    /** [SEARCH_FIELDS]' inclusive range bounds, for a date or number field. */
    const val ARG_FROM = "from"
    const val ARG_TO = "to"

    /** [SEARCH_DOCUMENTS]' inclusive range on when a document joined the library. */
    const val ARG_ADDED_FROM = "addedAtFrom"
    const val ARG_ADDED_TO = "addedAtTo"

    /** [SEARCH_DOCUMENTS]' OCR pipeline state filter: one [OcrState.column]. */
    const val ARG_OCR_STATE = "ocrState"

    /** [SEARCH_DOCUMENTS]' review-marker filter. */
    const val ARG_REVIEWED = "reviewed"

    /** [SEARCH_DOCUMENTS]' lifecycle filter: one of the `LIFECYCLE_*` values. */
    const val ARG_LIFECYCLE = "lifecycle"

    const val LIFECYCLE_LIVE = "live"
    const val LIFECYCLE_TRASHED = "trashed"
    const val LIFECYCLE_MISSING = "missing"
    const val LIFECYCLE_DELETED = "deleted"

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

    /** How many of a document's most recent journal events get_document_history shows. */
    const val MAX_HISTORY_EVENTS = 20

    /** How much of one history value get_document_history quotes. */
    const val MAX_HISTORY_VALUE_CHARS = 120

    private const val MILLIS_PER_DAY = 86_400_000L

    /**
     * Build the library tool set (see [LibraryToolSet]). Each tool is constructed once, so a tool the
     * model is advertised is the same instance the loop calls. [reviewMode] decides what "reviewed"
     * means, exactly as it does on the review screens.
     */
    fun toolSetFor(
        db: NpnDatabase,
        fields: List<FieldDef>,
        reviewMode: ReviewMode = ReviewMode.DEFAULT,
    ): LibraryToolSet {
        val entries =
            listOf(
                Entry(
                    SearchDocumentsTool(db.ftsDao(), db.documentDao(), db.tagDao(), reviewMode),
                    SearchDocumentsTool.Args.serializer(),
                ),
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
                Entry(GetDocumentHistoryTool(db), GetDocumentHistoryTool.Args.serializer()),
                Entry(LibraryStatsTool(db.documentDao(), reviewMode), LibraryStatsTool.Args.serializer()),
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
        includeAdded: Boolean = false,
    ): String =
        buildString {
            append("- [doc:")
                .append(doc.docId)
                .append("] file=\"")
                .append(escapeQuoted(doc.fileName))
                .append('"')
            doc.title?.takeIf { it.isNotBlank() }?.let { append(" title=\"").append(escapeQuoted(it)).append('"') }
            doc.docDate?.let { append(" date=").append(it) }
            if (includeAdded) append(" added=").append(isoDate(addedMillis(doc)))
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
            if (doc.deleted) append(" status=deleted")
            if (doc.trashed) append(" status=trashed")
            if (doc.missing) append(" status=missing")
            snippet?.takeIf { it.isNotBlank() }?.let {
                append(" snippet=\"").append(escapeQuoted(stripSnippetMarkers(it))).append('"')
            }
        }

    /**
     * When a document joined the library as the index knows it: [DocumentEntity.createdAt], the PDF's
     * own creation date (or its file's mtime when the PDF names none). This is the library's `added`
     * sort; it is not the date printed on the document ([DocumentEntity.docDate]).
     */
    internal fun addedMillis(doc: DocumentEntity): Long = doc.createdAt ?: doc.mtime

    /** The UTC calendar day of [millis], the timezone tool date filters compare in. */
    internal fun isoDate(millis: Long): String = instantAt(millis).date.toString()

    /** A UTC timestamp for history lines: `2026-11-20T13:45Z`. */
    internal fun isoDateTime(millis: Long): String {
        val local = instantAt(millis)
        val hour = local.hour.toString().padStart(2, '0')
        val minute = local.minute.toString().padStart(2, '0')
        return "${local.date}T$hour:$minute" + "Z"
    }

    private fun instantAt(millis: Long) = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC)

    /** A `YYYY-MM-DD` day as the model wrote it, or null when it is not one. */
    internal fun parseDay(value: String): LocalDate? =
        try {
            LocalDate.parse(value.trim())
        } catch (_: IllegalArgumentException) {
            null
        }

    /** [value] cut at [MAX_HISTORY_VALUE_CHARS], so one long note cannot fill the history output. */
    internal fun truncated(value: String): String =
        if (value.length <= MAX_HISTORY_VALUE_CHARS) value else value.take(MAX_HISTORY_VALUE_CHARS) + "…"

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

    /** How the lifecycle filter reads a row; each predicate is the one its screen queries with. */
    private enum class Lifecycle {
        LIVE,
        TRASHED,
        MISSING,
        DELETED,
        ;

        fun matches(doc: DocumentEntity): Boolean =
            when (this) {
                LIVE -> doc.isLive
                TRASHED -> doc.trashed && !doc.deleted
                MISSING -> doc.missing && !doc.deleted
                DELETED -> doc.deleted
            }
    }

    private fun lifecycleOf(value: String?): Lifecycle? =
        when (value?.trim()?.lowercase()) {
            null, "", ChatTools.LIFECYCLE_LIVE -> Lifecycle.LIVE
            ChatTools.LIFECYCLE_TRASHED -> Lifecycle.TRASHED
            ChatTools.LIFECYCLE_MISSING -> Lifecycle.MISSING
            ChatTools.LIFECYCLE_DELETED -> Lifecycle.DELETED
            else -> null
        }

    class SearchDocumentsTool(
        private val ftsDao: FtsDao,
        private val documentDao: DocumentDao,
        private val tagDao: TagDao,
        private val reviewMode: ReviewMode = ReviewMode.DEFAULT,
    ) : SimpleTool<SearchDocumentsTool.Args>(
            argsType = typeToken<Args>(),
            name = ChatTools.SEARCH_DOCUMENTS,
            description =
                "Search documents by full text and/or filters. Terms are AND-combined. Returns at " +
                    "most $MAX_SEARCH_RESULTS documents, ranked by relevance when query is given and " +
                    "newest added first otherwise, each with a docId and snippet. Leave query empty to " +
                    "list documents matching the filters. addedAtFrom/addedAtTo are an inclusive date " +
                    "range (YYYY-MM-DD) on when a document joined the library: its PDF's own creation " +
                    "date, else the file's modification time — not the date printed on it. ocrState, " +
                    "reviewed and lifecycle narrow the list; lifecycle=${ChatTools.LIFECYCLE_TRASHED}, " +
                    "${ChatTools.LIFECYCLE_MISSING} or ${ChatTools.LIFECYCLE_DELETED} discovers documents " +
                    "the library normally hides.",
        ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Search terms, e.g. \"invoice 2024 electricity\". Empty lists by filter.")
            @SerialName(ChatTools.ARG_QUERY)
            val query: String = "",
            @property:LLMDescription(
                "Inclusive lower bound (YYYY-MM-DD) on when the document joined the library: its PDF's " +
                    "creation date, else the file's modification time.",
            )
            @SerialName(ChatTools.ARG_ADDED_FROM)
            val addedAtFrom: String? = null,
            @property:LLMDescription("Inclusive upper bound (YYYY-MM-DD) on when the document joined the library.")
            @SerialName(ChatTools.ARG_ADDED_TO)
            val addedAtTo: String? = null,
            @property:LLMDescription("OCR state: none, pending, done, failed or adopted.")
            @SerialName(ChatTools.ARG_OCR_STATE)
            val ocrState: String? = null,
            @property:LLMDescription(
                "Review marker: true for reviewed, false for still needing review. In untagged-only " +
                    "mode a document counts as reviewed once it has any tag.",
            )
            @SerialName(ChatTools.ARG_REVIEWED)
            val reviewed: Boolean? = null,
            @property:LLMDescription(
                "Which documents to search: live (default), trashed, missing (its file is gone), or deleted.",
            )
            @SerialName(ChatTools.ARG_LIFECYCLE)
            val lifecycle: String? = null,
        )

        override suspend fun execute(args: Args): String {
            val lifecycle =
                lifecycleOf(args.lifecycle)
                    ?: return "Error: lifecycle must be one of ${ChatTools.LIFECYCLE_LIVE}, " +
                        "${ChatTools.LIFECYCLE_TRASHED}, ${ChatTools.LIFECYCLE_MISSING}, ${ChatTools.LIFECYCLE_DELETED}."
            val stateName = args.ocrState?.trim()?.takeIf { it.isNotEmpty() }
            val state = stateName?.let { wanted -> OcrState.entries.firstOrNull { it.column.equals(wanted, ignoreCase = true) } }
            if (stateName != null && state == null) {
                return "Error: ocrState must be one of " +
                    OcrState.entries.joinToString(", ") { it.column } + "."
            }
            val from =
                args.addedAtFrom?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
                    parseDay(value) ?: return "Error: addedAtFrom must be a date in YYYY-MM-DD form."
                }
            val to =
                args.addedAtTo?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
                    parseDay(value) ?: return "Error: addedAtTo must be a date in YYYY-MM-DD form."
                }
            if (from != null && to != null && from > to) {
                return "Error: addedAtFrom is after addedAtTo."
            }
            val fromMillis = from?.let { it.toEpochDays() * MILLIS_PER_DAY }
            val toExclusiveMillis = to?.let { (it.toEpochDays() + 1) * MILLIS_PER_DAY }

            val query = args.query.trim()
            val matching =
                candidates(query)
                    .filter { doc ->
                        lifecycle.matches(doc) &&
                            (fromMillis == null || addedMillis(doc) >= fromMillis) &&
                            (toExclusiveMillis == null || addedMillis(doc) < toExclusiveMillis) &&
                            (state == null || doc.ocrState == state.column)
                    }.let { filterReviewed(it, args.reviewed) }
            val shown = matching.take(MAX_SEARCH_RESULTS)
            val lines =
                if (query.isEmpty()) {
                    shown.map { docLine(it, includeAdded = true) }
                } else {
                    val snippets = ftsDao.snippetsFor(FtsQuery.sanitize(query), shown.map { it.docId })
                    shown.map { docLine(it, snippets[it.docId], includeAdded = true) }
                }
            if (lines.isEmpty()) {
                val filtered =
                    lifecycle != Lifecycle.LIVE || fromMillis != null || toExclusiveMillis != null ||
                        state != null || args.reviewed != null
                return when {
                    query.isEmpty() -> "No documents match those filters."
                    filtered -> "No documents match \"$query\" with those filters. Try fewer or different terms."
                    else -> "No documents match \"$query\". Try fewer or different terms."
                }
            }
            return buildString {
                append(lines.joinToString("\n"))
                val more = matching.size - shown.size
                if (more > 0) append("\n… and $more more matches (showing $MAX_SEARCH_RESULTS).")
            }
        }

        /**
         * The rows to consider, in the order the answer wants them: FTS rank for a query, newest
         * added first for a filter-only list. Hidden rows stay candidates so a lifecycle filter can
         * find them; the lifecycle predicate drops them from a default search.
         */
        private suspend fun candidates(query: String): List<DocumentEntity> {
            if (query.isEmpty()) {
                return documentDao
                    .getAllRows()
                    .sortedWith(
                        compareByDescending<DocumentEntity> { addedMillis(it) }
                            .thenBy { it.fileName.lowercase() }
                            .thenBy { it.docId },
                    )
            }
            val hits = ftsDao.search(FtsQuery.sanitize(query))
            val docs = documentDao.getByDocIds(hits).associateBy { it.docId }
            return hits.mapNotNull { docs[it] }
        }

        /**
         * The review filter as the review screens mean it: the stored marker where it is
         * authoritative, else "has any tag". Null leaves the list alone.
         */
        private suspend fun filterReviewed(
            docs: List<DocumentEntity>,
            wanted: Boolean?,
        ): List<DocumentEntity> {
            if (wanted == null || docs.isEmpty()) return docs
            if (reviewMode.reviewMarkerIsAuthoritative) return docs.filter { it.reviewed == wanted }
            val tagged = tagDao.tagsForDocs(docs.map { it.docId }).mapTo(HashSet()) { it.docId }
            return docs.filter { (it.docId in tagged) == wanted }
        }
    }

    class ListTagsTool(
        private val tagDao: TagDao,
    ) : SimpleTool<ListTagsTool.Args>(
            argsType = typeToken<Args>(),
            name = ChatTools.LIST_TAGS,
            description =
                "List every tag in the library with how many live documents carry it (trashed and " +
                    "deleted documents are not counted). Use it to find exact tag names before " +
                    "${ChatTools.DOCUMENTS_WITH_TAG}, or to answer how the library is organised.",
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
            description =
                "List live documents carrying one tag, matched by its exact name (get names from " +
                    "${ChatTools.LIST_TAGS}). Returns at most $MAX_LISTED_DOCS documents, each with its " +
                    "docId, or says that no document has the tag. For words inside documents use " +
                    "${ChatTools.SEARCH_DOCUMENTS} instead.",
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
                appendLine("added: ${isoDate(addedMillis(doc))}")
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

    /**
     * A document's recent journal history, bounded: the newest [MAX_HISTORY_EVENTS] events with long
     * values cut at [MAX_HISTORY_VALUE_CHARS]. Only events journaled against this docId are read, so
     * nothing another document carries can appear in the answer.
     */
    class GetDocumentHistoryTool(
        private val db: NpnDatabase,
    ) : SimpleTool<GetDocumentHistoryTool.Args>(
            argsType = typeToken<Args>(),
            name = ChatTools.GET_DOCUMENT_HISTORY,
            description =
                "Recent recorded history of one document by docId: the newest $MAX_HISTORY_EVENTS " +
                    "journal events (tag changes, field edits, review marks, OCR engine pin, trash/restore/" +
                    "delete), newest first. Long values are truncated. An import itself leaves no event, " +
                    "and events older than the displayed window are only counted.",
        ) {
        @Serializable
        data class Args(
            @property:LLMDescription("The docId (64-char hash) from a previous search result")
            @SerialName(ChatTools.ARG_DOC_ID)
            val docId: String,
        )

        override suspend fun execute(args: Args): String {
            val docId = args.docId.trim()
            val total = db.metadataProjectionDao().eventCountForDocument(docId)
            if (total == 0) {
                return "No recorded history for [doc:$docId]. Imports and OCR runs leave no journal event."
            }
            val recent = db.metadataProjectionDao().recentEventsForDocument(docId, MAX_HISTORY_EVENTS)
            val lines =
                recent.map { row ->
                    val event = runCatchingCancellable { EventDecoderRegistry.decodeStored(row.canonicalJson) }.getOrNull()
                    val entry = event?.let { HistoryEntry.of(it.op) } ?: HistoryEntry.Undecodable(row.operation)
                    "- [doc:$docId] ${isoDateTime(row.hlcPhysical)} device=${row.deviceId.take(8)}: ${describe(entry)}"
                }
            val shownNote =
                if (total > recent.size) {
                    "$total recorded events, newest first, showing the latest ${recent.size}:"
                } else {
                    "$total recorded events, newest first:"
                }
            return (listOf("History of [doc:$docId]: $shownNote") + lines).joinToString("\n")
        }

        /** One event as a short sentence; text that came from the journal is quoted and truncated. */
        private fun describe(entry: HistoryEntry): String =
            when (entry) {
                is HistoryEntry.TagAdded -> "tag added \"${truncated(entry.tag)}\""
                is HistoryEntry.TagRemoved -> "tag removed \"${truncated(entry.tag)}\""
                is HistoryEntry.Reviewed ->
                    when (entry.reviewed) {
                        true -> "marked reviewed"
                        false -> "marked needing review"
                        null -> "review mark cleared"
                    }
                is HistoryEntry.ReviewUnreadable -> "review mark set to an unreadable value \"${truncated(entry.value)}\""
                is HistoryEntry.OcrEnginePinned ->
                    entry.engine?.let { "OCR engine pinned to \"${truncated(it.label)}\"" } ?: "OCR engine pin cleared"
                is HistoryEntry.StoragePathSet ->
                    entry.path?.let { "storage path set to \"${truncated(it)}\"" } ?: "storage path cleared"
                is HistoryEntry.FieldSet ->
                    entry.value?.let { "field \"${truncated(entry.key)}\" set to \"${truncated(it)}\"" }
                        ?: "field \"${truncated(entry.key)}\" cleared"
                is HistoryEntry.Trashed -> "moved to trash from \"${truncated(entry.path)}\""
                HistoryEntry.Restored -> "restored"
                HistoryEntry.Deleted -> "permanently deleted"
                is HistoryEntry.Relinked -> "relinked to doc ${entry.newDocId}"
                is HistoryEntry.TagRenamed -> "tag \"${truncated(entry.from)}\" renamed to \"${truncated(entry.to)}\""
                is HistoryEntry.TagMerged -> "tag \"${truncated(entry.from)}\" merged into \"${truncated(entry.to)}\""
                is HistoryEntry.TagStyled -> "style changed for tag \"${truncated(entry.tag)}\""
                is HistoryEntry.SettingChanged -> "library setting \"${truncated(entry.key)}\" changed"
                is HistoryEntry.Unknown ->
                    "unrecorded event \"${truncated(entry.operation)}\"" +
                        (entry.payload?.let { " payload=\"${truncated(it)}\"" } ?: "")
                is HistoryEntry.Undecodable -> "unreadable event \"${truncated(entry.operation)}\""
            }
    }

    /**
     * Totals the assistant can quote: live documents, untagged and review-queue counts, OCR coverage,
     * added and document-date ranges, and what the lifecycle screens hold. Every number is a query;
     * nothing is derived or guessed, and a lifecycle total uses the same predicate as its screen.
     */
    class LibraryStatsTool(
        private val documentDao: DocumentDao,
        private val reviewMode: ReviewMode = ReviewMode.DEFAULT,
    ) : SimpleTool<LibraryStatsTool.Args>(
            argsType = typeToken<Args>(),
            name = ChatTools.LIBRARY_STATS,
            description =
                "Library totals: live documents, untagged, the review queue for this library's review " +
                    "mode, OCR coverage per state, the range of when documents were added, the range of " +
                    "document dates, and how many are trashed, missing or permanently deleted.",
        ) {
        @Serializable
        class Args

        override suspend fun execute(args: Args): String {
            val live = documentDao.countLive()
            if (live == 0 && !documentDao.any()) {
                return "The library has no documents yet."
            }
            val untagged = documentDao.countUntaggedOnce()
            val queue =
                when (reviewMode) {
                    ReviewMode.UNTAGGED_ONLY -> untagged
                    ReviewMode.INBOX_WORKFLOW -> documentDao.countUnreviewedOrUntaggedOnce()
                }
            val ocrRows = documentDao.ocrStateCounts()
            val byColumn = ocrRows.associate { it.ocrState to it.count }
            val knownColumns = OcrState.entries.map { it.column }.toSet()
            val added = documentDao.addedRange()
            val dates = documentDao.documentDateRange()
            return buildString {
                appendLine("Library statistics (live documents unless a lifecycle line says otherwise):")
                appendLine("- live documents: $live")
                appendLine("- untagged: $untagged")
                appendLine("- review queue (${reviewMode.wire} mode): $queue")
                appendLine("- in trash: ${documentDao.countTrashed()}")
                appendLine("- missing files: ${documentDao.countMissing()}")
                appendLine("- permanently deleted: ${documentDao.countDeleted()}")
                append("- OCR coverage: ")
                append(
                    OcrState.entries.joinToString(", ") { state ->
                        "${state.column}=${byColumn[state.column] ?: 0}"
                    },
                )
                for (row in ocrRows.filter { it.ocrState !in knownColumns }) {
                    append(", ${row.ocrState}=${row.count}")
                }
                appendLine()
                append("- added range: ")
                append(
                    if (added.oldest == null || added.newest == null) {
                        "(no live documents)"
                    } else {
                        "${isoDate(added.oldest)} to ${isoDate(added.newest)}"
                    },
                )
                appendLine()
                append("- document dates: ")
                append(
                    if (dates.oldest == null || dates.newest == null) {
                        "(none recorded)"
                    } else {
                        "${dates.oldest} to ${dates.newest}"
                    },
                )
                appendLine()
            }.trimEnd()
        }
    }
}
