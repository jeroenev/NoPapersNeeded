package dev.npn.app.ui.ai

import dev.npn.core.ai.AiChatEvent
import dev.npn.core.ai.AiChatHistoryItem
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * Pure fold tests for [ChatTranscript]: no coroutines, no dispatcher, no fakes — just events folded
 * into a value. These own the fold rules; [AiChatViewModelTest] only covers the coroutine plumbing.
 */
class ChatTranscriptTest {
    /** Fold a sequence of events into an EMPTY transcript. */
    private fun fold(vararg events: AiChatEvent): ChatTranscript =
        events.fold(ChatTranscript.EMPTY) { acc, event -> acc + event }

    @Test
    fun deltasGrowTheTrailingStreamingAssistantTurn() {
        val transcript =
            fold(
                AiChatEvent.TextDelta("Hel"),
                AiChatEvent.TextDelta("lo"),
                AiChatEvent.Done("Hello"),
            )

        transcript.items shouldHaveSize 1
        val assistant = transcript.items[0].shouldBeInstanceOf<TranscriptItem.AssistantTurn>()
        assistant.text shouldBe "Hello"
        assistant.streaming shouldBe false
    }

    @Test
    fun toolEventsCreateAndCompleteToolItems() {
        val transcript =
            fold(
                AiChatEvent.ToolCallStarted("search_documents", "{\"query\":\"tax\"}"),
                AiChatEvent.ToolCallFinished("search_documents", "{\"query\":\"tax\"}", "hit1"),
                AiChatEvent.TextDelta("Found it."),
                AiChatEvent.Done("Found it."),
            )

        transcript.items shouldHaveSize 2
        val tool = transcript.items[0].shouldBeInstanceOf<TranscriptItem.ToolUse>()
        tool.name shouldBe "search_documents"
        tool.result shouldBe "hit1"
        tool.running shouldBe false
        val assistant = transcript.items[1].shouldBeInstanceOf<TranscriptItem.AssistantTurn>()
        assistant.text shouldBe "Found it."
        assistant.streaming shouldBe false
    }

    @Test
    fun persistedHistoryRestoresToolItemsWithoutBlankUserTurns() {
        val transcript =
            ChatTranscript.fromPersistedHistory(
                listOf(
                    AiChatHistoryItem.UserTurn("find tax"),
                    AiChatHistoryItem.ToolUse("search_documents", "{}", "found one"),
                ),
            )

        transcript.items shouldContainExactly
            listOf(
                TranscriptItem.UserTurn(0, "find tax"),
                TranscriptItem.ToolUse(1, "search_documents", "{}", "found one", running = false),
            )
    }

    /** Ids key the list rows: an item keeps its id as it streams and completes, and none repeats. */
    @Test
    fun everyItemKeepsAnIdNoOtherItemHas() {
        val transcript =
            ChatTranscript
                .fromPersistedHistory(listOf(AiChatHistoryItem.UserTurn("earlier")))
                .plusUser("find tax")
                .plus(AiChatEvent.TextDelta("Let me "))
                .plus(AiChatEvent.TextDelta("search"))
                .plus(AiChatEvent.ToolCallStarted("search_documents", "{}"))
                .plus(AiChatEvent.ToolCallFinished("search_documents", "{}", "hit"))
                .plus(AiChatEvent.TextDelta("Found it"))
                .plus(AiChatEvent.Done("Let me searchFound it"))

        transcript.items.map { it.id } shouldContainExactly listOf(0L, 1L, 2L, 3L, 4L)

        val afterClear = transcript.cleared().plusUser("again")
        afterClear.items.single().id shouldBe 5L
    }

