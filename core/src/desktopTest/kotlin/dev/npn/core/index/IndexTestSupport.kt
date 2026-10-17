package dev.npn.core.index

import dev.npn.core.app.DocListItem
import dev.npn.core.app.DocumentRepository
import dev.npn.core.db.DeviceDatabase
import dev.npn.core.db.DocumentDao
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.StatementLog
import dev.npn.core.db.TempIndexDb
import dev.npn.core.db.npnDatabase
import dev.npn.core.db.recordingDatabase
import dev.npn.core.fs.JvmLibraryFileSystem
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.PathExternalIo
import dev.npn.core.journal.ContentAddressedJournalWriter
import dev.npn.core.journal.EpochClock
import dev.npn.core.journal.HlcWatermarkAllocator
import dev.npn.core.journal.JournalCodec
import dev.npn.core.journal.JournalEvent
import dev.npn.core.journal.JournalEventVersions
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.SnapshotCompactor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path

/**
 * A self-contained test rig: a temp library dir on the real JVM filesystem, a real Room database
 * in a separate temp file (the cache lives outside the library), and real journal pieces wired
 * over the same library. A mutable [MutableClock] gives deterministic times and monotonic HLCs.
 * Every component reads and writes the library through [fs], the decorator [create] was handed
 * over [raw], the real filesystem.
 */
class IndexRig private constructor(
    val libRoot: Path,
    private val dbs: TempIndexDb,
    val raw: JvmLibraryFileSystem,
    val fs: LibraryFileSystem,
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
            db,
            deviceDb.ocrQueueDao(),
            clock,
            serialize = unserialized,
        )

    val maintenance =
        MaintenanceService(fs, db.documentDao(), db.duplicateDao(), importService)

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
    internal fun writer(
        installation: String = "test-installation",
        files: LibraryFileSystem = fs,
    ): ContentAddressedJournalWriter = ContentAddressedJournalWriter(files, installation, HlcWatermarkAllocator(clock))

    /**
     * Build a [JournalCoordinator] over [writer] backed by this rig's indexer and [accumulator],
     * compacting with [compaction] when one is given and running on [dispatcher].
     */
    internal fun coordinator(
        writer: ContentAddressedJournalWriter = writer(),
        accumulator: MetadataAccumulator = this.accumulator,
        compaction: Compaction? = null,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): JournalCoordinator = JournalCoordinator(writer, indexer, accumulator, compaction, dispatcher)

    /** Compaction over this library once more than [threshold] objects have settled for [stabilityWindowMs]. */
    internal fun compaction(
        threshold: Int,
        stabilityWindowMs: Long = 1_000L,
    ): Compaction =
        Compaction(
            SnapshotCompactor(fs, "test-installation"),
            SnapshotSupport(fs, db.metadataProjectionDao()),
            threshold = threshold,
            stabilityWindowMs = stabilityWindowMs,
        )

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

    /** Run [block] over this rig, then close it, whether or not [block] threw. */
    suspend fun use(block: suspend (IndexRig) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }

    companion object {
        /**
         * A fresh rig; with [statements], every SQL statement the index database runs is logged there.
         * Its components see the library through [files] over the real filesystem.
         */
        fun create(
            statements: StatementLog? = null,
            files: (JvmLibraryFileSystem) -> LibraryFileSystem = { it },
        ): IndexRig {
            val libRoot = Files.createTempDirectory("npn-index-lib")
            val dbs =
                TempIndexDb("npn-index-db") { file ->
                    if (statements == null) npnDatabase(file) else recordingDatabase(file, statements)
                }
            val raw = JvmLibraryFileSystem(libRoot, PathExternalIo)
            return IndexRig(libRoot, dbs, raw, files(raw), MutableClock(1_000L))
        }
    }
}

/** A test over a fresh [IndexRig], closed once [block] is done. */
fun indexTest(block: suspend TestScope.(IndexRig) -> Unit): TestResult = runTest { IndexRig.create().use { block(it) } }

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

/** [rowAtPath], failing the test with [relPath] when no live row is there. */
suspend fun DocumentDao.requireRowAtPath(relPath: String): DocumentEntity =
    checkNotNull(rowAtPath(relPath)) { "No live index row at $relPath" }

/** The row of [docId], failing the test with the id when the index has none. */
suspend fun DocumentDao.requireByDocId(docId: String): DocumentEntity =
    checkNotNull(getByDocId(docId)) { "No index row for document $docId" }

/** What the library shows of [docId] now, failing the test with the id when it shows nothing. */
suspend fun DocumentRepository.requireDoc(docId: String): DocListItem =
    checkNotNull(observeDoc(docId).first()) { "No document $docId in the library" }

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

/**
 * [ImportService]'s serialize where no coordinator holds a lock: each step runs at once, against a
 * journal that holds no relink and hides no document.
 */
val unserialized: suspend (suspend (LockedJournal) -> Unit) -> Unit = { step ->
    step(
        object : LockedJournal {
            override suspend fun successorOf(docId: String): String = docId

            override suspend fun isDeleted(docId: String): Boolean = false

            override suspend fun restoreReturned(
                docId: String,
                relPath: String,
            ): Boolean = false

            override suspend fun trashTimes(): Map<String, Long?> = emptyMap()
        },
    )
}
