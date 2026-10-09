package dev.npn.app.ui.ai

import dev.npn.app.FakeLibrarySession
import dev.npn.app.FakeSettingsRepository
import dev.npn.app.MainDispatcherTest
import dev.npn.app.aiConfiguredSettings
import dev.npn.app.collecting
import dev.npn.app.failure
import dev.npn.app.ui.common.uiText
import dev.npn.core.ai.AiChatEvent
import dev.npn.core.ai.AiChatHistoryItem
import dev.npn.core.ai.AiChatSession
import dev.npn.core.ai.AiProvider
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_chat_delete_failed
import no_papers_needed.composeapp.generated.resources.common_ai_assistant_empty_answer
import no_papers_needed.composeapp.generated.resources.common_ai_assistant_unreachable
import kotlin.test.Test

/**
 * Coroutine-plumbing tests only: send gating, busy transitions, stop/clear, failure handling, the
 * config probe, history capture, and the blank-answer message. The fold rules themselves live in
 * [ChatTranscriptTest] — do not re-assert them here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiChatViewModelTest : MainDispatcherTest() {
    private fun vm(
        session: FakeLibrarySession = FakeLibrarySession(),
        settings: FakeSettingsRepository = aiConfiguredSettings(),
    ) = AiChatViewModel(session, settings)

    /** An open chat follows Settings: configuring AI there, or turning it off, applies at once. */
    @Test
    fun configuredFollowsSettings() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            val model = vm(settings = settings)
            collecting(model.ui)

            model.ui.value.configured shouldBe null
            advanceUntilIdle()
            model.ui.value.configured shouldBe false

            settings.edit { it.copy(aiEnabled = true, aiProvider = AiProvider.OLLAMA, aiModel = "qwen3:8b") }
            advanceUntilIdle()
            model.ui.value.configured shouldBe true
        }

    @Test
    fun aChatThatCannotBeDeletedIsReportedNotCrashed() =
        runTest(dispatcher) {
            val saved = AiChatSession("saved", "Previous chat", 2L)
            val session =
                FakeLibrarySession().apply {
                    aiChat.savedChatSessions = listOf(saved)
                    aiChat.failDeleteChatSession = { error("store locked") }
                }
            val model = vm(session)
            collecting(model.ui)
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()

            model.deleteSession(saved.id)
            advanceUntilIdle()

            messages shouldContainExactly listOf(failure(Res.string.ai_chat_delete_failed, "store locked"))
        }

    @Test
    fun startupOpensNewChatInsteadOfNewestSavedSession() =
        runTest(dispatcher) {
            val saved = AiChatSession("saved", "Previous chat", 2L)
            val fresh = AiChatSession("fresh", null, 3L)
            val session =
                FakeLibrarySession().apply {
                    aiChat.savedChatSessions = listOf(saved)
                    aiChat.createdChatSession = fresh
                    aiChat.chatTranscripts =
                        mapOf(
                            saved.id to listOf(AiChatHistoryItem.UserTurn("old question")),
                        )
                }
            val model = vm(session)
            collecting(model.ui)
            advanceUntilIdle()

            model.ui.value.currentSessionId shouldBe fresh.id
            model.ui.value.transcript.items shouldBe emptyList()
            model.ui.value.sessions
                .map { it.id } shouldContainExactly listOf(fresh.id, saved.id)
        }

    /** The open chat is stored only once asked in; deleting another one must not drop it from the list. */
    @Test
    fun deletingAnotherChatKeepsTheOpenUnaskedOneListed() =
        runTest(dispatcher) {
            val saved = AiChatSession("saved", "Previous chat", 2L)
            val other = AiChatSession("other", "Older chat", 1L)
            val fresh = AiChatSession("fresh", null, 3L)
            val session =
                FakeLibrarySession().apply {
                    aiChat.savedChatSessions = listOf(saved, other)
                    aiChat.createdChatSession = fresh
                }
            val model = vm(session)
            collecting(model.ui)
            advanceUntilIdle()

            model.deleteSession(saved.id)
            advanceUntilIdle()
            model.ui.value.currentSessionId shouldBe fresh.id
            model.ui.value.sessions
                .map { it.id } shouldContainExactly listOf(fresh.id, other.id)

            model.send("q")
            advanceUntilIdle()
            model.currentTitle() shouldBe "q"

            model.switchSession(other.id)
            advanceUntilIdle()
            model.ui.value.currentSessionId shouldBe other.id
            model.switchSession(fresh.id)
            advanceUntilIdle()
            model.ui.value.currentSessionId shouldBe fresh.id
        }

    /** Untitled is null, named by the screen in the user's language; the first question titles it. */
    @Test
    fun aNewChatIsUntitledUntilItsFirstQuestionAndAgainAfterClear() =
        runTest(dispatcher) {
            val model = vm()
            collecting(model.ui)
            advanceUntilIdle()
            model.currentTitle() shouldBe null

            model.send("  Which   invoices are overdue?")
            advanceUntilIdle()
            model.currentTitle() shouldBe "Which invoices are overdue?"

            model.clear()
            advanceUntilIdle()
            model.currentTitle() shouldBe null
        }

    private fun AiChatViewModel.currentTitle(): String? =
        ui.value.sessions
            .single { it.id == ui.value.currentSessionId }
            .title

    @Test
    fun sendAppendsUserTurnAndClearsBusy() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { aiChat.askResponses = listOf("hello back") }
            val model = vm(session)
            collecting(model.ui)
            advanceUntilIdle()

            model.send("hi")
            advanceUntilIdle()

            val items = model.ui.value.transcript.items
            items shouldHaveSize 2
            items[0].shouldBeInstanceOf<TranscriptItem.UserTurn>().text shouldBe "hi"
            model.ui.value.busy shouldBe false
        }

    @Test
    fun busyIsTrueWhileStreamingAndFalseWhenDone() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    aiChat.askDelayMillis = 1_000
                    aiChat.askScripts =
                        listOf(
                            listOf(AiChatEvent.TextDelta("partial"), AiChatEvent.Done("partial")),
                        )
                }
            val model = vm(session)
            collecting(model.ui)
            advanceUntilIdle()

            model.send("hi")
            advanceTimeBy(1_500)
            runCurrent()
            model.ui.value.busy shouldBe true

            advanceUntilIdle()
            model.ui.value.busy shouldBe false
        }

    @Test
    fun sendPassesOnlyTheQuestionToTheSession() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    aiChat.askScripts =
                        listOf(
                            listOf(AiChatEvent.TextDelta("a1"), AiChatEvent.Done("a1")),
                            listOf(AiChatEvent.TextDelta("a2"), AiChatEvent.Done("a2")),
                        )
                }
            val model = vm(session)
            collecting(model.ui)
            advanceUntilIdle()

            model.send("q1")
            advanceUntilIdle()
            model.send("q2")
            advanceUntilIdle()

            // Both asks go to the same session; history is the session's own persisted
            // transcript, so the viewmodel passes nothing but the question.
            val sessionId = model.ui.value.currentSessionId
            session.aiChat.asked
                .map { it.first }
                .distinct() shouldContainExactly listOf(sessionId)
            session.aiChat.asked.map { it.second } shouldContainExactly listOf("q1", "q2")
        }

    @Test
    fun failedAskKeepsUserTurnAndSurfacesMessage() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { aiChat.failAsk = { error("connection refused") } }
            val model = vm(session)
            collecting(model.ui)
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()

            model.send("hi")
            advanceUntilIdle()

            val items = model.ui.value.transcript.items
            items shouldHaveSize 1
            items[0].shouldBeInstanceOf<TranscriptItem.UserTurn>()
            model.ui.value.busy shouldBe false
            messages shouldContainExactly
                listOf(failure(Res.string.common_ai_assistant_unreachable, "connection refused"))
        }

    @Test
    fun midStreamFailureKeepsPartialTextAndSurfacesMessage() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    aiChat.askScripts =
                        listOf(
                            listOf(
                                AiChatEvent.TextDelta("partial"),
                                AiChatEvent.TextDelta(" more"),
                                AiChatEvent.Done("partial more"),
                            ),
                        )
                    aiChat.failAskAfter = 1
                }
            val model = vm(session)
            collecting(model.ui)
            val messages = collecting(model.messages.map { it.text })
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
            messages shouldContainExactly
                listOf(failure(Res.string.common_ai_assistant_unreachable, "stream interrupted"))
        }

    @Test
    fun emptyAnswerRunSurfacesMessageAndClearsBusy() =
        runTest(dispatcher) {
            // A run that ends with only Done("") produced no visible content (no text bubble, no tool
            // rows). Busy must clear and the user must get a one-shot message so the run leaves a trace.
            val session =
                FakeLibrarySession().apply {
                    aiChat.askScripts = listOf(listOf(AiChatEvent.Done("")))
                }
            val model = vm(session)
            collecting(model.ui)
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()

            model.send("hi")
            advanceUntilIdle()

            model.ui.value.busy shouldBe false
            messages shouldContainExactly listOf(uiText(Res.string.common_ai_assistant_empty_answer))
        }

    @Test
    fun stopCancelsAndFinalisesPartialTurn() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    aiChat.askDelayMillis = 1_000
                    aiChat.askScripts =
                        listOf(
                            listOf(
                                AiChatEvent.TextDelta("partial"),
                                AiChatEvent.TextDelta(" more"),
                                AiChatEvent.Done("partial more"),
                            ),
                        )
                }
            val model = vm(session)
            collecting(model.ui)
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
        }

    @Test
    fun blankOrBusySendIsIgnored() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session)
            collecting(model.ui)
            advanceUntilIdle()

            model.send("   ")
            advanceUntilIdle()

            model.ui.value.transcript.items shouldBe emptyList()
            session.aiChat.asked shouldBe emptyList()
        }

    @Test
    fun clearResetsTranscript() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { aiChat.askResponses = listOf("hi") }
            val model = vm(session)
            collecting(model.ui)
            advanceUntilIdle()

            model.send("hello")
            advanceUntilIdle()
            model.ui.value.transcript.items shouldHaveSize 2

            model.clear()
            model.ui.value.transcript.items shouldBe emptyList()
        }
}