    @Test
    fun aToolCallMidNarrationEndsTheCurrentTurnIntoTwoDistinctBubbles() {
        val transcript =
            fold(
                AiChatEvent.TextDelta("Let me search"),
                AiChatEvent.ToolCallStarted("search_documents", "{\"query\":\"tax\"}"),
                AiChatEvent.ToolCallFinished("search_documents", "{\"query\":\"tax\"}", "hit"),
                AiChatEvent.TextDelta("Found it."),
                AiChatEvent.Done("Let me searchFound it."),
            )

        // assistant("Let me search"), tool, assistant("Found it.") — no duplicated text, and the
        // run's concatenated fullText never overwrites the trailing turn.
        transcript.items shouldHaveSize 3
        val first = transcript.items[0].shouldBeInstanceOf<TranscriptItem.AssistantTurn>()
        first.text shouldBe "Let me search"
        first.streaming shouldBe false
        transcript.items[1].shouldBeInstanceOf<TranscriptItem.ToolUse>()
        val second = transcript.items[2].shouldBeInstanceOf<TranscriptItem.AssistantTurn>()
        second.text shouldBe "Found it."
        second.streaming shouldBe false
    }

    @Test
    fun multipleToolsInterleaveInOrder() {
        val transcript =
            fold(
                AiChatEvent.ToolCallStarted("list_tags", "{}"),
                AiChatEvent.ToolCallFinished("list_tags", "{}", "tags"),
                AiChatEvent.ToolCallStarted("search_documents", "{}"),
                AiChatEvent.ToolCallFinished("search_documents", "{}", "hits"),
                AiChatEvent.TextDelta("done"),
                AiChatEvent.Done("done"),
            )

        transcript.items shouldHaveSize 3
        val first = transcript.items[0].shouldBeInstanceOf<TranscriptItem.ToolUse>()
        first.name shouldBe "list_tags"
        first.result shouldBe "tags"
        first.running shouldBe false
        val second = transcript.items[1].shouldBeInstanceOf<TranscriptItem.ToolUse>()
        second.name shouldBe "search_documents"
        second.result shouldBe "hits"
        second.running shouldBe false
        transcript.items[2].shouldBeInstanceOf<TranscriptItem.AssistantTurn>().text shouldBe "done"
    }

    @Test
    fun toolFinishMatchesLastRunningToolOfThatName() {
        val transcript =
            fold(
                AiChatEvent.ToolCallStarted("search_documents", "{\"q\":\"a\"}"),
                AiChatEvent.ToolCallStarted("search_documents", "{\"q\":\"b\"}"),
                AiChatEvent.ToolCallFinished("search_documents", "{}", "second"),
            )

        transcript.items shouldHaveSize 2
        val first = transcript.items[0].shouldBeInstanceOf<TranscriptItem.ToolUse>()
        first.running shouldBe true
        first.result shouldBe ""
        val second = transcript.items[1].shouldBeInstanceOf<TranscriptItem.ToolUse>()
        second.running shouldBe false
        second.result shouldBe "second"
    }

    @Test
    fun toolFinishWithNoMatchingRunningToolIsANoOp() {
        val transcript =
            fold(
                AiChatEvent.ToolCallStarted("list_tags", "{}"),
                AiChatEvent.ToolCallFinished("list_tags", "{}", "done"),
                AiChatEvent.ToolCallFinished("list_tags", "{}", "again"),
            )

        transcript.items shouldHaveSize 1
        val tool = transcript.items[0].shouldBeInstanceOf<TranscriptItem.ToolUse>()
        tool.result shouldBe "done"
        tool.running shouldBe false
    }

    @Test
    fun doneWithPriorDeltasFinalisesTheTrailingTurn() {
        val transcript = fold(AiChatEvent.TextDelta("hi"), AiChatEvent.Done("hi"))

        transcript.items shouldHaveSize 1
        val assistant = transcript.items[0].shouldBeInstanceOf<TranscriptItem.AssistantTurn>()
        assistant.text shouldBe "hi"
        assistant.streaming shouldBe false
    }

    @Test
    fun doneWithoutPriorDeltasMaterialisesFullTextAsOneFinishedTurn() {
        val transcript = fold(AiChatEvent.Done("full answer"))

        transcript.items shouldHaveSize 1
        val assistant = transcript.items[0].shouldBeInstanceOf<TranscriptItem.AssistantTurn>()
        assistant.text shouldBe "full answer"
        assistant.streaming shouldBe false
    }

