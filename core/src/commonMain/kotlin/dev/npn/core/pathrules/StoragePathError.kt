package dev.npn.core.pathrules

/**
 * Why a storage-path template cannot be expanded. The user types the template, so every case is
 * an expected outcome that Settings names in the user's language, never an exception message.
 */
sealed interface StoragePathError {
    /** A `{` at [position] (0-based) that no `}` closes. */
    data class UnclosedPlaceholder(
        val position: Int,
    ) : StoragePathError

    /** A lone `}` at [position] (0-based); a literal brace is written `}}`. */
    data class UnexpectedClosingBrace(
        val position: Int,
    ) : StoragePathError

    /** A placeholder the template language does not know, such as `{author}`. */
    data class UnknownVariable(
        val name: String,
    ) : StoragePathError

    /** `{field}` or `{field:}`: the generic placeholder without a field key. */
    data object MissingFieldKey : StoragePathError

    /** A date pattern on [key], which the registry does not define as a date field. */
    data class NotADateField(
        val key: String,
    ) : StoragePathError

    /** A pattern on a placeholder that takes none: `{title:…}` or `{tags:…}`. */
    data class FormatNotAllowed(
        val name: String,
    ) : StoragePathError

    data object Blank : StoragePathError

    data object Absolute : StoragePathError

    data object Backslash : StoragePathError

    /** A `.` or `..` segment. */
    data object Traversal : StoragePathError

    /** A segment naming the app's own metadata directory, as written in the template. */
    data class ReservedDirectory(
        val name: String,
    ) : StoragePathError

    /** A segment that starts or ends with whitespace. */
    data object EdgeWhitespace : StoragePathError

    /** A segment holding characters a file name cannot carry. */
    data class InvalidSegment(
        val segment: String,
    ) : StoragePathError
}

/** What expanding a template gave: the library-relative path, or why there is none. */
sealed interface StoragePathExpansion {
    data class Path(
        val path: String,
    ) : StoragePathExpansion

    data class Invalid(
        val error: StoragePathError,
    ) : StoragePathExpansion
}

/** The expanded path, or null when the template is invalid for this context. */
val StoragePathExpansion.pathOrNull: String?
    get() = (this as? StoragePathExpansion.Path)?.path
