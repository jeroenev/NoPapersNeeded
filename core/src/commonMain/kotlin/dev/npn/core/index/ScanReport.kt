package dev.npn.core.index

/** A path holding a duplicate copy of an already-indexed document's content hash. */
data class DuplicateReport(
    val docId: String,
    val extraRelPath: String,
)

/** A journaled location that cannot yet be materialized; the current file is retained. */
data class PathConflict(
    val docId: String,
    val currentPath: String,
    val intendedPath: String,
)

/**
 * A candidate pairing between a disappeared/superseded document and a new one, produced by the
 * indexer's heuristics. Not auto-applied: the UI presents these and the user confirms via
 * [RelinkService.applyRelink].
 *
 * [reason] is a short machine tag: "path-match", or the evidence joined by `+` ("name" or
 * "fuzzy-name", "size", "dir"; "dir" never alone). [score] is a rough confidence (higher is
 * stronger); ordering only, not a probability.
 */
data class RelinkCandidate(
    val oldDocId: String,
    val newDocId: String,
    val reason: String,
    val score: Int,
)

/**
 * A document whose journal state says trashed/deleted but whose file is in the library at
 * [relPath] (put back by a sync tool or the user). A rescan prefers the file and reports it, so
 * the coordinator can record a restore; [hiddenAtMillis] is the wall time of the event that hid it.
 */
data class Resurrected(
    val docId: String,
    val relPath: String,
    val wasDeleted: Boolean,
    val hiddenAtMillis: Long,
)

/** Result of a full rescan. All counts are for this scan only. */
data class ScanReport(
    val added: Int,
    val moved: Int,
    val changed: Int,
    val missingCount: Int,
    val duplicates: List<DuplicateReport>,
    val conflictFiles: List<String>,
    val relinkCandidates: List<RelinkCandidate>,
    val hashedCount: Int,
    val durationMillis: Long,
    val pathConflicts: List<PathConflict> = emptyList(),
    /** PDFs left for a later scan because they still looked like a write in progress. */
    val deferred: List<String> = emptyList(),
    /** When set, a rescan after this many milliseconds can index some of [deferred]. */
    val retryAfterMillis: Long? = null,
)

/**
 * Lightweight progress surface for a rescan. [phase] moves through listing -> hashing ->
 * reconciling -> folding -> done. During hashing, [hashed]/[total] track file hashing progress.
 */
data class IndexProgress(
    val phase: Phase,
    val hashed: Int = 0,
    val total: Int = 0,
) {
    enum class Phase { LISTING, HASHING, RECONCILING, FOLDING, DONE }
}
