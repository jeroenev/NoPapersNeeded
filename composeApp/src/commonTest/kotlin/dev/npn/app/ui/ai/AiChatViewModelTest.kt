package dev.npn.app.ui.ai

import dev.npn.app.FakeLibrarySession
import dev.npn.app.MainDispatcherTest
import dev.npn.app.ui.common.resolveEnglish
import dev.npn.core.ai.AiChatEvent
import dev.npn.core.ai.AiChatHistoryItem
import dev.npn.core.ai.AiChatSession
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

/**
 * Coroutine-plumbing tests only: send gating, busy transitions, stop/clear, failure handling, the
 * config probe, history capture, and the blank-answer message. The fold rules themselves live in
 * [ChatTranscriptTest] — do not re-assert them here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiChatViewModelTest : MainDispatcherTest() {
    private fun vm(session: FakeLibrarySession = FakeLibrarySession()) = AiChatViewModel(session)

    @Test
    fun configProbePopulatesConfiguredFlag() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { ai.configValue = ai.configValue.copy(enabled = true) }
            val model = vm(session)
            val job = launch { model.ui.collect {} }

            model.ui.value.configured shouldBe null
            advanceUntilIdle()

            model.ui.value.configured shouldBe true
            job.cancel()
        }

    @Test
    fun startupOpensNewChatInsteadOfNewestSavedSession() =
        runTest(dispatcher) {
            val saved = AiChatSession("saved", "Previous chat", 2L)
            val fresh = AiChatSession("fresh", "New chat", 3L)
            val session =
                FakeLibrarySession().apply {
                    ai.savedChatSessions = listOf(saved)
                    ai.createdChatSession = fresh
                    ai.chatTranscripts =
                        mapOf(
                            saved.id to listOf(AiChatHistoryItem.UserTurn("old question")),
                        )
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.ui.value.currentSessionId shouldBe fresh.id
            model.ui.value.transcript.items shouldBe emptyList()
            model.ui.value.sessions
                .map { it.id } shouldContainExactly listOf(fresh.id, saved.id)
            job.cancel()
        }

    @Test
    fun sendAppendsUserTurnAndClearsBusy() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { ai.askResponses = listOf("hello back") }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.send("hi")
            advanceUntilIdle()

            val items = model.ui.value.transcript.items
            items shouldHaveSize 2
            items[0].shouldBeInstanceOf<TranscriptItem.UserTurn>().text shouldBe "hi"
            model.ui.value.busy shouldBe false
            job.cancel()
        }

    @Test
    fun busyIsTrueWhileStreamingAndFalseWhenDone() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    ai.askDelayMillis = 1_000
                    ai.askScripts =
                        listOf(
                            listOf(AiChatEvent.TextDelta("partial"), AiChatEvent.Done("partial")),
                        )
                }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.send("hi")
            advanceTimeBy(1_500)
            runCurrent()
            model.ui.value.busy shouldBe true

            advanceUntilIdle()
            model.ui.value.busy shouldBe false
            job.cancel()
        }

    @Test
    fun sendCapturesHistoryBeforeAppendingTheUserTurn() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    ai.askScripts =
                        listOf(
                            listOf(AiChatEvent.TextDelta("a1"), AiChatEvent.Done("a1")),
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

            // First ask sees no history and the current question is never part of the history.
            session.ai.asked[0].first shouldBe emptyList()
            session.ai.asked[0].second shouldBe "q1"
            // Second ask sees prior user+assistant turns but NOT the just-sent q2.
            session.ai.asked[1]
                .first
                .map { it.role to it.text } shouldContainExactly
                listOf(AiRole.USER to "q1", AiRole.ASSISTANT to "a1")
            session.ai.asked[1].second shouldBe "q2"
            job.cancel()
        }

    @Test
    fun failedAskKeepsUserTurnAndSurfacesMessage() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { ai.failAsk = { error("connection refused") } }
            val model = vm(session)
            val messages = mutableListOf<String>()
            val jobs =
                listOf(
                    launch { model.ui.collect {} },
                    launch { model.messages.collect { messages += it.resolveEnglish() } },
                )
            advanceUntilIdle()

            model.send("hi")
            advanceUntilIdle()

            val items = model.ui.value.transcript.items
            items shouldHaveSize 1
            items[0].shouldBeInstanceOf<TranscriptItem.UserTurn>()
            model.ui.value.busy shouldBe false
            messages shouldContainExactly listOf("Could not reach the AI assistant: connection refused")
            jobs.forEach { it.cancel() }
        }

    @Test
    fun midStreamFailureKeepsPartialTextAndSurfacesMessage() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    ai.askScripts =
                        listOf(
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
            val jobs =
                listOf(
                    launch { model.ui.collect {} },
                    launch { model.messages.collect { messages += it.resolveEnglish() } },
                )
            advanceUntilIdle()

            model.send("hi")
            advanceUntilIdle()

            val assistant =
                model.ui.value.transcript.items
                    .last()
                    .shouldBeInstanceOf<TranscriptItem.AssistantTurn>()
            assistant.text shouldBe "partial"
            assistant.streaming shouldBe false
            model.ui.value.busy shouldBe false
            messages shouldContainExactly listOf("Could not reach the AI assistant: stream interrupted")
            jobs.forEach { it.cancel() }
        }

    @Test
    fun emptyAnswerRunSurfacesMessageAndClearsBusy() =
        runTest(dispatcher) {
            // A run that ends with only Done("") produced no visible content (no text bubble, no tool
            // rows). Busy must clear and the user must get a one-shot message so the run leaves a trace.
            val session =
                FakeLibrarySession().apply {
                    ai.askScripts = listOf(listOf(AiChatEvent.Done("")))
                }
            val model = vm(session)
            val messages = mutableListOf<String>()
            val jobs =
                listOf(
                    launch { model.ui.collect {} },
                    launch { model.messages.collect { messages += it.resolveEnglish() } },
                )
            advanceUntilIdle()

            model.send("hi")
            advanceUntilIdle()

            model.ui.value.busy shouldBe false
            messages shouldContainExactly listOf("The assistant returned an empty answer.")
            jobs.forEach { it.cancel() }
        }

    @Test
    fun stopCancelsAndFinalisesPartialTurn() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    ai.askDelayMillis = 1_000
                    ai.askScripts =
                        listOf(
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

            val assistant =
                model.ui.value.transcript.items
                    .last()
                    .shouldBeInstanceOf<TranscriptItem.AssistantTurn>()
            assistant.text shouldBe "partial"
            assistant.streaming shouldBe false
            model.ui.value.busy shouldBe false
            job.cancel()
        }

    @Test
    fun blankOrBusySendIsIgnored() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.send("   ")
            advanceUntilIdle()

            model.ui.value.transcript.items shouldBe emptyList()
            session.ai.asked shouldBe emptyList()
            job.cancel()
        }

    @Test
    fun clearResetsTranscript() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { ai.askResponses = listOf("hi") }
            val model = vm(session)
            val job = launch { model.ui.collect {} }
            advanceUntilIdle()

            model.send("hello")
            advanceUntilIdle()
            model.ui.value.transcript.items shouldHaveSize 2

            model.clear()
            model.ui.value.transcript.items shouldBe emptyList()
            job.cancel()
        }
}
