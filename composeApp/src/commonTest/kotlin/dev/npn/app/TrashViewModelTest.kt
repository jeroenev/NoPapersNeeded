package dev.npn.app

import dev.npn.app.ui.common.uiText
import dev.npn.app.ui.trash.TrashViewModel
import dev.npn.core.index.RelinkCandidate
import dev.npn.core.testing.doc
import dev.npn.core.testing.listItem
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_restore_documents_failed
import no_papers_needed.composeapp.generated.resources.common_trash_delete_all_files_left
import no_papers_needed.composeapp.generated.resources.common_trash_delete_file_left
import no_papers_needed.composeapp.generated.resources.common_trash_restore_failed
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrashViewModelTest : MainDispatcherTest() {
    private fun vm(session: FakeLibrarySession = FakeLibrarySession()) = TrashViewModel(session)

    @Test
    fun relinkCandidatesEmptyWhenNoReport() =
        runTest(dispatcher) {
            val model = vm()
            collecting(model.state)
            advanceUntilIdle()
            model.state.value.relinks shouldBe emptyMap()
        }

    @Test
    fun relinkCandidatesExtractedFromReport() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val candidate = RelinkCandidate("old", "new", "name+size", 10)
            session.ingest.lastScanReportFlow.value = emptyScanReport().copy(relinkCandidates = listOf(candidate))
            val model = vm(session)
            collecting(model.state)
            advanceUntilIdle()
            model.state.value.relinks shouldBe mapOf("old" to listOf(candidate))
        }

    @Test
    fun trashedDuplicatesMissingPassThroughOneState() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            session.documents.trashed.value = listOf(listItem("d1"))
            session.documents.duplicates.value = listOf("d2" to "copy/d2.pdf")
            session.documents.missing.value = listOf(doc("d3"))
            val model = vm(session)
            collecting(model.state)
            advanceUntilIdle()
            val state = model.state.value
            state.trashed.map { it.doc.docId } shouldContainExactly listOf("d1")
            state.duplicates shouldContainExactly listOf("d2" to "copy/d2.pdf")
            state.missing.map { it.docId } shouldContainExactly listOf("d3")
        }

    @Test
    fun restoreAndDeleteForeverDelegate() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session)
            model.restore("d1")
            model.deleteForever("d2")
            advanceUntilIdle()
            session.docLifecycle.restoredDocs shouldContainExactly listOf("d1")
            session.docLifecycle.deletedDocs shouldContainExactly listOf("d2")
        }

    @Test
    fun restoreAllRestoresEveryTrashedDocumentAndSaysHowManyFailed() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            session.documents.trashed.value = listOf(listItem("d1"), listItem("d2"))
            session.docLifecycle.failRestore = { error("file exists at original path") }
            val model = vm(session)
            collecting(model.state)
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()

            model.restoreAll()
            advanceUntilIdle()

            messages shouldContainExactly listOf(uiText(Res.plurals.common_restore_documents_failed, 2, 2, 2))
        }

    @Test
    fun emptyTrashDeletesEveryTrashedDocument() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            session.documents.trashed.value = listOf(listItem("d1"), listItem("d2"))
            val model = vm(session)
            collecting(model.state)
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()

            model.emptyTrash()
            advanceUntilIdle()

            session.docLifecycle.deletedDocs shouldContainExactly listOf("d1", "d2")
            messages shouldBe emptyList()
        }

    /** The document is deleted, but its file stayed: that is said, rather than nothing or a failure. */
    @Test
    fun deletingForeverSaysWhenTheFileCouldNotBeRemoved() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            session.docLifecycle.filesLeftDocIds = setOf("d1")
            val model = vm(session)
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()

            model.deleteForever("d1")
            advanceUntilIdle()

            session.docLifecycle.deletedDocs shouldContainExactly listOf("d1")
            messages shouldContainExactly listOf(uiText(Res.string.common_trash_delete_file_left))
        }

    @Test
    fun emptyTrashSaysHowManyFilesCouldNotBeRemoved() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            session.documents.trashed.value = listOf(listItem("d1"), listItem("d2"), listItem("d3"))
            session.docLifecycle.filesLeftDocIds = setOf("d1", "d3")
            val model = vm(session)
            collecting(model.state)
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()

            model.emptyTrash()
            advanceUntilIdle()

            messages shouldContainExactly listOf(uiText(Res.plurals.common_trash_delete_all_files_left, 2, 2))
        }

    @Test
    fun restoreFailureEmitsMessage() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    docLifecycle.failRestore =
                        { error("file exists at original path") }
                }
            val model = vm(session)
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()
            model.restore("d1")
            advanceUntilIdle()
            session.docLifecycle.restoredDocs shouldBe emptyList()
            messages shouldContainExactly
                listOf(failure(Res.string.common_trash_restore_failed, "file exists at original path"))
        }
}
