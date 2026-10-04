package dev.npn.app

import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.UiAction
import dev.npn.app.ui.common.UiText
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** The one message path every screen's viewmodel reports through. */
@OptIn(ExperimentalCoroutinesApi::class)
class MessagesViewModelTest : MainDispatcherTest() {
    private class Messages : MessagesViewModel() {
        fun say(vararg texts: String) = texts.forEach { message(UiText.Dynamic(it)) }

        fun fail() = launchReporting(UiAction.UPDATE) { error("disk full") }
    }

    @Test
    fun messagesSentTogetherAreAllDeliveredInOrder() =
        runTest(dispatcher) {
            val model = Messages()
            val shown = mutableListOf<String>()
            val collector = launch { model.messages.collect { shown += it.resolveEnglish() } }
            runCurrent()

            model.say("first", "second")
            advanceUntilIdle()
            collector.cancel()

            shown shouldContainExactly listOf("first", "second")
        }

    @Test
    fun aMessageSentWhileNothingCollectsWaitsForTheNextCollector() =
        runTest(dispatcher) {
            val model = Messages()
            model.say("while away")
            model.fail()
            advanceUntilIdle()

            val shown = mutableListOf<String>()
            val collector = launch { model.messages.collect { shown += it.resolveEnglish() } }
            advanceUntilIdle()
            collector.cancel()

            shown shouldContainExactly listOf("while away", "Could not update: disk full")
        }
}
