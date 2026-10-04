package dev.npn.app

import dev.npn.app.platform.ScannedPdf
import dev.npn.app.ui.library.LibraryViewModel
import dev.npn.core.app.ImportFailure
import dev.npn.core.app.ImportOutcome
import dev.npn.core.fs.ExternalSource
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test

/**
 * What a finished scan says once its PDF is imported. Lives on the desktop target only because a
 * scanned PDF arrives as an [ExternalSource], which common code cannot construct.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryScanTest : MainDispatcherTest() {
    private val scanned = ScannedPdf(ExternalSource(Path.of("scan.pdf")), "scan.pdf")

    @Test
    fun anImportedScanSaysSo() = expectMessage(ImportOutcome.Imported("a", "a.pdf"), "Scan imported")

    @Test
    fun aScanAlreadyInTheLibrarySaysSo() = expectMessage(ImportOutcome.Duplicate("a", "a.pdf"), "Scan already in library")

    @Test
    fun aRefusedScanNamesTheReason() =
        expectMessage(ImportOutcome.Failed(ImportFailure.EMPTY_FILE), "Scan import failed: the file is empty")

    @Test
    fun aScanImportThatThrowsIsReported() =
        expectMessage(failure = { error("disk full") }, expected = "Scan import failed: disk full")

    @Test
    fun aFailureWithoutAMessageFallsBackToUnknownError() =
        expectMessage(failure = { throw object : Throwable() {} }, expected = "Scan import failed: unknown error")

    private fun expectMessage(
        outcome: ImportOutcome = ImportOutcome.Imported("a", "a.pdf"),
        expected: String,
        failure: (() -> Nothing)? = null,
    ) = runTest(dispatcher) {
        val session =
            FakeLibrarySession().apply {
                ingest.importOutcomes = listOf(outcome)
                ingest.failImport = failure
            }
        val model = LibraryViewModel(session, FakeSettingsRepository())
        val messages = mutableListOf<String>()
        val collector = launch { model.messages.collect { messages += it.resolveEnglish() } }

        model.scan { scanned }
        advanceUntilIdle()
        collector.cancel()

        messages shouldContainExactly listOf(expected)
    }
}
