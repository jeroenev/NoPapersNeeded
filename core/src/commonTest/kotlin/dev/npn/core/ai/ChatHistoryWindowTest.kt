package dev.npn.core.ai

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test

/**
 * The chat memory and each request replay a conversation by its size in characters, cut between
 * whole turns, with old tool results stubbed. A turn here is a question, a tool call and its
 * result, and an answer.
 */
class ChatHistoryWindowTest {
    private val system = Message.System("You answer questions about the library.", RequestMetaInfo.Empty)

    @Test
    fun aConversationThatFitsIsReplayedAsItIs() {
        val conversation = listOf(system) + turn(1, result = "short") + turn(2, result = "short")

        ChatHistoryWindow.window(conversation, maxChars = 10_000, fullToolResultTurns = 2) shouldBe conversation
    }

    @Test
    fun theOldestWholeTurnsGoOnceTheBudgetIsSpentAndTheSystemMessageStays() {
        val conversation = listOf(system) + turn(1) + turn(2) + turn(3)
        val turnChars = TURN_FIXED_CHARS + RESULT_CHARS

        val window = ChatHistoryWindow.window(conversation, maxChars = 2 * turnChars, fullToolResultTurns = 3)

        window.first() shouldBe system
        questions(window) shouldContainExactly listOf("question 2", "question 3")
        // Cut between turns: every result still follows the call it answers.
        window.drop(1).first() shouldBe Message.User("question 2", RequestMetaInfo.Empty)
    }

    @Test
    fun theNewestTurnIsKeptHoweverLong() {
        val conversation = listOf(system) + turn(1) + turn(2, result = "x".repeat(50_000))

        val window = ChatHistoryWindow.window(conversation, maxChars = 1_000, fullToolResultTurns = 1)

        questions(window) shouldContainExactly listOf("question 2")
        results(window).single().output.length shouldBe 50_000
    }

    @Test
    fun toolResultsOlderThanTheLastTurnsAreStubbed() {
        val conversation = listOf(system) + turn(1) + turn(2) + turn(3, result = "ok")

        val window = ChatHistoryWindow.window(conversation, maxChars = 100_000, fullToolResultTurns = 2)

        val (first, second, third) = results(window)
        first.output shouldContain "Earlier search_documents result omitted"
        // The stub keeps the call's id and tool, so the call it answers still pairs up with it.
        first.id shouldBe "call-1"
        first.tool shouldBe "search_documents"
        second.output shouldBe "r".repeat(RESULT_CHARS)
        third.output shouldBe "ok"
        // Everything but the results is replayed as it was.
        questions(window) shouldContainExactly listOf("question 1", "question 2", "question 3")
        window.filterIsInstance<Message.Assistant>().map { it.textContent() } shouldContainExactly
            listOf("", "answer 1", "", "answer 2", "", "answer 3")
    }

    @Test
    fun theBudgetCountsAStubbedResultAtTheSizeOfItsStub() {
        // Turn 1's result alone is over the budget, but only its stub is replayed.
        val conversation = listOf(system) + turn(1, result = "x".repeat(50_000)) + turn(2, result = "ok")

        val window = ChatHistoryWindow.window(conversation, maxChars = 1_000, fullToolResultTurns = 1)

        questions(window) shouldContainExactly listOf("question 1", "question 2")
    }

    @Test
    fun aShortResultIsKeptRatherThanStubbed() {
        val conversation = listOf(system) + turn(1, result = "No documents match.") + turn(2)

        val window = ChatHistoryWindow.window(conversation, maxChars = 100_000, fullToolResultTurns = 1)

        results(window).first().output shouldBe "No documents match."
    }

    @Test
    fun theTailOfATurnAnOlderWindowCutIsDropped() {
        // A history stored by a count-based window can open mid-turn: a result whose call is gone.
        val cut = turn(1).drop(2)
        val conversation = listOf(system) + cut + turn(2)

        val window = ChatHistoryWindow.window(conversation, maxChars = 100_000, fullToolResultTurns = 2)

        window shouldBe listOf(system) + turn(2)
    }

