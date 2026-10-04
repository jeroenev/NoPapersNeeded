package dev.npn.core.index

import dev.npn.core.app.JournalCoordinator
import dev.npn.core.db.DocumentDao
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.DeviceDatabase
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.StatementLog
import dev.npn.core.db.fieldTypes
import dev.npn.core.db.deviceDatabase
import dev.npn.core.db.npnDatabase
import dev.npn.core.db.recordingDatabase
import dev.npn.core.fs.JvmLibraryFileSystem
import dev.npn.core.index.ScanReport
import dev.npn.core.journal.EpochClock
import dev.npn.core.journal.HlcClock
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
    val deviceDb: DeviceDatabase,
    val clock: MutableClock,
) {
    val fsSegmentStore = FsSegmentStore(fs)

    val indexer = Indexer(fs, db, clock)

    /** Real persisted accumulator: reads the same V1 journal segments the writers produce. */
    val accumulator = MetadataAccumulator(fs, db, installationId = "test-installation")

    val importService =
        ImportService(
            fs,
            db.documentDao(),
            deviceDb.ocrQueueDao(),
            clock,
            fieldTypes = { db.fieldDefDao().fieldTypes() },
        )

    val maintenance =
        MaintenanceService(fs, db.documentDao(), db.duplicateDao(), importService)

    /**
     * Reconcile metadata through the persisted accumulator, then rescan the file tree. Mirrors
     * what [JournalCoordinator.rescan] does in production, so tests keep calling a single method.
     */
    suspend fun rescan(): ScanReport {
        // A rescan always projects, so it always needs a fold — reconcile returns null when it
        // read nothing and had nothing handed in, which is a no-op only the metadata paths can use.
        val state = accumulator.reconcile().state ?: accumulator.loadState()
        val report = indexer.rescan(metadataState = state)
        accumulator.markReady(state.maxHlc)
        return report
    }

    /** Build a SegmentWriter for [deviceId] over this library, with its own local state. */
    internal fun writer(deviceId: String): SegmentWriter =
        SegmentWriter(
            store = fsSegmentStore,
            deviceId = deviceId,
            hlcClock = HlcClock(deviceId, clock),
            localState = DbLocalJournalState(db.keyValueDao()),
        )

    /** Build a [JournalCoordinator] over [writer] backed by this rig's accumulator + indexer. */
    internal fun coordinator(writer: SegmentWriter): JournalCoordinator =
        JournalCoordinator(writer, indexer, accumulator)

    /** Write a fake PDF (bytes need not be valid; only the .pdf suffix matters to the indexer). */
    fun writePdf(
        relPath: String,
        content: String,
    ) {
        val p = libRoot.resolve(relPath)
        Files.createDirectories(p.parent)
        Files.write(p, content.encodeToByteArray())
    }

    fun exists(relPath: String): Boolean = Files.exists(libRoot.resolve(relPath))

    fun moveFile(
        from: String,
        to: String,
    ) {
        val target = libRoot.resolve(to)
        Files.createDirectories(target.parent)
        Files.move(libRoot.resolve(from), target)
    }

    fun deleteFile(relPath: String) {
        Files.deleteIfExists(libRoot.resolve(relPath))
    }

    fun copyFile(
        from: String,
        to: String,
    ) {
        val target = libRoot.resolve(to)
        Files.createDirectories(target.parent)
        Files.copy(libRoot.resolve(from), target)
    }

    fun externalFile(
        name: String,
        content: String,
    ): dev.npn.core.fs.ExternalSource {
        val ext = Files.createTempDirectory("npn-ext").resolve(name)
        Files.write(ext, content.encodeToByteArray())
        return dev.npn.core.fs
            .ExternalSource(ext)
    }

    fun close() {
        db.close()
        deviceDb.close()
        libRoot.toFile().deleteRecursively()
        dbDir.deleteRecursively()
    }

    companion object {
        /** A fresh rig; with [statements], every SQL statement the index database runs is logged there. */
        fun create(statements: StatementLog? = null): IndexRig {
            val libRoot = Files.createTempDirectory("npn-index-lib")
            val dbDir = Files.createTempDirectory("npn-index-db").toFile()
            val fs = JvmLibraryFileSystem(libRoot)
            val dbFile = File(dbDir, "index.db")
            val db = if (statements == null) npnDatabase(dbFile) else recordingDatabase(dbFile, statements)
            return IndexRig(libRoot, dbDir, fs, db, deviceDatabase(File(dbDir, "device.db")), MutableClock(1_000L))
        }
    }
}

/**
 * Test-only reverse path lookup. Production code addresses docs by content hash (docId); the
 * indexer tests still need "the row currently at this relPath" to assert move/missing outcomes.
 * Resolves the docId via [DocumentDao.allPathsAndMeta] (deleted rows excluded) then loads the row.
 */
suspend fun DocumentDao.rowAtPath(relPath: String): DocumentEntity? =
    allPathsAndMeta().firstOrNull { it.relPath == relPath }?.let { getByDocId(it.docId) }

/**
 * Hand-cranked test clock. With [autoTick] off (default) it returns [now] verbatim and only
 * [advance] moves it — the explicit-advance style the indexer/reconcile tests rely on. With
 * [autoTick] on it increments before every read, giving the strictly-increasing HLCs the
 * app/session tests want without manual bookkeeping. Consolidates the former TestClock
 * (auto-increment) and MutableClock (explicit advance).
 */
class MutableClock(
    start: Long = 1_000L,
    private val autoTick: Boolean = false,
) : EpochClock {
    var now: Long = start

    override fun nowMillis(): Long {
        if (autoTick) now += 1
        return now
    }

    fun advance(by: Long = 1L) {
        now += by
    }
}
