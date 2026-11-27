package dev.npn.core.index

import dev.npn.core.fs.DocumentFacts
import dev.npn.core.journal.FoldedState

/**
 * What [this] fold says of a document, for the [dev.npn.core.fs.DeleteGate], by a caller that holds
 * the fold under the writer lock.
 */
internal fun FoldedState.facts(): DocumentFacts {
    val state = this
    return object : DocumentFacts {
        override suspend fun isDeleted(docId: String): Boolean = state.isDeleted(docId)

        override suspend fun isTrashed(docId: String): Boolean = state.isTrashed(docId)

        override suspend fun successorOf(docId: String): String = state.resolveDocId(docId)
    }
}
