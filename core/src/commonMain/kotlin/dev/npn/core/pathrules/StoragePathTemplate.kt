package dev.npn.core.pathrules

import dev.npn.core.db.DocumentDao
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.uniquifyPath
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
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
     * Expand [template] with [context] and normalize the result. The returned path is
     * library-relative, uses '/' separators, and has no leading or trailing slashes.
     */
    fun expand(
        template: String,
        context: StoragePathContext,
    ): Result<String> = runCatching { expandInternal(template, context) }

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
                    require(end != -1) { "Unclosed placeholder starting at $index" }
                    val inner = template.substring(index + 1, end)
                    val colon = inner.indexOf(FORMAT_SEPARATOR)
                    val name = if (colon == -1) inner else inner.substring(0, colon)
                    val format = if (colon == -1) null else inner.substring(colon + 1)
                    sb.append(resolve(name.trim(), format?.trim(), context))
                    index = end + 1
                }

                char == PLACEHOLDER_END -> {
                    error("Unexpected '}' at $index")
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
            else -> error("Unknown template variable '$name'")
        }

    /** `{field:key}` or `{field:key:pattern}`: the part after the first colon is `key[:pattern]`. */
    private fun resolveGenericField(
        spec: String?,
        context: StoragePathContext,
    ): String {
        require(!spec.isNullOrBlank()) { "{field} needs a key: {field:key}" }
        val colon = spec.indexOf(FORMAT_SEPARATOR)
        val key = FieldKeys.normalize(if (colon == -1) spec else spec.substring(0, colon))
        val pattern = if (colon == -1) null else spec.substring(colon + 1).trim()
        requireNotNull(key) { "{field} needs a key: {field:key}" }
        return resolveField(key, pattern, context)
    }

    /** One field's value as a segment; a pattern is honoured for DATE fields and refused otherwise. */
    private fun resolveField(
        key: String,
        format: String?,
        context: StoragePathContext,
    ): String {
        val value = context.fields[key]
        if (format.isNullOrBlank()) return sanitizeSegment(value ?: "")
        require(context.fieldTypes[key] == FieldType.DATE) { "Formatting is only supported for date fields" }
        return sanitizeSegment(resolveDate(value, format))
    }

    private inline fun formatless(
        format: String?,
        name: String,
        value: () -> String,
    ): String {
        require(format.isNullOrBlank()) { "{$name} does not take a format" }
        return value()
    }

    private fun validateExpandedPath(
        template: String,
        expanded: String,
    ): String {
        require(template.isNotBlank()) { "Path must not be blank" }
        require(!template.startsWith('/') && !template.startsWith('\\')) { "Absolute paths are not allowed" }
        require(!Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(template)) { "Absolute paths are not allowed" }
        require('\\' !in expanded) { "Backslashes are not allowed" }

        val segments = expanded.split('/').filter { it.isNotBlank() }
        require(segments.isNotEmpty()) { "Path must not be blank" }
        segments.forEach { segment ->
            require(segment != "." && segment != "..") { "Path traversal is not allowed" }
            require(!segment.equals(NpnPaths.NPN_DIR, ignoreCase = true)) { "The .npn directory is reserved" }
            require(segment == segment.trim()) { "Path segments may not start or end with whitespace" }
            require(NpnPaths.sanitizeSegment(segment) == segment) {
                "Invalid filename segment '$segment'"
            }
        }
        return segments.joinToString("/")
    }

    private fun resolveTitle(context: StoragePathContext): String {
        val raw =
            context.title?.ifBlank { null }
                ?: context.originalFileName?.baseName()?.ifBlank { null }
                ?: DEFAULT_FALLBACK_TITLE
        return sanitizeSegment(raw)
    }

    private fun resolveDate(
        isoDate: String?,
        format: String?,
    ): String {
        if (isoDate.isNullOrBlank()) return ""
        val date = runCatching { LocalDate.parse(isoDate) }.getOrNull() ?: return isoDate
        return if (format.isNullOrBlank()) {
            isoDate
        } else {
            formatDate(date, format)
        }
    }

    private fun resolveTags(tags: List<String>): String =
        tags
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .sorted()
            .map { sanitizeSegment(it) }
            .filter { it.isNotEmpty() }
            .joinToString(", ")

    /**
     * Sanitize a single path segment. Delegates to [NpnPaths.sanitizeSegment], which replaces
     * directory separators and Windows-reserved characters with '_' and trims edge whitespace/dots.
     */
    fun sanitizeSegment(input: String): String = NpnPaths.sanitizeSegment(input)

    private fun String.baseName(): String {
        val dot = lastIndexOf('.')
        return if (dot > 0) substring(0, dot) else this
    }

    /** Format [date] using a tiny subset of Java date-pattern symbols. */
    @Suppress("CyclomaticComplexMethod")
    private fun formatDate(
        date: LocalDate,
        pattern: String,
    ): String {
        val out = StringBuilder()
        var index = 0
        while (index < pattern.length) {
            val char = pattern[index]
            val remaining = pattern.length - index
            when {
                char == 'y' && remaining >= 4 && pattern.startsWith("yyyy", index) -> {
                    out.append(date.year.toString().padStart(4, '0'))
                    index += 4
                }

                char == 'y' && remaining >= 2 && pattern.startsWith("yy", index) -> {
                    out.append((date.year % 100).toString().padStart(2, '0'))
                    index += 2
                }

                char == 'M' && remaining >= 4 && pattern.startsWith("MMMM", index) -> {
                    out.append(monthName(date.month))
                    index += 4
                }

                char == 'M' && remaining >= 3 && pattern.startsWith("MMM", index) -> {
                    out.append(monthName(date.month).take(3))
                    index += 3
                }

                char == 'M' && remaining >= 2 && pattern.startsWith("MM", index) -> {
                    out.append(
                        date.month.number
                            .toString()
                            .padStart(2, '0'),
                    )
                    index += 2
                }

                char == 'M' -> {
                    out.append(date.month.number)
                    index++
                }

                char == 'd' && remaining >= 2 && pattern.startsWith("dd", index) -> {
                    out.append(date.day.toString().padStart(2, '0'))
                    index += 2
                }

                char == 'd' -> {
                    out.append(date.day)
                    index++
                }

                else -> {
                    out.append(char)
                    index++
                }
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
        if (NpnPaths.isPdf(normalized.substringAfterLast('/'))) {
            normalized
        } else {
            "$normalized.pdf"
        }
    val exists: suspend (String) -> Boolean = { candidate ->
        // relPath has a unique index (DocumentEntity), so at most one row can own a candidate.
        val owner = documentDao.getByRelPath(candidate)
        if (owner?.docId == currentDocId) false else fs.exists(candidate) || owner != null
    }
    return uniquifyPath(
        path = withExtension,
        startIndex = 1,
        suffix = { "-$it" },
        exists = exists,
    )
}
