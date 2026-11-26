package dev.npn.core.pdf

import dev.npn.core.db.DocumentDao
import dev.npn.core.db.DocumentEntity
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.readDocument
import dev.npn.core.util.NpnLog
import dev.npn.core.util.Sensitive.Kind.DOCUMENT
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.sensitive
import dev.npn.core.util.warn
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * Fills in each live document's [DocumentEntity.createdAt]: the creation date its PDF names, or
 * its file's mtime when the PDF names none. The index can tell nothing of a file's age (a scan
 * finds a whole library at once, and copies and sync rewrite filesystem times), but the PDF carries
 * its own, which no copy changes. Rows wait at null meanwhile, and sort by their mtime.
 *
 * A file it cannot read, or that no longer holds the document's bytes, keeps its row at null: the
 * next pass, after a rescan has caught up with the file, tries it again. One that holds other bytes
 * asks for that rescan through [onStaleFile], as the OCR drain does.
 */
internal class CreationDates(
    private val engine: PdfEngine,
    private val fs: LibraryFileSystem,
    private val documentDao: DocumentDao,
    private val renderGate: RenderGate,
    private val log: NpnLog,
    private val onStaleFile: () -> Unit = {},
) {
    /**
     * Fill the missing dates whenever rows are waiting for one, for as long as the caller's scope
     * lives. Follows the count, not every write to `documents`: a pass changes the count only by
     * filling a row, so the rows it had to leave are tried again once after it, not on every write.
     */
    suspend fun follow() {
        documentDao
            .observeWithoutCreatedAt()
            .distinctUntilChanged()
            .filter { it > 0 }
            .conflate()
            .collect { fillMissing() }
    }

    /** One pass over every live row whose date is not read yet. */
    suspend fun fillMissing() {
        for (row in documentDao.liveWithoutCreatedAt()) {
            read(row)?.let { documentDao.fillCreatedAt(row.docId, it) }
        }
    }

    /** [row]'s creation date, or null when its file could not be read as the document's. */
    private suspend fun read(row: DocumentEntity): Long? {
        val read =
            runCatchingCancellable { fs.readDocument(row.relPath, row.docId) }
                .onFailure { log.warn("Reading ${sensitive(row.relPath, DOCUMENT)} for its creation date failed", it) }
        if (read.isFailure) return null
        val bytes = read.getOrNull()
        if (bytes == null) {
            onStaleFile()
            return null
        }
        // A PDF that does not open has no date to give; its bytes never change, so mtime it is.
        val named =
            runCatchingCancellable { renderGate.render { engine.open(bytes).use { it.creationDate } } }.getOrNull()
        return named ?: row.mtime
    }
}
