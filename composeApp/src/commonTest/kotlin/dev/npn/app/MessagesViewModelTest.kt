package dev.npn.app

import dev.npn.app.ui.common.BulkFailure
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.common.uiText
import dev.npn.core.util.NpnLog
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_restore_documents_failed
import no_papers_needed.composeapp.generated.resources.common_save_setting_failed
import no_papers_needed.composeapp.generated.resources.common_trash_restore_failed
import kotlin.test.Test

/** The one message path every screen's viewmodel reports through. */
class MessagesViewModelTest : MainDispatcherTest() {
    private class Messages : MessagesViewModel() {
        val logged = mutableListOf<Throwable?>()
        val loggedMessages = mutableListOf<String>()
        override val log =
            NpnLog { _, message, error ->
                logged += error
                loggedMessages += message
            }

        fun say(vararg texts: String) = texts.forEach { message(UiText.Raw(it)) }

        fun fail() = launchReporting(Res.string.common_save_setting_failed) { error("disk full") }

        suspend fun each(
            items: List<String>,
            failing: Set<String>,
        ) = forEachReporting(items, Failed) { if (it in failing) error("no $it") }
    }

    /** A bulk action goes on past a failed item, logs it, and hands back what failed for a retry. */
    @Test
    fun aBulkActionReturnsAndLogsWhatFailed() =
        runTest(dispatcher) {
            val model = Messages()
            val shown = collecting(model.messages.map { it.text })

            val failed = model.each(listOf("a", "b", "c"), failing = setOf("a", "c"))

            failed shouldBe setOf("a", "c")
            model.logged.map { it?.message } shouldContainExactly listOf("no a", "no c")
            shown shouldContainExactly listOf(uiText(Res.plurals.common_restore_documents_failed, 3, 2, 3))
        }

    /** A bulk action's items are often user data, such as tag names: the log line names none. */
    @Test
    fun aBulkActionLogsNoItemItIsNotToldIsSafe() =
        runTest(dispatcher) {
            val model = Messages()

            model.each(listOf("Health", "Taxes"), failing = setOf("Health"))

            model.loggedMessages shouldContainExactly listOf("A bulk action failed for <name>")
        }

    @Test
    fun aBulkActionWithOneFailureSaysWhy() =
        runTest(dispatcher) {
            val model = Messages()
            val shown = collecting(model.messages.map { it.text })

            model.each(listOf("a", "b"), failing = setOf("b")) shouldBe setOf("b")

            shown shouldContainExactly listOf(failure(Res.string.common_trash_restore_failed, "no b"))
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

    private companion object {
        val Failed = BulkFailure(Res.string.common_trash_restore_failed, Res.plurals.common_restore_documents_failed)
    }
}
