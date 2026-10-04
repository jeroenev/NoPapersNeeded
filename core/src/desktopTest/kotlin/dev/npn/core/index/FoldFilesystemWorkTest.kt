package dev.npn.core.index

import androidx.room3.useWriterConnection
import dev.npn.core.app.JournalCoordinator
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.StatementLog
import dev.npn.core.db.recordingDatabase
import dev.npn.core.fs.ExternalSource
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.JvmLibraryFileSystem
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.VerifiedPublicationResult
import dev.npn.core.journal.HlcClock
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.SegmentWriter
import dev.npn.core.model.SystemProperty
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * A [LibraryFileSystem] over a real one that records every call it makes and whether the calling
 * coroutine held an open index transaction at the time.
 */
private class WatchedFileSystem(
    private val delegate: JvmLibraryFileSystem,
) : LibraryFileSystem by delegate {
    lateinit var db: NpnDatabase
    private val calls = ArrayList<String>()
    private val inTransaction = ArrayList<String>()

    fun calls(name: String): List<String> = synchronized(calls) { calls.filter { it.startsWith("$name ") } }

    /** Every call made while a transaction was open. */
    fun callsInsideTransactions(): List<String> = synchronized(calls) { inTransaction.toList() }

    fun clear() = synchronized(calls) {
        calls.clear()
        inTransaction.clear()
    }

    private suspend fun record(
        name: String,
        path: String,
    ) {
        val open = db.useWriterConnection { it.inTransaction() }
        synchronized(calls) {
            calls += "$name $path"
            if (open) inTransaction += "$name $path"
        }
    }

    override suspend fun listAll(): List<FileMeta> = delegate.listAll().also { record("listAll", "") }

    override suspend fun listAllIndexable(): List<FileMeta> = delegate.listAllIndexable().also { record("list", "") }

    override suspend fun listAllUnder(relPath: String): List<FileMeta> {
        record("list", relPath)
        return delegate.listAllUnder(relPath)
    }

    override suspend fun exists(relPath: String): Boolean {
        record("exists", relPath)
        return delegate.exists(relPath)
    }

    override suspend fun stat(relPath: String): FileMeta? {
        record("stat", relPath)
        return delegate.stat(relPath)
    }

    override suspend fun readBytes(relPath: String): ByteArray {
        record("read", relPath)
        return delegate.readBytes(relPath)
    }

    override suspend fun sha256(relPath: String): String {
        record("sha256", relPath)
        return delegate.sha256(relPath)
    }

    override suspend fun writeBytesOnce(
        relPath: String,
        bytes: ByteArray,
    ) {
        record("write", relPath)
        delegate.writeBytesOnce(relPath, bytes)
    }

    override suspend fun publishVerifiedObjectOnce(
        stagingPath: String,
        canonicalPath: String,
        expectedHash: String,
    ): VerifiedPublicationResult {
        record("publish", canonicalPath)
        return delegate.publishVerifiedObjectOnce(stagingPath, canonicalPath, expectedHash)
    }

    override suspend fun claimDocument(
        fromRelPath: String,
        toRelPath: String,
    ) {
        record("claim", fromRelPath)
        delegate.claimDocument(fromRelPath, toRelPath)
    }

    override suspend fun copyOnce(
        fromRelPath: String,
        toRelPath: String,
    ) {
        record("copy", fromRelPath)
        delegate.copyOnce(fromRelPath, toRelPath)
    }

    override suspend fun move(
        fromRelPath: String,
        toRelPath: String,
    ) {
        record("move", fromRelPath)
        delegate.move(fromRelPath, toRelPath)
    }

    override suspend fun delete(relPath: String) {
        record("delete", relPath)
        delegate.delete(relPath)
    }

    override suspend fun importExternal(
        source: ExternalSource,
        toRelPath: String,
    ) {
        record("import", toRelPath)
        delegate.importExternal(source, toRelPath)
    }
}

/**
 * What a fold costs on the filesystem. Locating a document and recording where it was seen are
 * filesystem work: they run after the fold's transaction, read their baselines in bulk, and take a
 * rescan's hashes instead of hashing every PDF again.
 */
