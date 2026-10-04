package dev.npn.core.watch

import dev.npn.core.app.ImportOutcome
import dev.npn.core.app.IngestFacade
import dev.npn.core.app.ScanProgressUi
import dev.npn.core.fs.ExternalSource
import dev.npn.core.index.ScanReport
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
            mtimeMillis = 0,
            source = ExternalSource(Path.of("scan.pdf")),
        )

    private class FakeFolder(
        private val files: List<WatchedFolderFile>,
        private val events: Flow<Unit> = emptyFlow(),
    ) : WatchedFolder {
        var listings = 0

        override suspend fun listPdfs(): List<WatchedFolderFile> {
            listings++
            return files
        }

        override suspend fun delete(file: WatchedFolderFile) = true

        override fun changes(): Flow<Unit> = events
    }

    private class FakeIngest(
        private val import: suspend () -> ImportOutcome,
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
            return import()
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

            importer.start(backgroundScope, pollIntervalMillis = 3_600_000)
            advanceTimeBy(60_000)
            runCurrent()

            // The first poll's sweep, plus one for the whole burst.
            (folder.listings <= 2) shouldBe true
        }
}
