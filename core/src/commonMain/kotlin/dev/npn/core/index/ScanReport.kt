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
 * [dev.npn.core.app.MaintenanceFacade.applyRelink].
 *
 * [signals] are why the new file looks like the missing document's: [RelinkSignal.REPLACED_IN_PLACE]
 * alone, or some of the others, never [RelinkSignal.SAME_FOLDER] alone. [score] is a rough
 * confidence (higher is stronger); ordering only, not a probability.
 */
data class RelinkCandidate(
    val oldDocId: String,
    val newDocId: String,
    val signals: Set<RelinkSignal>,
    val score: Int,
)

/** One reason a new file may be a missing document's, for the user to weigh before a relink. */
enum class RelinkSignal {
    /** The file took the document's place: the same path, holding other bytes. */
    REPLACED_IN_PLACE,

    /** The same file name, case aside. */
    SAME_NAME,

    /** A file name close to the document's ([NameSimilarity]). */
    SIMILAR_NAME,

    /** The same size in bytes. */
    SAME_SIZE,

    /** The same folder: never evidence on its own, since a folder rewritten in place shares one. */
    SAME_FOLDER,
}

/**
 * A document whose journal state says trashed/deleted but whose own bytes are in the library at
 * [relPath] (put back by a sync tool or the user). A rescan prefers the file and reports it, so
 * the coordinator can record a restore; [hiddenAtMillis] is the wall time of the event that hid it.
 */
data class Resurrected(
    val docId: String,
    val relPath: String,
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
 * reconciling -> folding; once the scan ends there is no progress at all. During hashing,
 * [hashed]/[total] track file hashing progress.
 */
data class IndexProgress(
    val phase: Phase,
    val hashed: Int = 0,
    val total: Int = 0,
) {
    enum class Phase { LISTING, HASHING, RECONCILING, FOLDING }
}
