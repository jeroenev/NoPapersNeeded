package dev.npn.core.journal

import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.sha256Bytes

/** The paths of the files under the library's journal that [JournalPaths.classify] says are a [T]. */
internal suspend inline fun <reified T : JournalPath> LibraryFileSystem.journalPaths(): List<String> =
    listAllUnder(NpnPaths.JOURNAL_DIR).map { it.relPath }.filter { JournalPaths.classify(it) is T }

/** The library's published storage-v2 journal objects. */
internal suspend fun LibraryFileSystem.journalObjects(): List<FileMeta> =
    listAllUnder(NpnPaths.JOURNAL_DIR).filter { JournalPaths.classify(it.relPath) is JournalPath.ObjectV2 }

/** Each published journal object's text. */
internal suspend fun LibraryFileSystem.journalObjectTexts(): List<String> =
    journalObjects().map { readBytes(it.relPath).decodeToString() }

/** Every event the published journal objects hold. */
internal suspend fun LibraryFileSystem.journalObjectEvents(): List<JournalEvent> =
    journalObjectTexts().flatMap { it.lines() }.mapNotNull { JournalCodec.decodeLineOrNull(it) }

/**
 * Compact the journal files at [sources], v2 objects or snapshots, as builds before this one did:
 * publish one snapshot holding each of their events once, in HLC order, under a manifest that
 * supersedes their hashes, then delete them. This build writes no snapshot and deletes no journal
 * object, but the libraries those builds compacted hold what they left. Returns the snapshot's path.
 */
internal suspend fun LibraryFileSystem.compactAsAnEarlierBuild(sources: List<String>): String {
    require(sources.isNotEmpty()) { "A snapshot supersedes at least one source" }
    val hashes = ArrayList<String>()
    val events = ArrayList<JournalEvent>()
    for (path in sources) {
        val bytes = readBytes(path)
        val read =
            when (val kind = JournalPaths.classify(path)) {
                is JournalPath.ObjectV2 -> StorageV2Reader.read(path, bytes).also { hashes += kind.expectedHash }
                is JournalPath.SnapshotV2 -> SnapshotV2Reader.read(path, bytes).also { hashes += kind.expectedHash }
                else -> error("Not an object or a snapshot: $path")
            }
        events += (read as JournalSourceRead.Accepted).events.map { it.event }
    }
    val bytes = earlierBuildSnapshot(hashes.sorted(), events.distinctBy { it.dot }.sortedBy { it.hlc })
    val snapshot = JournalPaths.objectPath(sha256Bytes(bytes), snapshot = true)
    writeBytesOnce(snapshot, bytes)
    sources.forEach { delete(it) }
    return snapshot
}
