package dev.npn.app

import dev.npn.app.ui.common.ImportImage
import dev.npn.app.ui.common.ImportRequest
import dev.npn.core.app.ImportOutcome
import dev.npn.core.fs.ExternalSource
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test

/**
 * Files dropped on the desktop window: imported into the open library from whatever screen is
 * showing, and reported in the app's own messages rather than only to the log. Desktop-only
 * because a dropped file arrives as an [ExternalSource], which common code cannot construct.
 */
class DroppedFilesTest : MainDispatcherTest() {
    private val dropped = ImportRequest(listOf("a.pdf", "b.pdf").map { ExternalSource(Path.of(it)) to it })

    @Test
    fun droppedFilesAreImportedAndTheUserIsToldWhatBecameOfThem() =
        runTest(dispatcher) {
            val env = FakeAppEnvironment(backgroundScope, savedLocation = "/lib")
            val vm = AppViewModel(env)
            advanceUntilIdle()
            env.sessions
                .getValue("/lib")
                .ingest.importOutcomes =
                listOf(ImportOutcome.Imported("a", "a.pdf"), ImportOutcome.Duplicate("b", "b.pdf"))

            messagesOf(vm) { vm.importFiles(dropped) {} } shouldContainExactly
                listOf("1 imported, 1 duplicate · reading text…")
        }

    /** The message's Show hands the documents the drop brought in to the library. */
    @Test
    fun showOpensTheLibraryOnTheDroppedDocuments() =
        runTest(dispatcher) {
            val env = FakeAppEnvironment(backgroundScope, savedLocation = "/lib")
            val vm = AppViewModel(env)
            advanceUntilIdle()
            env.sessions
                .getValue("/lib")
                .ingest.importOutcomes =
                listOf(ImportOutcome.Imported("a", "a.pdf"), ImportOutcome.Imported("b", "b.pdf"))
            val messages = collecting(vm.messages)
            val shown = mutableListOf<List<String>>()

            vm.importFiles(dropped) { shown += it }
            advanceUntilIdle()
            val action = messages.single().action
            action?.label?.english() shouldBe "Show"
            action?.onPerform?.invoke()

            shown shouldContainExactly listOf(listOf("a", "b"))
        }

    /** Images become one PDF together; one that cannot be read is counted as failed and left out. */
    @Test
    fun droppedImagesBecomeOneDocument() =
        runTest(dispatcher) {
            val env = FakeAppEnvironment(backgroundScope, savedLocation = "/lib")
            val vm = AppViewModel(env)
            advanceUntilIdle()
            val images =
                ImportRequest(
                    pdfs = emptyList(),
                    images =
                        listOf(
                            ImportImage("one.png") { byteArrayOf(1) },
                            ImportImage("two.jpg") { null },
                            ImportImage("three.jpg") { byteArrayOf(3) },
                        ),
                )

            messagesOf(vm) { vm.importFiles(images) {} } shouldContainExactly
                listOf("1 imported, 1 failed · reading text…")
        }

    @Test
    fun aDropWithNoLibraryOpenSaysToOpenOneFirst() =
        runTest(dispatcher) {
            val vm = AppViewModel(FakeAppEnvironment(backgroundScope))
            advanceUntilIdle()

            messagesOf(vm) { vm.importFiles(dropped) {} } shouldContainExactly
                listOf("Open a library first, then drop the files again.")
        }

    @Test
    fun aDropThatFailsSaysWhy() =
        runTest(dispatcher) {
            val env = FakeAppEnvironment(backgroundScope, savedLocation = "/lib")
            val vm = AppViewModel(env)
            advanceUntilIdle()
            env.sessions
                .getValue("/lib")
                .ingest.failImport = { error("Library session is closed") }

            messagesOf(vm) { vm.importFiles(dropped) {} } shouldContainExactly
                listOf("Could not import the files: Library session is closed")
        }

    private fun TestScope.messagesOf(
        vm: AppViewModel,
        action: () -> Unit,
    ): List<String> {
        val messages = collecting(vm.messages.map { it.english() })
        action()
        advanceUntilIdle()
        return messages
    }
}
