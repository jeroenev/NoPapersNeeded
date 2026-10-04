package dev.npn.core.index

import dev.npn.core.db.DocumentEntity
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.NpnPaths

/**
 * Pairs the documents a rescan found missing with the files it found new, as relink candidates the
 * user confirms. Every pair is scored, but scoring is a bounded amount of work, and each missing
 * document keeps only its [MAX_CANDIDATES_PER_DOC] best: a batch tool rewriting a folder makes
 * every missing document resemble every new one.
 */
internal object RelinkPairing {
    const val MAX_CANDIDATES_PER_DOC = 3

    /** The reason of a file replaced in place: same path, other bytes. */
    const val PATH_MATCH = "path-match"
    const val SCORE_PATH_MATCH = 100
    private const val SCORE_NAME = 50
    private const val SCORE_SIZE = 40
    private const val SCORE_SIMILAR_NAME = 15
    private const val SCORE_DIR = 10

    /** Highest score first; between equals, the lower id, so a rescan proposes the same ones. */
    private val ranking = compareByDescending<RelinkCandidate> { it.score }.thenBy { it.newDocId }

    /** One side of a pair, reduced once to what scoring compares. */
    private class File(
        val docId: String,
        val name: String,
        val size: Long,
        val relPath: String,
    ) {
        val stem = NameSimilarity.stem(name)
        val dir = NpnPaths.parentDir(relPath)
    }

    /** Candidates for [missingDocs] among [newDocs], leaving out the pairs [existing] proposes already. */
    fun pair(
        missingDocs: List<DocumentEntity>,
        newDocs: Map<String, FileMeta>,
        existing: List<RelinkCandidate>,
    ): List<RelinkCandidate> {
        if (missingDocs.isEmpty() || newDocs.isEmpty()) return emptyList()
        val proposed = existing.mapTo(HashSet()) { it.oldDocId to it.newDocId }
        val found = newDocs.map { (docId, meta) -> File(docId, meta.name, meta.size, meta.relPath) }
        val out = ArrayList<RelinkCandidate>()
        for (doc in missingDocs) {
            val gone = File(doc.docId, doc.fileName, doc.size, doc.relPath)
            val best = ArrayList<RelinkCandidate>(MAX_CANDIDATES_PER_DOC + 1)
            for (candidate in found) {
                val scored = score(gone, candidate) ?: continue
                if (proposed.isEmpty() || (scored.oldDocId to scored.newDocId) !in proposed) keepBest(best, scored)
            }
            out += best
        }
        return out
    }

    /** Insert [candidate] into [best], which stays ranked and no longer than the cap. */
    private fun keepBest(
        best: ArrayList<RelinkCandidate>,
        candidate: RelinkCandidate,
    ) {
        val at = best.indexOfFirst { ranking.compare(candidate, it) < 0 }.let { if (it < 0) best.size else it }
        if (at >= MAX_CANDIDATES_PER_DOC) return
        best.add(at, candidate)
        if (best.size > MAX_CANDIDATES_PER_DOC) best.removeAt(best.lastIndex)
    }

    /**
     * The pair's candidate, or null without evidence. The name (equal, or else similar) and the
     * size are evidence; sharing a directory only adds to it, because a folder rewritten in place
     * makes every pair share one.
     */
    private fun score(
        gone: File,
        found: File,
    ): RelinkCandidate? {
        val sameName = gone.name.equals(found.name, ignoreCase = true)
        val similarName = !sameName && NameSimilarity.similar(gone.stem, found.stem)
        val sameSize = gone.size == found.size
        if (!sameName && !similarName && !sameSize) return null
        val sameDir = gone.dir == found.dir
        val signals =
            listOf(
                Signal("name", SCORE_NAME, sameName),
                Signal("fuzzy-name", SCORE_SIMILAR_NAME, similarName),
                Signal("size", SCORE_SIZE, sameSize),
                Signal("dir", SCORE_DIR, sameDir),
            ).filter { it.present }
        return RelinkCandidate(
            oldDocId = gone.docId,
            newDocId = found.docId,
            reason = signals.joinToString("+") { it.reason },
            score = signals.sumOf { it.score },
        )
    }

    private class Signal(
        val reason: String,
        val score: Int,
        val present: Boolean,
    )
}
