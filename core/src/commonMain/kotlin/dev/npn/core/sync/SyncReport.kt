package dev.npn.core.sync

import kotlinx.serialization.Serializable

/** The stages of one sync pass, in the order they run. */
enum class SyncPhase {
    LISTING,
    PULL_METADATA,
    RESOLVE_DOCUMENTS,
    APPLY_LOCAL,
    PUSH_DOCUMENTS,
    PUSH_METADATA,
    INDEXING,
}

/** Coarse progress of a running pass, for a progress bar. */
data class SyncProgress(
    val phase: SyncPhase,
    val done: Int = 0,
    val total: Int = 0,
)

/**
 * Something the pass could not converge on its own. A conflict is never silently resolved by
 * deleting or replacing anything: both sides keep their bytes and the user is told where. [kind]
 * picks the sentence the UI shows; [otherPath] is the second path some kinds name (where a copy
 * went instead).
 */
@Serializable
data class SyncConflict(
    val docId: String?,
    val remotePath: String,
    val kind: ConflictKind,
    val otherPath: String? = null,
)

/** What kept one item from converging this pass. */
enum class ConflictKind {
    /** The document's intended remote path holds another file; it stays where it is. */
    TARGET_OCCUPIED,

    /** The remote path held another file, so the document was uploaded as [SyncConflict.otherPath]. */
    UPLOADED_AS_COPY,

    /** The library path was taken, so the document was installed as [SyncConflict.otherPath]. */
    INSTALLED_AS_COPY,

    /** A remote file at this path could not be identified this pass; nothing is uploaded over it. */
    UNIDENTIFIED,

    /** The remote file changed between listing and acting; the next pass plans against it. */
    REMOTE_CHANGED,

    /** The library file no longer holds the document; it is left alone. */
    LOCAL_CHANGED,

    /**
     * An import brought back a document the journal has deleted, without a record of it: every other
     * device still has it deleted, so it stays on this device only.
     */
    KEPT_ON_THIS_DEVICE,
}

/** A per-item failure that did not abort the pass. */
@Serializable
data class SyncError(
    val path: String,
    val message: String,
)

/**
 * What one pass did. Counts are of transfers actually performed; a no-op pass has all zeros.
 * Persisted (as [SyncRunSummary]) so the settings screen can show the last outcome after a restart.
 */
@Serializable
data class SyncReport(
    val startedAt: Long,
    val durationMillis: Long,
    val downloadedDocuments: Int = 0,
    val uploadedDocuments: Int = 0,
    val downloadedObjects: Int = 0,
    val uploadedObjects: Int = 0,
    val movedRemote: Int = 0,
    val deletedRemote: Int = 0,
    val appliedLocally: Int = 0,
    val conflicts: List<SyncConflict> = emptyList(),
    val errors: List<SyncError> = emptyList(),
) {
    val transferred: Int
        get() = downloadedDocuments + uploadedDocuments + downloadedObjects + uploadedObjects

    val changedAnything: Boolean
        get() = transferred > 0 || movedRemote > 0 || deletedRemote > 0 || appliedLocally > 0
}

/**
 * The persisted outcome of the most recent pass: a report, or the [failure] that ended it early with
 * the engine's own [failureDetail]. Cleared when the library's remote changes.
 */
@Serializable
data class SyncRunSummary(
    val startedAt: Long,
    val finishedAt: Long,
    val report: SyncReport? = null,
    val failure: SyncFailure? = null,
    val failureDetail: String? = null,
) {
    val ok: Boolean get() = failure == null
}

/** Why a pass ended early. */
enum class SyncFailure {
    /** The remote could not be reached: no network, the service is down, a timeout. */
    REMOTE_UNREACHABLE,

    /** The provider no longer accepts the sign-in; the user has to connect again. */
    SIGN_IN_EXPIRED,

    /** The remote is out of space. */
    REMOTE_FULL,

    /** The remote folder holds files but no library; nothing was transferred. */
    NOT_A_LIBRARY,

    /** The remote folder holds a different library; nothing was transferred. */
    OTHER_LIBRARY,

    /** The journal could not be brought up to date, so nothing was planned from it. */
    JOURNAL_BLOCKED,

    /** Anything else; the detail says what. */
    UNKNOWN,
}

/** Live sync state for the UI: what is running now, and how the last pass ended. */
data class SyncStatus(
    val running: Boolean = false,
    val progress: SyncProgress? = null,
    val lastRun: SyncRunSummary? = null,
)

/** Why a requested pass did or did not run. */
sealed interface SyncOutcome {
    data class Completed(
        val report: SyncReport,
    ) : SyncOutcome

    /** The pass started but ended early: [reason], with the engine's own [detail] when it said one. */
    data class Failed(
        val reason: SyncFailure,
        val detail: String? = null,
    ) : SyncOutcome

    /** No remote is configured, or (for a scheduled run) automatic sync is off or not yet due. */
    data object NotConfigured : SyncOutcome

    /** The transfer engine is not usable on this device; see [TransportAvailability]. */
    data class Unavailable(
        val availability: TransportAvailability,
    ) : SyncOutcome

    /** Another pass is in flight; passes are single-flight per library. */
    data object AlreadyRunning : SyncOutcome
}

/**
 * A remote file, or a library file, changed between the step that planned around it and the step
 * that acted on it: an expected race with another writer, never a bug. The item is recorded as an
 * error and the next pass plans against what is there.
 */
class RemoteRaceException(
    message: String,
) : Exception(message)

/**
 * A pass refused before it changed anything, for a [reason] the user has to act on (a folder that
 * is no library, or another library's).
 */
class SyncRefusedException(
    val reason: SyncFailure,
) : Exception("Sync refused: $reason")
