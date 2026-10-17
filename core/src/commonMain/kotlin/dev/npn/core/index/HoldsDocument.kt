package dev.npn.core.index

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.isLive
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem

/**
 * Whether the file [this] describes still has the [size] and [mtime] a document's row stored: the
 * evidence a rescan and an import (`ImportService`) take that the bytes there are still that
 * document's without hashing them, for a document that is not hidden. A hidden one gave its path
 * up, so a file there is hashed.
 */
internal fun FileMeta.hasStoredStat(
    size: Long,
    mtime: Long,
): Boolean = this.size == size && mtimeMillis == mtime

/**
 * Whether the file at [row]'s path holds its document's bytes, [size] of them, by the index's
 * standard of proof: a live row's file that still has the size and mtime the row stored holds them
 * ([hasStoredStat]), and any other file of that size is hashed. Moving or keeping a document's file
 * goes by this, so an unchanged file costs a stat and a file replaced since the last rescan is never
 * taken for the document.
 */
internal suspend fun LibraryFileSystem.holdsDocument(
    row: DocumentEntity,
    size: Long = row.size,
): Boolean {
    val meta = stat(row.relPath)?.takeIf { !it.isDirectory && it.size == size } ?: return false
    return (row.isLive && meta.hasStoredStat(row.size, row.mtime)) || sha256(row.relPath) == row.docId
}
