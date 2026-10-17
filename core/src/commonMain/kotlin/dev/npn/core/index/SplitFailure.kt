package dev.npn.core.index

/** Why a split did not happen. The underlying cause, if any, is in the session log. */
enum class SplitFailure {
    /** The document is gone, trashed or missing, or the file at its path is no longer its own. */
    DOCUMENT_UNAVAILABLE,

    /** The page ranges are not at least two parts covering every page once, in order. */
    INVALID_PARTS,

    /** The PDF could not be read. */
    UNREADABLE_PDF,

    /** A part, its metadata or the trashing of the original failed; the parts were taken back. */
    NOT_COMPLETED,
}
