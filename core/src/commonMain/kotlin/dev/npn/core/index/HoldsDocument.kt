package dev.npn.core.index

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.isLive
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.hasStoredStat
import dev.npn.core.fs.holds

/**
 * Whether the file at [row]'s path holds its document's bytes, [size] of them, by the index's
 * standard of proof: a live row's file that still has the size and mtime the row stored holds them
 * ([hasStoredStat]), and any other file of that size is hashed ([holds]: one that cannot be read
 * does not). Moving or keeping a document's file goes by this, so an unchanged file costs a stat and
 * a file replaced since the last rescan is never taken for the document.
 */
internal suspend fun LibraryFileSystem.holdsDocument(
    row: DocumentEntity,
    size: Long = row.size,
): Boolean = heldDocumentStat(row, size) != null

/**
 * The stat by which [holdsDocument] proves the file at [row]'s path its document's, read before any
 * hash; null when it does not hold the document's bytes.
 */
internal suspend fun LibraryFileSystem.heldDocumentStat(
    row: DocumentEntity,
    size: Long = row.size,
): FileMeta? {
    val meta = stat(row.relPath)?.takeIf { !it.isDirectory && it.size == size } ?: return null
    return meta.takeIf { (row.isLive && it.hasStoredStat(row.size, row.mtime)) || holds(row.relPath, row.docId) }
}
