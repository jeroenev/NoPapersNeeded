package dev.npn.core.fs

/**
 * File-level adapter for journal segments (PLAN "Metadata journal"). Segments live at
 * `.npn/journal/<deviceId>/<fileName>` and are strictly write-once: the app only ever creates
 * new segment files, never appends or rewrites.
 *
 * This class deliberately holds no reference to the journal engine (a `SegmentStore` interface
 * defined elsewhere); it is glued to it later. It knows only files.
 */
class FsSegmentStoreFiles(private val fs: LibraryFileSystem) {

    /** All journal segment files across all device directories, as (deviceId, fileName) pairs. */
    suspend fun listJournalFiles(): List<Pair<String, String>> {
        val prefix = "${NpnPaths.JOURNAL_DIR}/"
        return fs.listAll()
            .asSequence()
            .filter { !it.isDirectory && it.relPath.startsWith(prefix) }
            .mapNotNull { meta ->
                // .npn/journal/<deviceId>/<fileName>
                val rest = meta.relPath.removePrefix(prefix)
                val slash = rest.indexOf('/')
                if (slash <= 0) return@mapNotNull null
                val deviceId = rest.substring(0, slash)
                val fileName = rest.substring(slash + 1)
                // Only direct children of the device dir are segments (no nested dirs).
                if (fileName.contains('/')) return@mapNotNull null
                deviceId to fileName
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
}
