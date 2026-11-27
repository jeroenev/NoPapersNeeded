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
import dev.npn.core.fs.PathExternalIo
import dev.npn.core.journal.ContentAddressedJournalWriter
import dev.npn.core.journal.HlcWatermarkAllocator
import dev.npn.core.util.EpochClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

/**
 * A self-contained test rig: a temp library dir on the real JVM filesystem, a real Room database
 * in a separate temp file (the cache lives outside the library), and real journal pieces wired
 * over the same library. A mutable [MutableClock] gives deterministic times and monotonic HLCs.
 * Every component reads and writes the library through [fs], the decorator [create] was handed
 * over [raw], the real filesystem.
 */
internal class IndexRig private constructor(
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
    val accumulator = MetadataAccumulator(fs, db)

    val importService =
        ImportService(
            fs,
            db,
            deviceDb.ocrQueueDao(),
            clock,
            serialize = unserialized,
        )

    val duplicates =
        DuplicateResolver(fs, db.documentDao(), db.duplicateDao(), serialize = { it() })

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
     * running on [dispatcher].
     */
    internal fun coordinator(
        writer: ContentAddressedJournalWriter = writer(),
        accumulator: MetadataAccumulator = this.accumulator,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): JournalCoordinator = JournalCoordinator(writer, indexer, accumulator, dispatcher = dispatcher)

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
        val ext = scratchDir().resolve(name)
        Files.write(ext, content.encodeToByteArray())
        return dev.npn.core.fs
            .ExternalSource(ext)
    }

    private val scratchDirs: MutableList<Path> = Collections.synchronizedList(mutableListOf())

    /** A fresh directory outside the library, for files a test keeps aside; [close] deletes it. */
    fun scratchDir(): Path = Files.createTempDirectory("npn-scratch").also { scratchDirs.add(it) }

    fun close() {
        dbs.close()
        libRoot.toFile().deleteRecursively()
        scratchDirs.forEach { it.toFile().deleteRecursively() }
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
internal fun indexTest(block: suspend TestScope.(IndexRig) -> Unit): TestResult =
    runTest { IndexRig.create().use { block(it) } }

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
 * journal that holds no relink and hides no document but one it was told was taken back. Each read
 * of it is a journal of its own.
 */
internal val unserialized: suspend (suspend (LockedJournal) -> Unit) -> Unit
    get() {
        val takenBack = HashSet<String>()
        return { step -> step(takingBack(takenBack)) }
    }

/** The journal of [unserialized], which has deleted the documents in [takenBack] and no other. */
private fun takingBack(takenBack: MutableSet<String>): LockedJournal =
    object : LockedJournal {
        override suspend fun successorOf(docId: String): String = docId

        override suspend fun isDeleted(docId: String): Boolean = docId in takenBack

        override suspend fun isTrashed(docId: String): Boolean = false

        override suspend fun isCurrent(docId: String): Boolean = true

        override suspend fun restoreReturned(
            docId: String,
            relPath: String,
        ): Boolean = false

        override suspend fun recordTakenBack(docId: String) {
            takenBack += docId
        }

        override suspend fun trashTimes(): Map<String, Long?> = emptyMap()
    }
