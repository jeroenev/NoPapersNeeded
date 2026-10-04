package dev.npn.app

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

/** The library's picker import: one summary for the batch, whatever each file came to. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryImportTest : MainDispatcherTest() {
    private val sources = listOf("a.pdf", "b.pdf", "c.pdf").map { ExternalSource(Path.of(it)) to it }

    @Test
    fun aRefusedFileIsCountedAndTheRestStillImport() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    ingest.importOutcomes =
                        listOf(
                            ImportOutcome.Failed(ImportFailure.COPY_FAILED),
                            ImportOutcome.Imported("b", "b.pdf"),
                            ImportOutcome.Duplicate("c", "c.pdf"),
                        )
                }
            val model = LibraryViewModel(session, FakeSettingsRepository())
            val messages = mutableListOf<String>()
            val collector = launch { model.messages.collect { messages += it.resolveEnglish() } }

            model.import(sources)
            advanceUntilIdle()
            collector.cancel()

            messages shouldContainExactly listOf("1 imported, 1 duplicate, 1 failed")
        }

    @Test
    fun anImportThatThrowsIsReportedNotCrashed() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { ingest.failImport = { error("Library session is closed") } }
            val model = LibraryViewModel(session, FakeSettingsRepository())
            val messages = mutableListOf<String>()
            val collector = launch { model.messages.collect { messages += it.resolveEnglish() } }

            model.import(sources)
            advanceUntilIdle()
            collector.cancel()

            messages shouldContainExactly listOf("Could not import the files: Library session is closed")
        }
}
