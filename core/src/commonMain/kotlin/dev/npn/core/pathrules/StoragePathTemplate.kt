package dev.npn.core.pathrules

import dev.npn.core.db.DocumentDao
import dev.npn.core.db.DocumentEntity
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.uniquifyPath
import dev.npn.core.model.FieldKeys
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.number

/**
 * Parser and evaluator for user-defined storage path templates.
 *
 * Supported placeholders:
 * - `{field:key}` — any registry field by key, sanitized as a segment; a missing value produces
 *   an empty segment that is collapsed when the path is normalized.
 * - `{field:key:pattern}` — a DATE field formatted with a date pattern (`yyyy`, `yyyy-MM`, ...).
 *   Formatting any other type is an error.
 * - `{title}` — alias for `{field:title}` that falls back to the original file name, then to
 *   "document", so a template never yields an empty file name.
 * - `{docDate}` / `{doc_date}` (+ `:pattern`), `{docType}`, `{correspondent}` — aliases for the
 *   corresponding `{field:...}`.
 * - `{tags}` — sorted, sanitized tags joined by ", ".
 *
 * Literal braces can be escaped as `{{` and `}}`.
 */
object StoragePathTemplate {
    private const val PLACEHOLDER_START = '{'
    private const val PLACEHOLDER_END = '}'
    private const val FORMAT_SEPARATOR = ':'
    private const val ESCAPED_BRACE_LENGTH = 2

    private const val DEFAULT_FALLBACK_TITLE = "document"

    /**
     * Expand [template] with [context] and normalize the result. The path is library-relative,
     * uses '/' separators, and has no leading or trailing slashes. A template that cannot expand
     * says why with a [StoragePathError]; anything else that throws is a bug and propagates.
     */
    fun expand(
        template: String,
        context: StoragePathContext,
    ): StoragePathExpansion =
        try {
            StoragePathExpansion.Path(expandInternal(template, context))
        } catch (invalid: InvalidTemplate) {
            StoragePathExpansion.Invalid(invalid.error)
        }

    /** Unwinds the parser to [expand] with the reason; never escapes this object. */
    private class InvalidTemplate(
        val error: StoragePathError,
    ) : Exception()

    private fun fail(error: StoragePathError): Nothing = throw InvalidTemplate(error)

    private inline fun ensure(
        condition: Boolean,
        error: () -> StoragePathError,
    ) {
        if (!condition) fail(error())
    }

    private fun expandInternal(
        template: String,
        context: StoragePathContext,
    ): String {
        val sb = StringBuilder()
        var index = 0
        while (index < template.length) {
            val char = template[index]
            when {
                char == PLACEHOLDER_START && isEscapedBrace(template, index) -> {
                    sb.append(PLACEHOLDER_START)
                    index += ESCAPED_BRACE_LENGTH
                }

                char == PLACEHOLDER_END && isEscapedBrace(template, index) -> {
                    sb.append(PLACEHOLDER_END)
                    index += ESCAPED_BRACE_LENGTH
                }

                char == PLACEHOLDER_START -> {
                    val end = template.indexOf(PLACEHOLDER_END, index)
                    ensure(end != -1) { StoragePathError.UnclosedPlaceholder(index) }
                    val inner = template.substring(index + 1, end)
                    val colon = inner.indexOf(FORMAT_SEPARATOR)
                    val name = if (colon == -1) inner else inner.substring(0, colon)
                    val format = if (colon == -1) null else inner.substring(colon + 1)
                    sb.append(resolve(name.trim(), format?.trim(), context))
                    index = end + 1
                }

                char == PLACEHOLDER_END -> {
                    fail(StoragePathError.UnexpectedClosingBrace(index))
                }

                else -> {
                    sb.append(char)
                    index++
                }
            }
        }
        return validateExpandedPath(template, sb.toString())
    }

    private fun isEscapedBrace(
        template: String,
        index: Int,
    ): Boolean = index + 1 < template.length && template[index + 1] == template[index]

    private fun resolve(
        name: String,
        format: String?,
        context: StoragePathContext,
    ): String =
        when (name) {
            "title" -> formatless(format, name) { resolveTitle(context) }
            "docDate", "doc_date" -> resolveField(FieldKeys.DOC_DATE, format, context)
            "docType" -> resolveField(FieldKeys.DOC_TYPE, format, context)
            "correspondent" -> resolveField(FieldKeys.CORRESPONDENT, format, context)
            "tags" -> formatless(format, name) { resolveTags(context.tags) }
            "field" -> resolveGenericField(format, context)
            else -> fail(StoragePathError.UnknownVariable(name))
        }

    /** `{field:key}` or `{field:key:pattern}`: the part after the first colon is `key[:pattern]`. */
    private fun resolveGenericField(
        spec: String?,
        context: StoragePathContext,
    ): String {
        if (spec.isNullOrBlank()) fail(StoragePathError.MissingFieldKey)
        val colon = spec.indexOf(FORMAT_SEPARATOR)
        val key =
            FieldKeys.normalize(if (colon == -1) spec else spec.substring(0, colon))
                ?: fail(StoragePathError.MissingFieldKey)
        val pattern = if (colon == -1) null else spec.substring(colon + 1).trim()
        return resolveField(key, pattern, context)
    }

    /** One field's value as a segment; a pattern is honoured for DATE fields and refused otherwise. */
    private fun resolveField(
        key: String,
        format: String?,
        context: StoragePathContext,
    ): String {
        val value = context.fields[key]
        if (format.isNullOrBlank()) return NpnPaths.sanitizeSegment(value.orEmpty())
        ensure(context.fieldTypes[key]?.isDate == true) { StoragePathError.NotADateField(key) }
        return NpnPaths.sanitizeSegment(resolveDate(value, format))
    }