class FoldFilesystemWorkTest {
    private val log = StatementLog()
    private lateinit var libRoot: Path
    private lateinit var dbDir: File
    private lateinit var fs: WatchedFileSystem
    private lateinit var db: NpnDatabase
    private val clock = MutableClock(1_000L)
    private lateinit var indexer: Indexer
    private lateinit var accumulator: MetadataAccumulator
    private lateinit var coordinator: JournalCoordinator

    @BeforeTest
    fun setup() {
        libRoot = Files.createTempDirectory("npn-fold-fs-lib")
        dbDir = Files.createTempDirectory("npn-fold-fs-db").toFile()
        fs = WatchedFileSystem(JvmLibraryFileSystem(libRoot))
        db = recordingDatabase(File(dbDir, "index.db"), log)
        fs.db = db
        indexer = Indexer(fs, db, clock)
        accumulator = MetadataAccumulator(fs, db, installationId = "test-installation")
        val writer =
            SegmentWriter(
                store = FsSegmentStore(fs),
                deviceId = "dev-main",
                hlcClock = HlcClock("dev-main", clock),
                localState = DbLocalJournalState(db.keyValueDao()),
            )
        coordinator = JournalCoordinator(writer, indexer, accumulator)
    }

    @AfterTest
    fun teardown() {
        db.close()
        libRoot.toFile().deleteRecursively()
        dbDir.deleteRecursively()
    }

    private fun writePdfs(count: Int) {
        repeat(count) { i ->
            val p = libRoot.resolve("doc-$i.pdf")
            Files.write(p, "document $i".encodeToByteArray())
        }
    }

    private fun baselineReadsOneByOne(): Int = log.statements.count { it.startsWith(BASELINE_BY_KEY) }

    @Test
    fun aFirstScanHashesEveryPdfOnceAndTouchesNoFileInsideATransaction() =
        runTest {
            writePdfs(DOCS)

            coordinator.rescan()

            fs.calls("sha256").size shouldBe DOCS
            fs.callsInsideTransactions().shouldBeEmpty()
            baselineReadsOneByOne() shouldBe 0
        }

    @Test
    fun anUnchangedRescanHashesNothingAndReadsItsBaselinesInBulk() =
        runTest {
            writePdfs(DOCS)
            coordinator.rescan()
            fs.clear()
            log.clear()

            coordinator.rescan()

            fs.calls("sha256").shouldBeEmpty()
            fs.calls("exists").shouldBeEmpty()
            baselineReadsOneByOne() shouldBe 0
        }

    @Test
    fun reprojectingTheWholeLibraryTouchesNoPdf() =
        runTest {
            writePdfs(DOCS)
            coordinator.rescan()
            fs.clear()
            log.clear()

            indexer.refreshState(accumulator.loadState())

            fs.calls("sha256").shouldBeEmpty()
            fs.calls("exists").shouldBeEmpty()
            fs.calls("stat").shouldBeEmpty()
            baselineReadsOneByOne() shouldBe 0
        }

    @Test
    fun aRenameMovesTheFileOnceItsCommitIsWritten() =
        runTest {
            writePdfs(1)
            coordinator.rescan()
            val docId = checkNotNull(db.documentDao().rowAtPath("doc-0.pdf")).docId
            fs.clear()

            coordinator.commitAndApply(
                listOf(PendingOp.propSet(docId, SystemProperty.STORAGE_PATH, "archive/renamed-statement.pdf")),
            )

            fs.callsInsideTransactions().shouldBeEmpty()
            Files.exists(libRoot.resolve("archive/renamed-statement.pdf")) shouldBe true
            Files.exists(libRoot.resolve("doc-0.pdf")) shouldBe false
            val row = checkNotNull(db.documentDao().getByDocId(docId))
            row.relPath shouldBe "archive/renamed-statement.pdf"
            row.fileName shouldBe "renamed-statement.pdf"
            db.ftsDao().searchDocIds("renamed") shouldContainExactly listOf(docId)
        }

    private companion object {
        const val DOCS = 12
        const val BASELINE_BY_KEY = "SELECT value FROM kv WHERE key = ?"
    }
}