    @Test
    fun doneAfterAToolWithNoDeltaMaterialisesFullTextAsANewTurn() {
        // The trailing item is a ToolUse, not an AssistantTurn: Done falls back to fullText.
        val transcript =
            fold(
                AiChatEvent.ToolCallStarted("list_tags", "{}"),
                AiChatEvent.ToolCallFinished("list_tags", "{}", "tags"),
                AiChatEvent.Done("the answer"),
            )

        transcript.items shouldHaveSize 2
        transcript.items[0].shouldBeInstanceOf<TranscriptItem.ToolUse>()
        val assistant = transcript.items[1].shouldBeInstanceOf<TranscriptItem.AssistantTurn>()
        assistant.text shouldBe "the answer"
        assistant.streaming shouldBe false
    }

    @Test
    fun docRefsAreExtractedPerBubbleFromEachTurnsOwnText() {
        val hexA = "a".repeat(HEX_DOC_ID_LEN)
        val hexB = "b".repeat(HEX_DOC_ID_LEN)
        val transcript =
            fold(
                AiChatEvent.TextDelta("See [doc:$hexA]"),
                AiChatEvent.ToolCallStarted("search_documents", "{}"),
                AiChatEvent.ToolCallFinished("search_documents", "{}", "hit"),
                AiChatEvent.TextDelta("Also [doc:$hexB]"),
                AiChatEvent.Done("See [doc:$hexA]Also [doc:$hexB]"),
            )

        val first = transcript.items[0].shouldBeInstanceOf<TranscriptItem.AssistantTurn>()
        // Each bubble carries only its OWN docRefs, not the whole run's.
        first.docRefs shouldContainExactly listOf(hexA)
        val second = transcript.items[2].shouldBeInstanceOf<TranscriptItem.AssistantTurn>()
        second.docRefs shouldContainExactly listOf(hexB)
    }

    @Test
    fun doneExtractsDocRefsForTheNonStreamingFallbackTurn() {
        val hex = "a".repeat(HEX_DOC_ID_LEN)
        val transcript = fold(AiChatEvent.Done("See [doc:$hex] for details."))

        val assistant = transcript.items[0].shouldBeInstanceOf<TranscriptItem.AssistantTurn>()
        assistant.docRefs shouldContainExactly listOf(hex)
    }

    @Test
    fun finalizedKeepsPartialStreamingTextAndExtractsDocRefs() {
        val hex = "a".repeat(HEX_DOC_ID_LEN)
        val partial =
            ChatTranscript.EMPTY +
                AiChatEvent.TextDelta("partial [doc:$hex]")

        val finalized = partial.finalized()

        val assistant = finalized.items.last().shouldBeInstanceOf<TranscriptItem.AssistantTurn>()
        assistant.text shouldBe "partial [doc:$hex]"
        assistant.streaming shouldBe false
        assistant.docRefs shouldContainExactly listOf(hex)
    }

    @Test
    fun finalizedIsANoOpWhenTrailingItemIsNotAStreamingAssistantTurn() {
        val transcript =
            fold(
                AiChatEvent.ToolCallStarted("list_tags", "{}"),
                AiChatEvent.ToolCallFinished("list_tags", "{}", "tags"),
            )

        transcript.finalized() shouldBe transcript
    }

    @Test
    fun plusUserAppendsAUserTurn() {
        val transcript = ChatTranscript.EMPTY.plusUser("hello")

        transcript.items shouldHaveSize 1
        transcript.items[0].shouldBeInstanceOf<TranscriptItem.UserTurn>().text shouldBe "hello"
    }

    @Test
    fun hasVisibleContentTrueForAnyToolRow() {
        val transcript = ChatTranscript.EMPTY + AiChatEvent.ToolCallStarted("list_tags", "{}")

        transcript.hasVisibleContent.shouldBeTrue()
    }

    @Test
    fun hasVisibleContentTrueForNonBlankAssistantText() {
        val transcript = fold(AiChatEvent.Done("answer"))

        transcript.hasVisibleContent.shouldBeTrue()
    }

    @Test
    fun hasVisibleContentFalseForOnlyUserTurnsOrBlankAnswers() {
        ChatTranscript.EMPTY
            .plusUser("q")
            .hasVisibleContent
            .shouldBeFalse()
        fold(AiChatEvent.Done("")).hasVisibleContent.shouldBeFalse()
    }

    private companion object {
        const val HEX_DOC_ID_LEN = 64
    }
}
