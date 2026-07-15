package dev.npn.core.index

import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.npnDatabase
import dev.npn.core.fs.FsSegmentStoreFiles
import dev.npn.core.fs.JvmLibraryFileSystem
import dev.npn.core.journal.EpochClock
import dev.npn.core.journal.HlcClock
import dev.npn.core.journal.JournalIndex
import dev.npn.core.journal.SegmentWriter
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * A self-contained test rig: a temp library dir on the real JVM filesystem, a real Room database
 * in a separate temp file (the cache lives outside the library), and real journal pieces wired
 * over the same library. A mutable [MutableClock] gives deterministic times and monotonic HLCs.
 */
class IndexRig private constructor(
    val libRoot: Path,
    val dbDir: File,
    val fs: JvmLibraryFileSystem,
    val db: NpnDatabase,
    val clock: MutableClock,
) {
    val segmentStoreFiles = FsSegmentStoreFiles(fs)
    val fsSegmentStore = FsSegmentStore(segmentStoreFiles)
    val journalIndex = JournalIndex(fsSegmentStore)
    val foldedTracker = DbFoldedSegmentTracker(db.foldedSegmentDao())

    val indexer = Indexer(fs, db, journalIndex, foldedTracker, clock)

    val importService =
        ImportService(fs, db.documentDao(), db.ocrQueueDao(), clock)

    val maintenance =
        MaintenanceService(fs, db.documentDao(), db.duplicateDao(), importService)

    /** Build a SegmentWriter for [deviceId] over this library, with its own local state. */
    fun writer(deviceId: String): SegmentWriter =
        SegmentWriter(
            store = fsSegmentStore,
            deviceId = deviceId,
            hlcClock = HlcClock(deviceId, clock),
            localState = DbLocalJournalState(db.keyValueDao()),
        )

    /** Write a fake PDF (bytes need not be valid; only the .pdf suffix matters to the indexer). */
    fun writePdf(relPath: String, content: String) {
        val p = libRoot.resolve(relPath)
        Files.createDirectories(p.parent)
        Files.write(p, content.encodeToByteArray())
    }

    fun exists(relPath: String): Boolean = Files.exists(libRoot.resolve(relPath))

    fun moveFile(from: String, to: String) {
        val target = libRoot.resolve(to)
        Files.createDirectories(target.parent)
        Files.move(libRoot.resolve(from), target)
    }

    fun deleteFile(relPath: String) {
        Files.deleteIfExists(libRoot.resolve(relPath))
    }

    fun copyFile(from: String, to: String) {
        val target = libRoot.resolve(to)
        Files.createDirectories(target.parent)
        Files.copy(libRoot.resolve(from), target)
    }

    fun externalFile(name: String, content: String): dev.npn.core.fs.ExternalSource {
        val ext = Files.createTempDirectory("npn-ext").resolve(name)
        Files.write(ext, content.encodeToByteArray())
        return dev.npn.core.fs.ExternalSource(ext)
    }

    fun close() {
        db.close()
        libRoot.toFile().deleteRecursively()
        dbDir.deleteRecursively()
    }

    companion object {
        fun create(): IndexRig {
            val libRoot = Files.createTempDirectory("npn-index-lib")
            val dbDir = Files.createTempDirectory("npn-index-db").toFile()
            val fs = JvmLibraryFileSystem(libRoot)
            val db = npnDatabase(File(dbDir, "index.db"))
            return IndexRig(libRoot, dbDir, fs, db, MutableClock(1_000L))
        }
    }
}

/** A hand-cranked [EpochClock] so tests control time and keep HLCs strictly increasing. */
class MutableClock(start: Long) : EpochClock {
    var now: Long = start
    override fun nowMillis(): Long = now
    fun advance(by: Long = 1L) { now += by }
}
