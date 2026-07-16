package dev.npn.core.index

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes read-modify-write access to the FTS `content` column across the two writers that
 * touch it: [Indexer.applyFold] (which carries existing extracted text forward while updating the
 * metadata-derived columns) and the OCR pipeline (which writes the freshly-extracted content).
 *
 * Both perform a read-then-replace on the same FTS row, so without a shared lock a concurrent
 * metadata fold and an OCR completion for the same doc can clobber each other: the fold can
 * overwrite freshly-OCR'd content with the pre-OCR snapshot it read, or vice-versa. A single
 * shared instance injected into both closes that window.
 */
class FtsWriteGate {
    private val mutex = Mutex()

    suspend fun <T> withGate(block: suspend () -> T): T = mutex.withLock { block() }
}
