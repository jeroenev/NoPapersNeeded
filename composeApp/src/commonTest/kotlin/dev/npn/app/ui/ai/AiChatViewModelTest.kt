package dev.npn.app.ui.ai

import dev.npn.app.FakeLibrarySession
import dev.npn.app.MainDispatcherTest
import dev.npn.core.ai.AiRole
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
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
        turns[0].role shouldBe AiRole.USER
        turns[0].text shouldBe "hi"
        turns[1].role shouldBe AiRole.ASSISTANT
        turns[1].text shouldBe "hello back"
        model.ui.value.busy shouldBe false
        job.cancel()
    }

    @Test
    fun secondSendPassesPriorTurnsAsHistory() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply { ai.askResponses = listOf("a1", "a2") }
        val model = vm(session)
        val job = launch { model.ui.collect {} }
        advanceUntilIdle()

        model.send("q1")
        advanceUntilIdle()
        model.send("q2")
        advanceUntilIdle()

        // First call: empty history, question q1.
        session.ai.asked[0].first shouldBe emptyList()
        session.ai.asked[0].second shouldBe "q1"
        // Second call: history holds the first user+assistant turn, question q2 (not in history).
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
    fun failedAskSurfacesMessageAndKeepsUserTurn() = runTest(dispatcher) {
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
        turns[0].role shouldBe AiRole.USER
        turns[0].text shouldBe "hi"
        model.ui.value.busy shouldBe false
        messages shouldContainExactly listOf("Could not reach the AI assistant: connection refused")
        jobs.forEach { it.cancel() }
    }

    @Test
    fun assistantTurnExtractsDocRefs() = runTest(dispatcher) {
        val hex = "a".repeat(HEX_DOC_ID_LEN)
        val session = FakeLibrarySession().apply {
            ai.askResponses = listOf("See [doc:$hex] for details.")
        }
        val model = vm(session)
        val job = launch { model.ui.collect {} }
        advanceUntilIdle()

        model.send("where?")
        advanceUntilIdle()

        val assistant = model.ui.value.turns.last()
        assistant.docRefs shouldContainExactly listOf(hex)
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
