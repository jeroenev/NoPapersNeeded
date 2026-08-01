package dev.npn.app.ui.ai

import dev.npn.app.FakeLibrarySession
import dev.npn.app.MainDispatcherTest
import dev.npn.core.ai.AiChatEvent
import dev.npn.core.ai.AiRole
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiChatViewModelTest : MainDispatcherTest() {

    private fun vm(session: FakeLibrarySession = FakeLibrarySession()) = AiChatViewModel(session)

    @Test
    fun sendAppendsUserAndAssistantTurns() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply { ai.askResponses = listOf("hello back") }
        val model = vm(session)
        val job = launch { model.ui.collect {} }
        advanceUntilIdle()

        model.send("hi")
        advanceUntilIdle()

        val turns = model.ui.value.turns
        turns shouldHaveSize 2
        val user = turns[0].shouldBeInstanceOf<TranscriptItem.UserTurn>()
        user.text shouldBe "hi"
        val assistant = turns[1].shouldBeInstanceOf<TranscriptItem.AssistantTurn>()
        assistant.text shouldBe "hello back"
        assistant.streaming shouldBe false
        model.ui.value.busy shouldBe false
        job.cancel()
    }

    @Test
    fun deltasGrowTrailingAssistantTurn() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply {
            ai.askScripts = listOf(
                listOf(
                    AiChatEvent.TextDelta("Hel"),
                    AiChatEvent.TextDelta("lo"),
                    AiChatEvent.Done("Hello"),
                ),
            )
        }
        val model = vm(session)
        val job = launch { model.ui.collect {} }
        advanceUntilIdle()

        model.send("hi")
        advanceUntilIdle()

        val turns = model.ui.value.turns
        turns shouldHaveSize 2
        val assistant = turns[1].shouldBeInstanceOf<TranscriptItem.AssistantTurn>()
        assistant.text shouldBe "Hello"
        assistant.streaming shouldBe false
        job.cancel()
    }

    @Test
    fun toolEventsCreateAndCompleteToolItems() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply {
            ai.askScripts = listOf(
                listOf(
                    AiChatEvent.ToolCallStarted("search_documents", "{\"query\":\"tax\"}"),
                    AiChatEvent.ToolCallFinished("search_documents", "{\"query\":\"tax\"}", "hit1"),
                    AiChatEvent.TextDelta("Found it."),
                    AiChatEvent.Done("Found it."),
                ),
            )
        }
        val model = vm(session)
        val job = launch { model.ui.collect {} }
        advanceUntilIdle()

        model.send("find tax")
        advanceUntilIdle()

        val turns = model.ui.value.turns
        turns shouldHaveSize 3
        turns[0].shouldBeInstanceOf<TranscriptItem.UserTurn>()
        val tool = turns[1].shouldBeInstanceOf<TranscriptItem.ToolUse>()
        tool.name shouldBe "search_documents"
        tool.result shouldBe "hit1"
        tool.running shouldBe false
        val assistant = turns[2].shouldBeInstanceOf<TranscriptItem.AssistantTurn>()
        assistant.text shouldBe "Found it."
        job.cancel()
    }

    @Test
    fun preToolAndPostToolTextBecomeTwoDistinctFinalisedTurns() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply {
            ai.askScripts = listOf(
                listOf(
                    AiChatEvent.TextDelta("Let me search"),
                    AiChatEvent.ToolCallStarted("search_documents", "{\"query\":\"tax\"}"),
                    AiChatEvent.ToolCallFinished("search_documents", "{\"query\":\"tax\"}", "hit"),
                    AiChatEvent.TextDelta("Found it."),
                    AiChatEvent.Done("Let me searchFound it."),
                ),
            )
        }
        val model = vm(session)
        val job = launch { model.ui.collect {} }
        advanceUntilIdle()

        model.send("find tax")
        advanceUntilIdle()

        val turns = model.ui.value.turns
        // user, assistant("Let me search"), tool, assistant("Found it.") — no duplicated text.
        turns shouldHaveSize 4
        val first = turns[1].shouldBeInstanceOf<TranscriptItem.AssistantTurn>()
        first.text shouldBe "Let me search"
        first.streaming shouldBe false
        turns[2].shouldBeInstanceOf<TranscriptItem.ToolUse>()
        val second = turns[3].shouldBeInstanceOf<TranscriptItem.AssistantTurn>()
        second.text shouldBe "Found it."
        second.streaming shouldBe false
        job.cancel()
    }

    @Test
    fun historyAfterTextToolTextHasNoDuplication() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply {
            ai.askScripts = listOf(
                listOf(
                    AiChatEvent.TextDelta("Let me search"),
                    AiChatEvent.ToolCallStarted("search_documents", "{}"),
                    AiChatEvent.ToolCallFinished("search_documents", "{}", "hit"),
                    AiChatEvent.TextDelta("Found it."),
                    AiChatEvent.Done("Let me searchFound it."),
                ),
                listOf(AiChatEvent.TextDelta("ok"), AiChatEvent.Done("ok")),
            )
        }
        val model = vm(session)
        val job = launch { model.ui.collect {} }
        advanceUntilIdle()

        model.send("q1")
        advanceUntilIdle()
        model.send("q2")
        advanceUntilIdle()

        val secondHistory = session.ai.asked[1].first
        // user q1 + the two distinct assistant turns; no text repeated, no concatenated fullText.
        secondHistory.map { it.role to it.text } shouldContainExactly listOf(
            AiRole.USER to "q1",
            AiRole.ASSISTANT to "Let me search",
            AiRole.ASSISTANT to "Found it.",
        )
        job.cancel()
    }

    @Test
    fun secondSendPassesPriorTextTurnsAsHistoryExcludingTools() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply {
            ai.askScripts = listOf(
                listOf(
                    AiChatEvent.ToolCallStarted("list_tags", "{}"),
                    AiChatEvent.ToolCallFinished("list_tags", "{}", "tags"),
                    AiChatEvent.TextDelta("a1"),
                    AiChatEvent.Done("a1"),
                ),
                listOf(AiChatEvent.TextDelta("a2"), AiChatEvent.Done("a2")),
            )
        }
        val model = vm(session)
        val job = launch { model.ui.collect {} }
        advanceUntilIdle()

        model.send("q1")
        advanceUntilIdle()
        model.send("q2")
        advanceUntilIdle()

        session.ai.asked[0].first shouldBe emptyList()
        session.ai.asked[0].second shouldBe "q1"
        // Tool items are excluded from history: only user q1 + assistant a1.
        val secondHistory = session.ai.asked[1].first
        secondHistory shouldHaveSize 2
        secondHistory[0].role shouldBe AiRole.USER
        secondHistory[0].text shouldBe "q1"
        secondHistory[1].role shouldBe AiRole.ASSISTANT
        secondHistory[1].text shouldBe "a1"
        session.ai.asked[1].second shouldBe "q2"
        job.cancel()
    }

    @Test
    fun failedAskKeepsUserTurnAndSurfacesMessage() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply { ai.failAsk = { error("connection refused") } }
        val model = vm(session)
        val messages = mutableListOf<String>()
        val jobs = listOf(
            launch { model.ui.collect {} },
            launch { model.messages.collect { messages += it } },
        )
        advanceUntilIdle()

        model.send("hi")
        advanceUntilIdle()

        val turns = model.ui.value.turns
        turns shouldHaveSize 1
        turns[0].shouldBeInstanceOf<TranscriptItem.UserTurn>()
        model.ui.value.busy shouldBe false
        messages shouldContainExactly listOf("Could not reach the AI assistant: connection refused")
        jobs.forEach { it.cancel() }
    }

    @Test
    fun midStreamFailureKeepsPartialTextAndSurfacesMessage() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply {
            ai.askScripts = listOf(
                listOf(
                    AiChatEvent.TextDelta("partial"),
                    AiChatEvent.TextDelta(" more"),
                    AiChatEvent.Done("partial more"),
                ),
            )
            ai.failAskAfter = 1
        }
        val model = vm(session)
        val messages = mutableListOf<String>()
        val jobs = listOf(
            launch { model.ui.collect {} },
            launch { model.messages.collect { messages += it } },
        )
        advanceUntilIdle()

        model.send("hi")
        advanceUntilIdle()

        val assistant = model.ui.value.turns.last().shouldBeInstanceOf<TranscriptItem.AssistantTurn>()
        assistant.text shouldBe "partial"
        assistant.streaming shouldBe false
        model.ui.value.busy shouldBe false
        messages shouldContainExactly listOf("Could not reach the AI assistant: stream interrupted")
        jobs.forEach { it.cancel() }
    }

    @Test
    fun emptyAnswerRunSurfacesMessageAndClearsBusy() = runTest(dispatcher) {
        // A run that ends with only Done("") produced no visible content (no text bubble, no tool
        // rows). Busy must clear and the user must get a one-shot message so the run leaves a trace.
        val session = FakeLibrarySession().apply {
            ai.askScripts = listOf(listOf(AiChatEvent.Done("")))
        }
        val model = vm(session)
        val messages = mutableListOf<String>()
        val jobs = listOf(
            launch { model.ui.collect {} },
            launch { model.messages.collect { messages += it } },
        )
        advanceUntilIdle()

        model.send("hi")
        advanceUntilIdle()

        model.ui.value.busy shouldBe false
        messages shouldContainExactly listOf("The assistant returned an empty answer.")
        jobs.forEach { it.cancel() }
    }

    @Test
    fun assistantTurnExtractsDocRefsOnDone() = runTest(dispatcher) {
        val hex = "a".repeat(HEX_DOC_ID_LEN)
        val session = FakeLibrarySession().apply {
            ai.askResponses = listOf("See [doc:$hex] for details.")
        }
        val model = vm(session)
        val job = launch { model.ui.collect {} }
        advanceUntilIdle()

        model.send("where?")
        advanceUntilIdle()

        val assistant = model.ui.value.turns.last().shouldBeInstanceOf<TranscriptItem.AssistantTurn>()
        assistant.docRefs shouldContainExactly listOf(hex)
        job.cancel()
    }

    @Test
    fun stopCancelsAndFinalisesPartialTurn() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply {
            ai.askDelayMillis = 1_000
            ai.askScripts = listOf(
                listOf(
                    AiChatEvent.TextDelta("partial"),
                    AiChatEvent.TextDelta(" more"),
                    AiChatEvent.Done("partial more"),
                ),
            )
        }
        val model = vm(session)
        val job = launch { model.ui.collect {} }
        advanceUntilIdle()

        model.send("hi")
        // Let the first delay + first delta land, then stop before completion.
        advanceTimeBy(1_500)
        runCurrent()
        model.stop()
        runCurrent()

        val assistant = model.ui.value.turns.last().shouldBeInstanceOf<TranscriptItem.AssistantTurn>()
        assistant.text shouldBe "partial"
        assistant.streaming shouldBe false
        model.ui.value.busy shouldBe false
        job.cancel()
    }

    @Test
    fun blankOrBusySendIsIgnored() = runTest(dispatcher) {
        val session = FakeLibrarySession()
        val model = vm(session)
        val job = launch { model.ui.collect {} }
        advanceUntilIdle()

        model.send("   ")
        advanceUntilIdle()

        model.ui.value.turns shouldBe emptyList()
        session.ai.asked shouldBe emptyList()
        job.cancel()
    }

    @Test
    fun clearResetsTranscript() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply { ai.askResponses = listOf("hi") }
        val model = vm(session)
        val job = launch { model.ui.collect {} }
        advanceUntilIdle()

        model.send("hello")
        advanceUntilIdle()
        model.ui.value.turns shouldHaveSize 2

        model.clear()
        model.ui.value.turns shouldBe emptyList()
        job.cancel()
    }

    private companion object {
        const val HEX_DOC_ID_LEN = 64
    }
}
