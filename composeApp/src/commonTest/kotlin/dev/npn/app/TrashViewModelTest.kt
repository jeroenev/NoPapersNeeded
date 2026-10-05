package dev.npn.app

import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.common.uiText
import dev.npn.app.ui.trash.TrashViewModel
import dev.npn.core.index.RelinkCandidate
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_trash_restore_failed
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrashViewModelTest : MainDispatcherTest() {
    private fun vm(session: FakeLibrarySession = FakeLibrarySession()) = TrashViewModel(session)

    @Test
    fun relinkCandidatesEmptyWhenNoReport() =
        runTest(dispatcher) {
            val model = vm()
            val job = launch { model.state.collect {} }
            advanceUntilIdle()
            model.state.value.relinks shouldBe emptyMap()
            job.cancel()
        }

    @Test
    fun relinkCandidatesExtractedFromReport() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val candidate = RelinkCandidate("old", "new", "name+size", 10)
            session.ingest.lastScanReportFlow.value = emptyScanReport().copy(relinkCandidates = listOf(candidate))
            val model = vm(session)
            val job = launch { model.state.collect {} }
            advanceUntilIdle()
            model.state.value.relinks shouldBe mapOf("old" to listOf(candidate))
            job.cancel()
        }

    @Test
    fun trashedDuplicatesMissingPassThroughOneState() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            session.documents.trashed.value = listOf(listItem("d1"))
            session.documents.duplicates.value = listOf("d2" to "copy/d2.pdf")
            session.documents.missing.value = listOf(doc("d3"))
            val model = vm(session)
            val job = launch { model.state.collect {} }
            advanceUntilIdle()
            val state = model.state.value
            state.trashed.map { it.doc.docId } shouldContainExactly listOf("d1")
            state.duplicates shouldContainExactly listOf("d2" to "copy/d2.pdf")
            state.missing.map { it.docId } shouldContainExactly listOf("d3")
            job.cancel()
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
    fun restoreFailureEmitsMessage() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    docLifecycle.failRestore =
                        { error("file exists at original path") }
                }
            val model = vm(session)
            val messages = mutableListOf<UiText>()
            val job = launch { model.messages.collect { messages += it.text } }
            advanceUntilIdle()
            model.restore("d1")
            advanceUntilIdle()
            session.docLifecycle.restoredDocs shouldBe emptyList()
            messages shouldContainExactly
                listOf(failure(Res.string.common_trash_restore_failed, "file exists at original path"))
            job.cancel()
        }
}
