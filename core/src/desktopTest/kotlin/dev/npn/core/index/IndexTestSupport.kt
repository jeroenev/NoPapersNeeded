package dev.npn.core.index

import dev.npn.core.app.JournalCoordinator
import dev.npn.core.db.DeviceDatabase
import dev.npn.core.db.DocumentDao
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.StatementLog
import dev.npn.core.db.TempIndexDb
import dev.npn.core.db.fieldTypes
import dev.npn.core.db.npnDatabase
import dev.npn.core.db.recordingDatabase
import dev.npn.core.fs.JvmLibraryFileSystem
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.PathExternalIo
import dev.npn.core.index.ScanReport
import dev.npn.core.journal.ContentAddressedJournalWriter
import dev.npn.core.journal.EpochClock
import dev.npn.core.journal.HlcWatermarkAllocator
import dev.npn.core.journal.JournalCodec
import dev.npn.core.journal.JournalEvent
import dev.npn.core.journal.JournalEventVersions
import dev.npn.core.journal.PendingOp
import java.nio.file.Files
import java.nio.file.Path

/**
 * A self-contained test rig: a temp library dir on the real JVM filesystem, a real Room database
 * in a separate temp file (the cache lives outside the library), and real journal pieces wired
 * over the same library. A mutable [MutableClock] gives deterministic times and monotonic HLCs.
 */
class IndexRig private constructor(
    val libRoot: Path,
    private val dbs: TempIndexDb,
    val fs: JvmLibraryFileSystem,
    val clock: MutableClock,
) {
    val db: NpnDatabase = dbs.db
    val deviceDb: DeviceDatabase = dbs.deviceDb

    val indexer = Indexer(fs, db, clock)

    /** Real persisted accumulator: reads the same journal objects the writers publish. */
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
        MaintenanceService(fs, db.duplicateDao(), importService)

    /**
     * Reconcile metadata through the persisted accumulator, then rescan the file tree. Mirrors
     * what [JournalCoordinator.rescan] does in production, so tests keep calling a single method.
     */
    suspend fun rescan(onResurrected: suspend (List<Resurrected>) -> Unit = {}): ScanReport {
        // A rescan always projects, so it always needs a fold — reconcile returns null when it
        // read nothing and had nothing handed in, which is a no-op only the metadata paths can use.
        val state = accumulator.reconcile().state ?: accumulator.loadState()
        val report = indexer.rescan(metadataState = state, onResurrected = onResurrected)
        accumulator.markReady(state.maxHlc)
        return report
    }

    /**
     * A journal writer over this library, publishing storage-v2 objects as any replica does;
     * [installation] only names its staging files. Writes through it bypass the rig's coordinator,
     * the way another device's writes arrive.
     */
    internal fun writer(installation: String = "test-installation"): ContentAddressedJournalWriter =
        ContentAddressedJournalWriter(fs, installation, HlcWatermarkAllocator(clock))

    /** Build a [JournalCoordinator] over [writer] backed by this rig's accumulator + indexer. */
    internal fun coordinator(writer: ContentAddressedJournalWriter = writer()): JournalCoordinator =
        JournalCoordinator(writer, indexer, accumulator)

    /** v1 segments for [deviceId] in this library, as builds before storage-v2 wrote them. */
    fun legacySegments(deviceId: String): LegacySegments = LegacySegments(fs, deviceId, clock)

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
        dbs.close()
        libRoot.toFile().deleteRecursively()
    }

    companion object {
        /** A fresh rig; with [statements], every SQL statement the index database runs is logged there. */
        fun create(statements: StatementLog? = null): IndexRig {
            val libRoot = Files.createTempDirectory("npn-index-lib")
            val dbs =
                TempIndexDb("npn-index-db") { file ->
                    if (statements == null) npnDatabase(file) else recordingDatabase(file, statements)
                }
            return IndexRig(libRoot, dbs, JvmLibraryFileSystem(libRoot, PathExternalIo), MutableClock(1_000L))
        }
    }
}

/**
 * Writes v1 segments, `.npn/journal/<deviceId>/<first seq>.jsonl`, the way builds before storage-v2
 * did. Nothing writes these any more, but libraries still hold them and the reader must fold them.
 */
class LegacySegments(
    private val fs: LibraryFileSystem,
    private val deviceId: String,
    clock: EpochClock,
) {
    private val hlc = HlcWatermarkAllocator(clock)
    private var seq = 0L

    /** Write [ops] as the next segment; returns its path. */
    suspend fun write(ops: List<PendingOp>): String {
        val first = seq + 1
        val events =
            ops.map {
                JournalEvent(JournalEventVersions.CANONICAL, ++seq, hlc.next(deviceId), deviceId, it.doc, it.op)
            }
        val path = "${NpnPaths.JOURNAL_DIR}/$deviceId/${first.toString().padStart(SEQ_WIDTH, '0')}.jsonl"
        fs.writeBytesOnce(path, JournalCodec.encodeLines(events).encodeToByteArray())
        return path
    }

    private companion object {
        const val SEQ_WIDTH = 16
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
