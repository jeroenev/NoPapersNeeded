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
 * deleting or replacing anything: both sides keep their bytes and the user is told where.
 */
@Serializable
data class SyncConflict(
    val docId: String?,
    val remotePath: String,
    val message: String,
)

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

/** The persisted outcome of the most recent pass: a report, or the failure that ended it early. */
@Serializable
data class SyncRunSummary(
    val startedAt: Long,
    val finishedAt: Long,
    val report: SyncReport? = null,
    val failure: String? = null,
) {
    val ok: Boolean get() = failure == null
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

    /** The pass started but ended in a failure that no per-item retry covers (listing, transport). */
    data class Failed(
        val message: String,
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
