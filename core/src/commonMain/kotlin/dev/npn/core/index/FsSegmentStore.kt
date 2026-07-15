package dev.npn.core.index

import dev.npn.core.fs.FsSegmentStoreFiles
import dev.npn.core.journal.SegmentRef
import dev.npn.core.journal.SegmentStore

/**
 * Adapts the file-level [FsSegmentStoreFiles] to the journal engine's [SegmentStore] interface.
 * Pure delegation: the file adapter already enforces the write-once invariant (its
 * `writeBytesOnce` fails if the file exists), which is what [SegmentStore.writeSegment]
 * requires.
 */
class FsSegmentStore(private val files: FsSegmentStoreFiles) : SegmentStore {

    override suspend fun listSegments(): List<SegmentRef> =
        files.listJournalFiles()
            .filter { (_, fileName) -> SegmentRef.isSegmentFile(fileName) }
            .map { (deviceId, fileName) -> SegmentRef(deviceId, fileName) }

    override suspend fun readSegment(ref: SegmentRef): String =
        files.readJournalFile(ref.deviceId, ref.fileName)

    override suspend fun writeSegment(deviceId: String, fileName: String, content: String) {
        files.writeJournalFileOnce(deviceId, fileName, content)
    }

    override suspend fun deleteSegment(ref: SegmentRef) {
        files.deleteJournalFile(ref.deviceId, ref.fileName)
    }
}
