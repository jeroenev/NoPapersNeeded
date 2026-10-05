package dev.npn.app

import dev.npn.app.ui.common.UiMessage
import dev.npn.app.ui.library.LibraryViewModel
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** Bulk trash from the library selection, and the Undo message that takes it back. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryTrashTest : MainDispatcherTest() {
    private fun vm(session: FakeLibrarySession = FakeLibrarySession()) =
        LibraryViewModel(session, FakeSettingsRepository())

    /** Collect messages emitted by [model] into a list of resolved English strings. */
    private fun TestScope.collectMessages(model: LibraryViewModel): MutableList<String> {
        val messages = mutableListOf<String>()
        backgroundScope.launch { model.messages.collect { messages += it.resolveEnglish() } }
        return messages
    }

    @Test
    fun bulkTrashAllSucceedClearsSelection() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            model.startSelection("d1")
            model.toggleSelection("d2")
            model.toggleSelection("d3")
            model.bulkTrash()
            advanceUntilIdle()
            session.docLifecycle.trashedDocs shouldContainExactlyInAnyOrder listOf("d1", "d2", "d3")
            model.ui.value.selected shouldBe emptySet()
            model.ui.value.selectionMode shouldBe false
        }

    @Test
    fun bulkTrashOffersAnUndoThatRestoresTheTrashedIds() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            val messages = mutableListOf<UiMessage>()
            backgroundScope.launch { model.messages.collect { messages += it } }
            model.startSelection("d1")
            model.toggleSelection("d2")
            runCurrent()
            model.bulkTrash()
            advanceUntilIdle()

            val undo = messages.single()
            undo.resolveEnglish() shouldBe "Moved 2 documents to trash"
            undo.action
                ?.label
                ?.resolveEnglish() shouldBe "Undo"
            undo.action?.onPerform?.invoke()
            advanceUntilIdle()
            session.docLifecycle.restoredDocs shouldContainExactly listOf("d1", "d2")
        }

    @Test
    fun bulkTrashPartialFailureUndoesOnlyTrashedIds() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { docLifecycle.failTrashDocIds = setOf("d2") }
            val model = vm(session = session)
            val messages = mutableListOf<UiMessage>()
            backgroundScope.launch { model.messages.collect { messages += it } }
            model.startSelection("d1")
            model.toggleSelection("d2")
            model.toggleSelection("d3")
            runCurrent()
            model.bulkTrash()
            advanceUntilIdle()

            // Only the successfully trashed ids are offered for undo; d2 failed and stays selected.
            messages
                .single { it.action != null }
                .action
                ?.onPerform
                ?.invoke()
            advanceUntilIdle()
            session.docLifecycle.restoredDocs shouldContainExactly listOf("d1", "d3")
        }

    @Test
    fun bulkTrashNoSuccessOffersNoUndo() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { docLifecycle.failTrashDocIds = setOf("d1") }
            val model = vm(session = session)
            val messages = mutableListOf<UiMessage>()
            backgroundScope.launch { model.messages.collect { messages += it } }
            model.startSelection("d1")
            runCurrent()
            model.bulkTrash()
            advanceUntilIdle()
            messages.filter { it.action != null } shouldBe emptyList()
        }

    @Test
    fun undoTrashRestoresGivenIds() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            model.undoTrash(listOf("d1", "d2"))
            advanceUntilIdle()
            session.docLifecycle.restoredDocs shouldContainExactly listOf("d1", "d2")
        }

    @Test
    fun undoTrashPartialFailureEmitsMessage() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { docLifecycle.failRestore = { error("path taken") } }
            val model = vm(session = session)
            val messages = collectMessages(model)
            model.undoTrash(listOf("d1", "d2"))
            advanceUntilIdle()
            messages shouldContainExactly listOf("Could not restore 2 of 2 documents")
        }

    @Test
    fun bulkTrashPartialFailureKeepsOnlyFailedIdsSelected() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { docLifecycle.failTrashDocIds = setOf("d2") }
            val model = vm(session = session)
            model.startSelection("d1")
            model.toggleSelection("d2")
            model.toggleSelection("d3")

            val messages = mutableListOf<String>()
            val collector = launch { model.messages.collect { messages += it.resolveEnglish() } }
            runCurrent()
            model.bulkTrash()
            advanceUntilIdle()
            collector.cancel()
            // Both messages show, in order: the failure, then the Undo for what did move.
            messages shouldContainExactly
                listOf("Could not move 1 of 3 documents to the trash", "Moved 2 documents to trash")
            // d1 and d3 succeeded; only d2 kept selected for retry.
            session.docLifecycle.trashedDocs shouldContainExactlyInAnyOrder listOf("d1", "d3")
            model.ui.value.selected shouldBe setOf("d2")
            model.ui.value.selectionMode shouldBe true
        }
}
