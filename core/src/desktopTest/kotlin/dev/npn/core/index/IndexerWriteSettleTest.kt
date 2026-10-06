package dev.npn.core.index

import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.journal.PendingOp
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** A PDF written in place is indexed only once its bytes have settled (see [WriteSettle]). */
class IndexerWriteSettleTest {
    private lateinit var rig: IndexRig

    @BeforeTest fun setup() {
        rig = IndexRig.create()
    }

    @AfterTest fun teardown() {
        rig.close()
    }

    private fun write(
        relPath: String,
        content: String,
        mtime: Long,
        append: Boolean = false,
    ) {
        val p = rig.libRoot.resolve(relPath)
        Files.createDirectories(p.parent)
        val options =
            if (append) {
                arrayOf(StandardOpenOption.APPEND)
            } else {
                arrayOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
            }
        Files.write(p, content.encodeToByteArray(), *options)
        Files.setLastModifiedTime(p, FileTime.fromMillis(mtime))
    }

    private suspend fun docIdAt(relPath: String): String? =
        rig.db
            .documentDao()
            .rowAtPath(relPath)
            ?.docId

    @Test
    fun fileStillBeingWrittenIsDeferredThenIndexedWithItsFinalHash() =
        runTest {
            write("in.pdf", "%PDF-1.7 first half", mtime = T)
            rig.clock.now = T + 1_000

            val early = rig.rescan()

            early.added shouldBe 0
            early.deferred shouldContainExactly listOf("in.pdf")
            early.retryAfterMillis shouldBe WriteSettle.QUIET_MILLIS - 1_000
            docIdAt("in.pdf").shouldBeNull()

            write("in.pdf", " second half %%EOF\n", mtime = T + 2_000, append = true)
            rig.clock.now = T + 2_000 + WriteSettle.QUIET_MILLIS

            val settled = rig.rescan()

            settled.added shouldBe 1
            settled.deferred.shouldBeEmpty()
            settled.retryAfterMillis.shouldBeNull()
            docIdAt("in.pdf") shouldBe sha256Bytes("%PDF-1.7 first half second half %%EOF\n".encodeToByteArray())
        }

    @Test
    fun emptyFileIsWaitedOnWhileYoungAndNeverIndexed() =
        runTest {
            write("empty.pdf", "", mtime = T)
            rig.clock.now = T + WriteSettle.QUIET_MILLIS

            val young = rig.rescan()
            young.added shouldBe 0
            young.deferred shouldContainExactly listOf("empty.pdf")

            rig.clock.now = T + WriteSettle.TRAILER_GRACE_MILLIS
            val old = rig.rescan()
            old.added shouldBe 0
            old.deferred.shouldBeEmpty()
            old.retryAfterMillis.shouldBeNull()
        }

    @Test
    fun pausedWriteWithoutEofMarkerWaitsForTheGracePeriod() =
        runTest {
            write("slow.pdf", "%PDF-1.7 stalled copy", mtime = T)
            rig.clock.now = T + 10_000

            val paused = rig.rescan()
            paused.added shouldBe 0
            paused.retryAfterMillis shouldBe WriteSettle.TRAILER_GRACE_MILLIS - 10_000

            // A damaged PDF that never gets the marker is still a document once left alone.
            rig.clock.now = T + WriteSettle.TRAILER_GRACE_MILLIS
            rig.rescan().added shouldBe 1
        }

    @Test
    fun knownDocumentRewrittenInPlaceKeepsItsRowUntilTheNewBytesSettle() =
        runTest {
            write("a.pdf", "%PDF old %%EOF", mtime = T)
            rig.clock.now = T + WriteSettle.TRAILER_GRACE_MILLIS
            rig.rescan().added shouldBe 1
            val oldId = docIdAt("a.pdf").shouldNotBeNull()

            write("a.pdf", "%PDF new, partly", mtime = rig.clock.now - 500)
            val writing = rig.rescan()
            writing.changed shouldBe 0
            writing.missingCount shouldBe 0
            docIdAt("a.pdf") shouldBe oldId

            rig.clock.now += WriteSettle.TRAILER_GRACE_MILLIS
            val edited = rig.rescan()
            edited.changed shouldBe 1
            docIdAt("a.pdf") shouldBe sha256Bytes("%PDF new, partly".encodeToByteArray())
        }

    /** A file at a trashed document's path counts as its return only once the rescan knows its hash. */
    @Test
    fun aTrashedDocumentWaitsForItsReturningFileToSettle() =
        runTest {
            write("a.pdf", "%PDF old %%EOF", mtime = T)
            rig.clock.now = T + WriteSettle.TRAILER_GRACE_MILLIS
            rig.rescan()
            val trashed = docIdAt("a.pdf").shouldNotBeNull()
            rig.writer().commit(listOf(PendingOp.docTrash(trashed, "a.pdf")))
            rig.deleteFile("a.pdf")
            rig.rescan()

            write("a.pdf", "%PDF old %%EOF", mtime = rig.clock.now - 500)
            val resurrected = ArrayList<Resurrected>()
            val writing = rig.rescan { resurrected += it }

            writing.deferred shouldContainExactly listOf("a.pdf")
            resurrected.shouldBeEmpty()
            rig.db
                .documentDao()
                .getByDocId(trashed)
                ?.trashed shouldBe true

            rig.clock.now += WriteSettle.QUIET_MILLIS
            rig.rescan { resurrected += it }
            resurrected.map { it.docId } shouldContainExactly listOf(trashed)
        }

    @Test
    fun mtimeFromAClockFarAheadIsNotAWriteInProgress() =
        runTest {
            write("synced.pdf", "%PDF synced %%EOF", mtime = T + 3_600_000)
            rig.clock.now = T

            rig.rescan().added shouldBe 1
        }

    @Test
    fun fileWrittenToWhileHashedIsDeferred() =
        runTest {
            write("racing.pdf", "%PDF before %%EOF", mtime = T)
            rig.clock.now = T + WriteSettle.TRAILER_GRACE_MILLIS
            val racing =
                object : LibraryFileSystem by rig.fs {
                    override suspend fun sha256(relPath: String): String =
                        rig.fs.sha256(relPath).also {
                            write(relPath, "%PDF after %%EOF", mtime = T + 1)
                        }
                }
            val indexer = Indexer(racing, rig.db, rig.clock)

            val report = indexer.rescan(metadataState = rig.accumulator.loadState())

            report.added shouldBe 0
            report.deferred shouldContainExactly listOf("racing.pdf")
            report.retryAfterMillis shouldBe WriteSettle.QUIET_MILLIS
            docIdAt("racing.pdf").shouldBeNull()
        }

    @Test
    fun importingAnEmptyFileIsRejectedAndLeavesNothingBehind() =
        runTest {
            assertFailsWith<EmptyImportException> {
                rig.importService.importPdf(rig.externalFile("empty.pdf", ""), "empty.pdf")
            }

            rig.db
                .documentDao()
                .allPathsAndMeta()
                .shouldBeEmpty()
            rig.fs
                .listAllIndexable()
                .filter { !it.isDirectory }
                .shouldBeEmpty()
        }

    private companion object {
        const val T = 1_700_000_000_000L
    }
}
