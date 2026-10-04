package dev.npn.app.di

/** Why a library did not open, in the terms the user is told. */
enum class OpenFailureReason {
    /** The folder is not there: an unmounted drive or card, or it was moved or deleted. */
    FOLDER_MISSING,

    /** Android's all-files grant, which a library on device storage needs, is missing or revoked. */
    NO_STORAGE_ACCESS,

    /** A new library's folder could not be created where the user asked. */
    FOLDER_NOT_CREATED,

    /**
     * The folder's filesystem cannot create a file without risking replacing one another device
     * just wrote (no hard links and no exclusive rename), so the journal cannot be written there.
     */
    UNSUPPORTED_STORAGE,

    /** Anything else: the library or the app's data for it could not be read. */
    UNREADABLE,
}

/**
 * Thrown by a platform's library wiring when it can tell why a library cannot be opened, so the user
 * is told [reason] rather than the exception's English [message], which stays a technical detail.
 */
class LibraryUnavailableException(
    val reason: OpenFailureReason,
    message: String,
) : Exception(message)
