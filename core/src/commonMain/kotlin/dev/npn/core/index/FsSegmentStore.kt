package dev.npn.core.index

import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.journal.SegmentRef
import dev.npn.core.journal.SegmentStore

/**
 * Filesystem-backed journal [SegmentStore]. Segments live at
 * `.npn/journal/<deviceId>/<fileName>` and are strictly write-once: the app only ever creates
 * new segment files, never appends or rewrites (`writeBytesOnce` fails if the file exists, which
 * is exactly what [SegmentStore.writeSegment] requires).
 *
 * The file-level accessors ([listJournalFiles], [readJournalFile], [writeJournalFileOnce],
 * [deleteJournalFile]) operate on raw (deviceId, fileName) pairs; the [SegmentStore] overrides
 * layer the segment-file filtering on top.
 */
class FsSegmentStore(private val fs: LibraryFileSystem) : SegmentStore {

    /** All journal segment files across all device directories, as (deviceId, fileName) pairs. */
    suspend fun listJournalFiles(): List<Pair<String, String>> {
        val prefix = "${NpnPaths.JOURNAL_DIR}/"
        return fs.listAllUnder(NpnPaths.JOURNAL_DIR)
            .asSequence()
            .filter { !it.isDirectory && it.relPath.startsWith(prefix) }
            .mapNotNull { meta ->
                when (val path = NpnPaths.journalPath(meta.relPath)) {
                    is NpnPaths.JournalPath.Legacy -> path.deviceId to path.fileName
                    else -> null
                }
            }
            .toList()
    }

    suspend fun readJournalFile(deviceId: String, fileName: String): String =
        fs.readBytes(NpnPaths.journalSegmentPath(deviceId, fileName)).decodeToString()

    suspend fun writeJournalFileOnce(deviceId: String, fileName: String, content: String) {
        fs.writeBytesOnce(NpnPaths.journalSegmentPath(deviceId, fileName), content.encodeToByteArray())
    }

    suspend fun deleteJournalFile(deviceId: String, fileName: String) {
        fs.delete(NpnPaths.journalSegmentPath(deviceId, fileName))
    }

    override suspend fun listSegments(): List<SegmentRef> =
        listJournalFiles()
            .filter { (_, fileName) -> SegmentRef.isSegmentFile(fileName) }
            .map { (deviceId, fileName) -> SegmentRef(deviceId, fileName) }

    override suspend fun readSegment(ref: SegmentRef): String =
        readJournalFile(ref.deviceId, ref.fileName)

    override suspend fun writeSegment(deviceId: String, fileName: String, content: String) {
        writeJournalFileOnce(deviceId, fileName, content)
    }
}
