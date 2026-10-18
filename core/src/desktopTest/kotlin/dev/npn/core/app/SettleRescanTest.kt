package dev.npn.core.app

import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.FsEvent
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.index.WriteSettle
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

/**
 * The rescans a session runs by itself. The follow-up it schedules for files that were still being
 * written: a deferred file gets indexed without anyone rescanning, and a newer signal replaces a
 * pending follow-up rather than adding a second one. And the retry of an initial rescan that failed,
 * at the maintenance tick.
 *
 * The session runs on its own [time], which only the test advances: a follow-up's delay passes when
 * the test says so, never while a rescan waits on the database.
 */
class SettleRescanTest {
    private val time = TestCoroutineScheduler()

    /** Every library listing a rescan makes, so the test can count rescans. */
    private val listings = AtomicInteger()

    /** The next read of this path gets other bytes: a file another app is rewriting in place. */
    @Volatile private var rewriting: String? = null

    /** While set, the library cannot be listed, so every rescan fails. */
    @Volatile private var unreadable = false

    private fun over(fs: LibraryFileSystem): LibraryFileSystem =
        object : LibraryFileSystem by fs {
            override suspend fun listAllIndexable(): List<FileMeta> {
                if (unreadable) throw IOException("library unreadable")
                return fs.listAllIndexable().also { listings.incrementAndGet() }
            }

            override suspend fun readBytes(relPath: String): ByteArray {
                if (relPath != rewriting) return fs.readBytes(relPath)
                rewriting = null
                return PdfFixtures.embeddedTextPdf("half written")
            }

            // No folder watch: every rescan here is one the test or the session itself asked for.
            override fun watch(): Flow<FsEvent> = emptyFlow()
        }

    private fun settleTest(block: suspend (AppSessionRig) -> Unit) =
        runTest {
            val host = Job()
            val rig = AppSessionRig.create(CoroutineScope(host + UnconfinedTestDispatcher(time)), files = ::over)
            try {
                block(rig)
            } finally {
                rig.close()
                host.cancel()
            }
        }

    /** A PDF at [relPath] whose last write is now, by the session's clock: still settling. */
    private fun AppSessionRig.writeSettling(relPath: String) {
        writePdf(relPath, PdfFixtures.embeddedTextPdf("still settling"))
        Files.setLastModifiedTime(libRoot.resolve(relPath), FileTime.fromMillis(clock.now))
    }

    /**
     * Let the session's follow-up delays pass until [count] listings have been made, waiting in real
     * time for the rescans themselves, which run on the database's threads.
     */
    private suspend fun awaitListings(count: Int) =
        withContext(Dispatchers.Default) {
            withTimeout(TIMEOUT_MILLIS) {
                while (listings.get() < count) {
                    time.advanceTimeBy(PAST_A_FOLLOW_UP_MILLIS)
                    delay(POLL_MILLIS)
                }
            }
        }

    @Test
    fun aFileStillSettlingIsIndexedByTheFollowUpRescan() =
        settleTest { rig ->
            rig.writeSettling("in.pdf")

            val report = rig.session.rescan()

            report.deferred shouldContainExactly listOf("in.pdf")
            report.retryAfterMillis.shouldNotBeNull()
            rig.db
                .documentDao()
                .getByRelPath("in.pdf")
                .shouldBeNull()

            rig.clock.advance(WriteSettle.QUIET_MILLIS)
            awaitListings(2)
            rig.session.rescan()

            rig.db
                .documentDao()
                .getByRelPath("in.pdf")
                .shouldNotBeNull()
            // The test's own two rescans and the one follow-up: it found the file settled.
            listings.get() shouldBe 3
        }

    @Test
    fun aSecondDeferralReplacesThePendingFollowUp() =
        settleTest { rig ->
            rig.writeSettling("in.pdf")
            rig.session.rescan()
            rig.session.rescan()

            rig.clock.advance(WriteSettle.QUIET_MILLIS)
            awaitListings(3)
            // Queued behind any other follow-up on the journal's lock, so one would be counted first.
            rig.session.rescan()

            rig.db
                .documentDao()
                .getByRelPath("in.pdf")
                .shouldNotBeNull()
            listings.get() shouldBe 4
        }

    /** A file OCR found changing under it is rescanned once it has had time to settle. */
    @Test
    fun aStaleFileSignalFromOcrSchedulesAFollowUpRescan() =
        settleTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("rewritten in place"))
            rewriting = "a.pdf"

            // The initial rescan indexes the file, and OCR then reads other bytes than it hashed.
            rig.session.start()
            awaitListings(2)
            rig.session.rescan()

            listings.get() shouldBe 3
        }

    /**
     * An initial rescan that failed keeps the drains waiting, and on desktop nothing else rescans
     * until the folder changes: the maintenance tick tries again, and its rescan lets them run.
     */
    @Test
    fun theMaintenanceTickRetriesAFailedInitialRescan() =
        settleTest { rig ->
            unreadable = true
            rig.session.start()
            rig.session.awaitInitialRescan() shouldBe false
            unreadable = false

            withContext(Dispatchers.Default) {
                withTimeout(TIMEOUT_MILLIS) {
                    while (!rig.session.awaitInitialRescan()) {
                        time.advanceTimeBy(PAST_A_TICK_MILLIS)
                        delay(POLL_MILLIS)
                    }
                }
            }
        }

    private companion object {
        /** Past the session's maintenance tick. */
        const val PAST_A_TICK_MILLIS = 15L * 60L * 1000L + 1_000L

        /** Beyond the longest settle wait plus the session's margin past it. */
        const val PAST_A_FOLLOW_UP_MILLIS = WriteSettle.QUIET_MILLIS + 1_000L
        const val POLL_MILLIS = 20L
        const val TIMEOUT_MILLIS = 20_000L
    }
}
