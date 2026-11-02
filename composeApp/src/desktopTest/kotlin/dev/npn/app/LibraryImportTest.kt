package dev.npn.app

import dev.npn.app.ui.library.LibraryViewModel
import dev.npn.core.app.ImportFailure
import dev.npn.core.app.ImportOutcome
import dev.npn.core.app.LibraryFilter
import dev.npn.core.fs.ExternalSource
import dev.npn.core.ocr.OcrState
import dev.npn.core.testing.doc
import dev.npn.core.testing.listItem
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test

/**
 * The library's picker import: one summary for the batch, whatever each file came to, a Show that
 * narrows the library to the new documents, and once their text is read, one tip to search it.
 */
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
            val messages = collecting(model.messages.map { it.english() })

            model.import(sources)
            advanceUntilIdle()

            messages shouldContainExactly listOf("1 imported, 1 duplicate, 1 failed · reading text…")
        }

    /** Nothing new: no text to read, and nothing to show. */
    @Test
    fun anImportOfDuplicatesOnlyOffersNothingToShow() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply { ingest.importOutcomes = listOf(ImportOutcome.Duplicate("a", "a.pdf")) }
            val model = LibraryViewModel(session, FakeSettingsRepository())
            val messages = collecting(model.messages)

            model.import(sources)
            advanceUntilIdle()

            messages.single().english() shouldBe "3 duplicates"
            messages.single().action shouldBe null
        }

    @Test
    fun showNarrowsTheLibraryToTheNewDocuments() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    ingest.importOutcomes =
                        listOf(
                            ImportOutcome.Imported("a", "a.pdf"),
                            ImportOutcome.Duplicate("x", "x.pdf"),
                            ImportOutcome.Imported("c", "c.pdf"),
                        )
                }
            val model = LibraryViewModel(session, FakeSettingsRepository())
            val messages = collecting(model.messages)

            model.import(sources)
            advanceUntilIdle()
            val show = messages.single().action
            show?.label?.english() shouldBe "Show"
            show?.onPerform?.invoke()

            model.ui.value.filter shouldBe LibraryFilter(docIds = listOf("a", "c"))
        }

    /** Once the new documents' text is read, the user is told to try searching it, once per device. */
    @Test
    fun theSearchTipComesOnceTheTextIsRead() =
        runTest(dispatcher) {
            val session = importing("a", "b", "c")
            session.documents.documents.value = listOf("a", "b", "c").map { reading(it, OcrState.PENDING) }
            val settings = FakeSettingsRepository()
            val model = LibraryViewModel(session, settings)
            val messages = collecting(model.messages.map { it.english() })

            model.import(sources)
            runCurrent()
            messages shouldContainExactly listOf("3 imported · reading text…")

            session.documents.documents.value =
                listOf(reading("a", OcrState.DONE), reading("b", OcrState.FAILED), reading("c", OcrState.ADOPTED))
            runCurrent()

            messages shouldContainExactly
                listOf(
                    "3 imported · reading text…",
                    "The text of 2 new documents is ready. Try searching for a word from one of their pages.",
                )
            settings.settings.value.searchTipShown shouldBe true

            // The next import's text is read too, but the tip was given.
            model.import(sources)
            advanceUntilIdle()
            messages.size shouldBe 3
        }

    /** Text that is never read (no engine installed, say) is not waited for all session. */
    @Test
    fun theTipStopsWaitingForTextThatIsNeverRead() =
        runTest(dispatcher) {
            val session = importing("a", "b", "c")
            session.documents.documents.value = listOf("a", "b", "c").map { reading(it, OcrState.PENDING) }
            val settings = FakeSettingsRepository()
            val model = LibraryViewModel(session, settings)
            val messages = collecting(model.messages.map { it.english() })

            model.import(sources)
            advanceUntilIdle()
            session.documents.documents.value = listOf("a", "b", "c").map { reading(it, OcrState.DONE) }
            advanceUntilIdle()

            messages shouldContainExactly listOf("3 imported · reading text…")
            settings.settings.value.searchTipShown shouldBe false
        }

    /** A user who has searched already needs no tip, nor does an import whose text could not be read. */
    @Test
    fun noTipForASearcherOrForUnreadText() =
        runTest(dispatcher) {
            val session = importing("a", "b", "c")
            session.documents.documents.value = listOf("a", "b", "c").map { reading(it, OcrState.FAILED) }
            val settings = FakeSettingsRepository()
            val model = LibraryViewModel(session, settings)
            val messages = collecting(model.messages.map { it.english() })

            model.import(sources)
            advanceUntilIdle()
            settings.updateSettings { it.copy(searchTried = true) }
            session.documents.documents.value = listOf("a", "b", "c").map { reading(it, OcrState.DONE) }
            model.import(sources)
            advanceUntilIdle()

            messages shouldContainExactly listOf("3 imported · reading text…", "3 imported · reading text…")
            settings.settings.value.searchTipShown shouldBe false
        }

    @Test
    fun anImportThatThrowsIsReportedNotCrashed() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { ingest.failImport = { error("Library session is closed") } }
            val model = LibraryViewModel(session, FakeSettingsRepository())
            val messages = collecting(model.messages.map { it.english() })

            model.import(sources)
            advanceUntilIdle()

            messages shouldContainExactly listOf("Could not import the files: Library session is closed")
        }

    /** A session whose imports bring in [docIds], one per file. */
    private fun importing(vararg docIds: String) =
        FakeLibrarySession().apply { ingest.importOutcomes = docIds.map { ImportOutcome.Imported(it, "$it.pdf") } }

    private fun reading(
        docId: String,
        state: OcrState,
    ) = listItem(doc(docId, ocrState = state))
}