    private inline fun formatless(
        format: String?,
        name: String,
        value: () -> String,
    ): String {
        ensure(format.isNullOrBlank()) { StoragePathError.FormatNotAllowed(name) }
        return value()
    }

    private fun validateExpandedPath(
        template: String,
        expanded: String,
    ): String {
        ensure(template.isNotBlank()) { StoragePathError.Blank }
        ensure(!template.startsWith('/') && !template.startsWith('\\')) { StoragePathError.Absolute }
        // A NUL is left to the segment check below, which reports it as an invalid segment.
        when (NpnPaths.unsafeReason(expanded)) {
            NpnPaths.UnsafePath.DRIVE_PREFIX -> fail(StoragePathError.Absolute)
            NpnPaths.UnsafePath.BACKSLASH -> fail(StoragePathError.Backslash)
            NpnPaths.UnsafePath.TRAVERSAL -> fail(StoragePathError.Traversal)
            NpnPaths.UnsafePath.NUL, null -> Unit
        }

        val segments = expanded.split('/').filter { it.isNotBlank() }
        ensure(segments.isNotEmpty()) { StoragePathError.Blank }
        segments.forEach { segment ->
            ensure(!segment.equals(NpnPaths.NPN_DIR, ignoreCase = true)) { StoragePathError.ReservedDirectory(segment) }
            ensure(segment == segment.trim()) { StoragePathError.EdgeWhitespace }
            ensure(NpnPaths.sanitizeSegment(segment) == segment) { StoragePathError.InvalidSegment(segment) }
        }
        return segments.joinToString("/")
    }

    private fun resolveTitle(context: StoragePathContext): String {
        val raw =
            context.title?.ifBlank { null }
                ?: context.originalFileName?.let(NpnPaths::stripPdfExtension)?.ifBlank { null }
                ?: DEFAULT_FALLBACK_TITLE
        return NpnPaths.sanitizeSegment(raw)
    }

    private fun resolveDate(
        isoDate: String?,
        format: String,
    ): String {
        if (isoDate.isNullOrBlank()) return ""
        val date = runCatching { LocalDate.parse(isoDate) }.getOrNull() ?: return isoDate
        return formatDate(date, format)
    }

    private fun resolveTags(tags: List<String>): String =
        tags
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .sorted()
            .map { NpnPaths.sanitizeSegment(it) }
            .filter { it.isNotEmpty() }
            .joinToString(", ")

    /**
     * The date-pattern symbols [formatDate] knows, a tiny subset of Java's, longest first so a
     * longer run of one letter wins over its prefix. A lone `y` is no symbol and stays literal.
     */
    private val DATE_TOKENS: List<Pair<String, (LocalDate) -> String>> =
        listOf(
            "yyyy" to { it.year.toString().padStart(4, '0') },
            "yy" to { (it.year % 100).toString().padStart(2, '0') },
            "MMMM" to { monthName(it.month) },
            "MMM" to { monthName(it.month).take(3) },
            "MM" to {
                it.month.number
                    .toString()
                    .padStart(2, '0')
            },
            "M" to { it.month.number.toString() },
            "dd" to { it.day.toString().padStart(2, '0') },
            "d" to { it.day.toString() },
        )

    /** Format [date] with [pattern]: each [DATE_TOKENS] symbol is replaced, anything else is literal. */
    private fun formatDate(
        date: LocalDate,
        pattern: String,
    ): String {
        val out = StringBuilder()
        var index = 0
        while (index < pattern.length) {
            val token = DATE_TOKENS.firstOrNull { (symbol, _) -> pattern.startsWith(symbol, index) }
            if (token == null) {
                out.append(pattern[index])
                index++
            } else {
                out.append(token.second(date))
                index += token.first.length
            }
        }
        return out.toString()
    }

    private fun monthName(month: Month): String = month.name.lowercase().replaceFirstChar { it.uppercase() }
}

/**
 * Build a unique library-relative path starting from [target].
 *
 * [currentDocId] is the document that is about to occupy the path; paths owned by other
 * documents (including missing files whose row still exists) are treated as occupied.
 */
suspend fun resolveUniqueTarget(
    fs: LibraryFileSystem,
    documentDao: DocumentDao,
    currentDocId: String?,
    target: String,
): String {
    val normalized = NpnPaths.normalize(target)
    val withExtension =
        if (NpnPaths.isPdf(NpnPaths.fileName(normalized))) {
            normalized
        } else {
            "$normalized.pdf"
        }
    return uniquifyPath(
        path = withExtension,
        startIndex = 1,
        suffix = { "-$it" },
        exists = { pathTaken(fs, documentDao, currentDocId, it) },
    )
}

/**
 * Whether [candidate] is taken for [currentDocId], the document about to occupy it (null for a file
 * that is no document yet): a file is there, or another document's row names it. [holds] says which
 * rows count; by default every one does, a missing file's row included.
 */
internal suspend fun pathTaken(
    fs: LibraryFileSystem,
    documentDao: DocumentDao,
    currentDocId: String?,
    candidate: String,
    holds: (DocumentEntity) -> Boolean = { true },
): Boolean {
    // relPath has a unique index (DocumentEntity), so at most one row can own a candidate.
    val owner = documentDao.getByRelPath(candidate)
    if (owner != null && owner.docId == currentDocId) return false
    return fs.exists(candidate) || (owner != null && holds(owner))
}
