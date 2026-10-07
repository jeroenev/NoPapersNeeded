package dev.npn.app

import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.UiText
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_save_setting_failed
import kotlin.test.Test

/** The one message path every screen's viewmodel reports through. */
@OptIn(ExperimentalCoroutinesApi::class)
class MessagesViewModelTest : MainDispatcherTest() {
    private class Messages : MessagesViewModel() {
        fun say(vararg texts: String) = texts.forEach { message(UiText.Raw(it)) }

        fun fail() = launchReporting(Res.string.common_save_setting_failed) { error("disk full") }
    }

    @Test
    fun messagesSentTogetherAreAllDeliveredInOrder() =
        runTest(dispatcher) {
            val model = Messages()
            val shown = mutableListOf<UiText>()
            val collector = launch { model.messages.collect { shown += it.text } }
            runCurrent()

            model.say("first", "second")
            advanceUntilIdle()
            collector.cancel()

            shown shouldContainExactly listOf(UiText.Raw("first"), UiText.Raw("second"))
        }

    @Test
    fun aMessageSentWhileNothingCollectsWaitsForTheNextCollector() =
        runTest(dispatcher) {
            val model = Messages()
            model.say("while away")
            model.fail()
            advanceUntilIdle()

            val shown = mutableListOf<UiText>()
            val collector = launch { model.messages.collect { shown += it.text } }
            advanceUntilIdle()
            collector.cancel()

            shown shouldContainExactly
                listOf(UiText.Raw("while away"), failure(Res.string.common_save_setting_failed, "disk full"))
        }

    /** A bulk action reporting per document while no screen collects loses none of its messages. */
    @Test
    fun everyMessageSentWhileNothingCollectsArrivesInOrder() =
        runTest(dispatcher) {
            val model = Messages()
            val sent = List(100) { "message $it" }
            model.say(*sent.toTypedArray())

            val shown = mutableListOf<UiText>()
            val collector = launch { model.messages.collect { shown += it.text } }
            advanceUntilIdle()
            collector.cancel()

            shown shouldContainExactly sent.map(UiText::Raw)
        }
}
