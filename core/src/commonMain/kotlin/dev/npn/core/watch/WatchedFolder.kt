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
) {
    /**
     * Whether the file under this one's [id], now [size] bytes and last modified at [mtimeMillis], is
     * still the file listed: the same size and mtime, the proof a rescan takes. A provider that
     * reported no mtime (0) for the listing is judged by the size alone.
     */
    fun unchangedAt(
        size: Long,
        mtimeMillis: Long,
    ): Boolean = size == this.size && (this.mtimeMillis == 0L || mtimeMillis == this.mtimeMillis)
}

/**
 * A folder outside the library that is polled/watched for PDFs to auto-import (e.g. the output
 * folder of a scanner app). Desktop implements this over java.nio, android over SAF.
 */
interface WatchedFolder {
    /** Non-recursive listing of the PDF files currently in the folder. Empty when unreachable. */
    suspend fun listPdfs(): List<WatchedFolderFile>

    /**
     * Remove [file] from the watched folder (only used when delete-after-import is on), only while
     * it is still the file listed and imported ([WatchedFolderFile.unchangedAt]): a file written
     * under its name since was never imported, and is left for the next sweep. Returns whether it
     * deleted the file.
     */
    suspend fun delete(file: WatchedFolderFile): Boolean

    /**
     * Coarse change notifications, best effort. Platforms without file events (android SAF)
     * return an empty flow; the importer's periodic poll covers them.
     */
    fun changes(): Flow<Unit>
}
