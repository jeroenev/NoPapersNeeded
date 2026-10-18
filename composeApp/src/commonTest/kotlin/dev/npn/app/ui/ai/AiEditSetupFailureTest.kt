package dev.npn.app.ui.ai

import dev.npn.app.FakeLibrarySession
import dev.npn.app.MainDispatcherTest
import dev.npn.app.collecting
import dev.npn.app.failure
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_edit_setup_load_failed
import kotlin.test.Test

class AiEditSetupFailureTest : MainDispatcherTest() {
    @Test
    fun aSetupReadThatFailsIsReportedAndFallsBackToDefaults() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply { savedViews.failObserve = IllegalStateException("database locked") }
            val model = vm(session = session)
            collecting(model.ui)
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()

            model.ui.value.savedViews shouldBe emptyList()
            messages shouldContainExactly listOf(failure(Res.string.ai_edit_setup_load_failed, "database locked"))
        }
}
