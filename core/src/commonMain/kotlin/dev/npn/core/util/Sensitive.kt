package dev.npn.core.util

/**
 * User data in a log message: a document's name, title or path, a tag, a correspondent, a folder,
 * a location on the device. It renders as its [Kind]'s placeholder, never as the value, so
 * `log.warn("Importing ${sensitive(name, DOCUMENT)} failed")` writes "Importing <document>
 * failed" to every log (the file, stderr, logcat) and so to the diagnostics bundle.
 *
 * Mark at the call site whatever the user typed, named or chose; ids and content hashes are not
 * user data. [dev.npn.core.diagnostics.Diagnostics.sanitize] stays the backstop for text nobody
 * marked, such as an exception's message.
 */
class Sensitive internal constructor(
    private val value: Any?,
    private val kind: Kind,
) {
    enum class Kind(
        val placeholder: String,
    ) {
        /** A document's file name or library-relative path. */
        DOCUMENT("<document>"),

        /** A folder: in the library, watched, or the library's own location. */
        FOLDER("<folder>"),

        /** Any other path on the device. */
        PATH("<path>"),
        TAG("<tag>"),

        /** Anything else the user named or typed: a title, a correspondent, a field's value. */
        NAME("<name>"),
    }

    override fun toString(): String = kind.placeholder

    // Two markers of the same value are the same marker; the value is never rendered.
    override fun equals(other: Any?): Boolean = other is Sensitive && other.kind == kind && other.value == value

    override fun hashCode(): Int = 31 * kind.hashCode() + value.hashCode()
}

/** Mark [value] as user data in a log message; see [Sensitive]. */
fun sensitive(
    value: Any?,
    kind: Sensitive.Kind = Sensitive.Kind.NAME,
): Sensitive = Sensitive(value, kind)
