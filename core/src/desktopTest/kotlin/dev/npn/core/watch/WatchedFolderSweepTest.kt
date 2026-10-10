package dev.npn.core.watch

import dev.npn.core.app.ImportOutcome
import dev.npn.core.app.IngestFacade
import dev.npn.core.app.ScanProgressUi
import dev.npn.core.fs.ExternalSource
import dev.npn.core.index.ScanReport
import dev.npn.core.journal.EpochClock
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test

/** How sweeps are scheduled and contained, against an in-memory folder and ingest. */
class WatchedFolderSweepTest {
    private val file =
        WatchedFolderFile(
            id = "scan.pdf",
            name = "scan.pdf",
            size = 10,
            mtimeMillis = 1_000,
            source = ExternalSource(Path.of("scan.pdf")),
        )

    private class FakeFolder(
        private val files: List<WatchedFolderFile>,
        private val events: Flow<Unit> = emptyFlow(),
    ) : WatchedFolder {
        var listings = 0

        override suspend fun listPdfs(): List<WatchedFolderFile> {
            listings++
            return files.toList()
        }

        override suspend fun delete(
            file: WatchedFolderFile,
            docId: String,
        ) = true

        override fun changes(): Flow<Unit> = events
    }

    private class FakeIngest(
        /** The outcome of an import of the file named so. */
        private val import: suspend (String) -> ImportOutcome,
    ) : IngestFacade {
        var imports = 0

        override val scanProgress: Flow<ScanProgressUi?> = emptyFlow()
        override val lastScanReport: Flow<ScanReport?> = emptyFlow()

        override suspend fun rescan(): ScanReport = error("not used")

        override suspend fun importPdf(
            source: ExternalSource,
            suggestedName: String,
        ): ImportOutcome {
            imports++
            return import(suggestedName)
        }

        override suspend fun importPdfBytes(
            bytes: ByteArray,
            suggestedName: String,
        ): ImportOutcome = error("not used")

        override suspend fun importImagesAsPdf(
            images: List<ByteArray>,
            suggestedName: String,
        ): ImportOutcome = error("not used")
    }

    @Test
    fun overlappingSweepsImportAFileOnce() =
        runTest {
            val ingest =
                FakeIngest {
                    delay(100)
                    ImportOutcome.Imported("doc", "scan.pdf")
                }
            val importer = WatchedFolderImporter(ingest, FakeFolder(listOf(file)), { false }, minQuietMillis = 0)

            val first = async { importer.scanOnce() }
            val second = async { importer.scanOnce() }

            (first.await().imported + second.await().imported) shouldBe 1
            ingest.imports shouldBe 1
        }

    @Test
    fun aCancelledImportCostsNoAttempt() =
        runTest {
            val started = CompletableDeferred<Unit>()
            var calls = 0
            val ingest =
                FakeIngest {
                    calls++
                    if (calls == 1) {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                    ImportOutcome.Imported("doc", "scan.pdf")
                }
            val importer =
                WatchedFolderImporter(ingest, FakeFolder(listOf(file)), { false }, minQuietMillis = 0, maxAttempts = 1)

            val sweep = launch { importer.scanOnce() }
            started.await()
            sweep.cancelAndJoin()

            importer.scanOnce().imported shouldBe 1
        }

    @Test
    fun aBurstOfChangeEventsCostsAtMostOneMoreSweep() =
        runTest {
            val burst = flow { repeat(64) { emit(Unit) } }
            val folder = FakeFolder(emptyList(), burst)
            val importer = WatchedFolderImporter(FakeIngest { error("nothing to import") }, folder, { false })

            importer.start(backgroundScope, pollIntervalMillis = 3_600_000, timeSource = testScheduler.timeSource)
            advanceTimeBy(60_000)
            runCurrent()

            // The first poll's sweep, plus one for the whole burst.
            (folder.listings <= 2) shouldBe true
        }

    /**
     * A scanner writes a file while a slow sweep runs, and the poll ticks before that sweep ends.
     * The poll does not cut the change's quiet window short: the file is imported once it has gone
     * quiet, not skipped as too fresh by a sweep at once and left for the next poll.
     */
    @Test
    fun aPollDuringASlowSweepKeepsTheWaitAChangeAskedFor() =
        runTest {
            val slow = file.copy(id = "slow.pdf", name = "slow.pdf", mtimeMillis = -60_000)
            val scan = file.copy(mtimeMillis = 59_500)
            val files = mutableListOf(slow)
            val scanner =
                flow {
                    delay(scan.mtimeMillis)
                    files += scan
                    emit(Unit)
                }
            val importedAt = HashMap<String, Long>()
            val ingest =
                FakeIngest { name ->
                    if (name == slow.name) delay(61_000)
                    importedAt[name] = currentTime
                    ImportOutcome.Imported(name, name)
                }
            val importer =
                WatchedFolderImporter(ingest, FakeFolder(files, scanner), { false }, clock = EpochClock { currentTime })

            importer.start(backgroundScope, pollIntervalMillis = 60_000, timeSource = testScheduler.timeSource)
            advanceTimeBy(64_000)
            runCurrent()

            importedAt[slow.name] shouldBe 61_000L
            // The change's own wait: the quiet window and the settle margin after the event.
            importedAt[scan.name] shouldBe 62_500L
        }
}
