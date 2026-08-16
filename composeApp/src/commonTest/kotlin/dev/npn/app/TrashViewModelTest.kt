package dev.npn.app

import dev.npn.app.ui.trash.TrashViewModel
import dev.npn.core.index.RelinkCandidate
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrashViewModelTest : MainDispatcherTest() {
    private fun vm(session: FakeLibrarySession = FakeLibrarySession()) = TrashViewModel(session)

    // ---- relinkCandidates derived from last scan report ----

    @Test
    fun relinkCandidatesEmptyWhenNoReport() =
        runTest(dispatcher) {
            val model = vm()
            val job = launch { model.relinkCandidates.collect {} }
            advanceUntilIdle()
            model.relinkCandidates.value shouldBe emptyList()
            job.cancel()
        }

    @Test
    fun relinkCandidatesExtractedFromReport() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val candidate = RelinkCandidate("old", "new", "name+size", 10)
            session.ingest.lastScanReportFlow.value = emptyScanReport().copy(relinkCandidates = listOf(candidate))
            val model = vm(session)
            val job = launch { model.relinkCandidates.collect {} }
            advanceUntilIdle()
            model.relinkCandidates.value shouldContainExactly listOf(candidate)
            job.cancel()
        }

    // ---- observed lists pass through ----

    @Test
    fun trashedDuplicatesMissingPassThrough() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            session.documents.trashed.value = listOf(listItem("d1"))
            session.documents.duplicates.value = listOf("d2" to "copy/d2.pdf")
            session.documents.missing.value = listOf(doc("d3"))
            val model = vm(session)
            val jobs =
                listOf(
                    launch { model.trashed.collect {} },
                    launch { model.duplicates.collect {} },
                    launch { model.missing.collect {} },
                )
            advanceUntilIdle()
            model.trashed.value.map { it.doc.docId } shouldContainExactly listOf("d1")
            model.duplicates.value shouldContainExactly listOf("d2" to "copy/d2.pdf")
            model.missing.value.map { it.docId } shouldContainExactly listOf("d3")
            jobs.forEach { it.cancel() }
        }

    // ---- write ops delegate ----

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

    // ---- failed writes surface a user-facing message ----

    @Test
    fun restoreFailureEmitsMessage() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    docLifecycle.failRestore =
                        { error("file exists at original path") }
                }
            val model = vm(session)
            val messages = mutableListOf<String>()
            val job = launch { model.messages.collect { messages += it.resolveEnglish() } }
            advanceUntilIdle()
            model.restore("d1")
            advanceUntilIdle()
            session.docLifecycle.restoredDocs shouldBe emptyList()
            messages shouldContainExactly listOf("Could not restore document: file exists at original path")
            job.cancel()
        }
}
