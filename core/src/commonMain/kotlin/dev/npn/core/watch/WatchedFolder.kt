package dev.npn.core.watch

import dev.npn.core.fs.ExternalSource
import kotlinx.coroutines.flow.Flow

/**
 * A PDF file observed in a watched folder. [id] is a stable platform identifier (desktop:
 * absolute path string; android: document uri string) used to remember what was already handled;
 * [size] and [mtimeMillis] make that memory invalidate itself when the file changes.
 */
data class WatchedFolderFile(
    val id: String,
    val name: String,
    val size: Long,
    val mtimeMillis: Long,
    val source: ExternalSource,
)

/**
 * A folder outside the library that is polled/watched for PDFs to auto-import (e.g. the output
 * folder of a scanner app). Desktop implements this over java.nio, android over SAF.
 */
interface WatchedFolder {
    /** Non-recursive listing of the PDF files currently in the folder. Empty when unreachable. */
    suspend fun listPdfs(): List<WatchedFolderFile>

    /** Remove [file] from the watched folder (only used when delete-after-import is on). */
    suspend fun delete(file: WatchedFolderFile): Boolean

    /**
     * Coarse change notifications, best effort. Platforms without file events (android SAF)
     * return an empty flow; the importer's periodic poll covers them.
     */
    fun changes(): Flow<Unit>
}