    @Test
    fun theMemoryKeepsEveryToolResultInFull() {
        val conversation = listOf(system) + List(5) { turn(it + 1) }.flatten()

        ChatHistoryWindow.memory.preprocess(conversation) shouldBe conversation
    }

    @Test
    fun aRequestReplaysOnlyTheLatestToolResultsInFull() {
        val conversation = listOf(system) + List(4) { turn(it + 1) }.flatten() + question(5)

        val window = ChatHistoryWindow.forRequest(conversation)

        // The question being answered and the turn before it.
        results(window).map { it.output == "r".repeat(RESULT_CHARS) } shouldContainExactly
            listOf(false, false, false, true)
    }

    @Test
    fun aGrowingTurnKeepsTheEarlierTurnsItStartedWith() {
        // Signed reasoning is bound to every message before it: an earlier turn dropped mid-loop
        // would invalidate the reasoning of the turn being answered.
        val start = listOf(system) + turn(1) + question(2)
        val turns = ChatHistoryWindow.turnsForRequest(start)
        val grown = start + turn(2, result = "x".repeat(ChatHistoryWindow.REQUEST_CHARS)).drop(1)

        ChatHistoryWindow.turnsForRequest(grown) shouldBe 1
        questions(ChatHistoryWindow.forRequest(grown, turns)) shouldContainExactly listOf("question 1", "question 2")
    }

    @Test
    fun onlyTheTurnBeingAnsweredReplaysItsReasoning() {
        val conversation = listOf(system) + withReasoning(turn(1)) + withReasoning(turn(2))

        val window = ChatHistoryWindow.forRequest(conversation, turns = 2)

        val reasoningByTurn =
            window.filterIsInstance<Message.Assistant>().map { m ->
                m.parts.any {
                    it is MessagePart.Reasoning
                }
            }
        reasoningByTurn shouldContainExactly listOf(false, false, true, false)
    }

    @Test
    fun anEarlierAnswerHoldingOnlyReasoningIsNotReplayedEmpty() {
        val reasoningOnly =
            Message.Assistant(
                MessagePart.Reasoning(content = listOf("thinking")),
                ResponseMetaInfo.Empty,
            )
        val conversation = listOf(system) + turn(1) + reasoningOnly + turn(2)

        val window = ChatHistoryWindow.forRequest(conversation, turns = 2)

        window.filterIsInstance<Message.Assistant>().none { it.parts.isEmpty() } shouldBe true
        window.size shouldBe conversation.size - 1
    }

    /** [turn] with a reasoning part before its tool call. */
    private fun withReasoning(turn: List<Message>): List<Message> {
        val call = turn[1] as Message.Assistant
        val reasoning = MessagePart.Reasoning(content = listOf("thinking"), encrypted = "sig")
        return listOf(turn[0], call.copy(parts = listOf(reasoning) + call.parts)) + turn.drop(2)
    }

    private fun question(n: Int): Message.User = Message.User("question $n", RequestMetaInfo.Empty)

    /** Question [n], a `search_documents` call answered with [result], and the answer. */
    private fun turn(
        n: Int,
        result: String = "r".repeat(RESULT_CHARS),
    ): List<Message> =
        listOf(
            question(n),
            Message.Assistant(
                listOf(MessagePart.Tool.Call("call-$n", "search_documents", "{}")),
                ResponseMetaInfo.Empty,
            ),
            Message.User(MessagePart.Tool.Result("call-$n", "search_documents", result), RequestMetaInfo.Empty),
            Message.Assistant("answer $n", ResponseMetaInfo.Empty),
        )

    private fun questions(messages: List<Message>): List<String> =
        messages
            .filterIsInstance<Message.User>()
            .filter { user -> user.parts.none { it is MessagePart.Tool.Result } }
            .map { it.textContent() }

    private fun results(messages: List<Message>): List<MessagePart.Tool.Result> =
        messages.flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Result>()

    private companion object {
        const val RESULT_CHARS = 1_000

        /** "question n" + the call's tool name and args + "answer n". */
        const val TURN_FIXED_CHARS = 10 + 16 + 2 + 8
    }
}
