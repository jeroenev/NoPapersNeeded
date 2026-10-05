package dev.npn.app

import dev.npn.core.app.ImportOutcome
import dev.npn.core.fs.ExternalSource
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
@OptIn(ExperimentalCoroutinesApi::class)
class DroppedFilesTest : MainDispatcherTest() {
    private val dropped = listOf("a.pdf", "b.pdf").map { ExternalSource(Path.of(it)) to it }

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

            messagesOf(vm) { vm.importFiles(dropped) } shouldContainExactly listOf("1 imported, 1 duplicate")
        }

    @Test
    fun aDropWithNoLibraryOpenSaysToOpenOneFirst() =
        runTest(dispatcher) {
            val vm = AppViewModel(FakeAppEnvironment(backgroundScope))
            advanceUntilIdle()

            messagesOf(vm) { vm.importFiles(dropped) } shouldContainExactly
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

            messagesOf(vm) { vm.importFiles(dropped) } shouldContainExactly
                listOf("Could not import the files: Library session is closed")
        }

    private fun TestScope.messagesOf(
        vm: AppViewModel,
        action: () -> Unit,
    ): List<String> {
        val messages = mutableListOf<String>()
        val collector = launch { vm.messages.collect { messages += it.english() } }
        action()
        advanceUntilIdle()
        collector.cancel()
        return messages
    }
}
