package dev.npn.core.ai

import ai.koog.prompt.streaming.StreamFrame
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import kotlin.test.Test

class ToolCallSanitizerTest {
    @Test
    fun blankToolNamesAreNotDispatchable() {
        StreamFrame
            .ToolCallComplete("call-1", "", "{}", null)
            .toUsableToolCall()
            .shouldBeNull()
        StreamFrame
            .ToolCallComplete("call-1", "   ", "{}", null)
            .toUsableToolCall()
            .shouldBeNull()
    }

    @Test
    fun validToolCallsAreTrimmedAndGetFallbackIds() {
        val call =
            StreamFrame
                .ToolCallComplete(null, " search_documents ", "{}", null)
                .toUsableToolCall()
        val other =
            StreamFrame
                .ToolCallComplete(" ", "search_documents", "{}", null)
                .toUsableToolCall()

        call?.name shouldBe "search_documents"
        call?.id shouldStartWith "call_"
        call?.argsJson shouldBe "{}"
        other?.id shouldStartWith "call_"
        call?.id shouldNotBe other?.id
    }
}
