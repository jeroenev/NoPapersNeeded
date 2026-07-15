package dev.npn.core.index

/** A path holding a duplicate copy of an already-indexed document's content hash. */
data class DuplicateReport(val docId: String, val extraRelPath: String)

/**
 * A candidate pairing between a disappeared/superseded document and a new one, produced by the
 * indexer's heuristics. Not auto-applied: the UI presents these and the user confirms via
 * [RelinkService.applyRelink].
 *
 * [reason] is a short machine tag ("path-match", "name+size", "name", "size", "fuzzy-name").
 * [score] is a rough confidence (higher is stronger); ordering only, not a probability.
 */
data class RelinkCandidate(
    val oldDocId: String,
    val newDocId: String,
    val reason: String,
    val score: Int,
)

/**
 * A document whose journal state says trashed/deleted but whose file still exists at the
 * original path (resurrected by sync or the user). The indexer prefers the file (clears
 * missing) and reports it so the UI can offer to re-trash.
 */
data class Resurrected(val docId: String, val relPath: String, val wasDeleted: Boolean)

/** Result of a full rescan. All counts are for this scan only. */
data class ScanReport(
    val added: Int,
    val moved: Int,
    val changed: Int,
    val missingCount: Int,
    val duplicates: List<DuplicateReport>,
    val conflictFiles: List<String>,
    val resurrected: List<Resurrected>,
    val relinkCandidates: List<RelinkCandidate>,
    val hashedCount: Int,
    val durationMillis: Long,
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
