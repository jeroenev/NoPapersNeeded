package dev.npn.core.ai

import ai.koog.prompt.streaming.StreamFrame
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ToolCallSanitizerTest {
    @Test
    fun blankToolNamesAreNotDispatchable() {
        StreamFrame
            .ToolCallComplete("call-1", "", "{}", null)
            .toUsableToolCall(0)
            .shouldBeNull()
        StreamFrame
            .ToolCallComplete("call-1", "   ", "{}", null)
            .toUsableToolCall(0)
            .shouldBeNull()
    }

    @Test
    fun validToolCallsAreTrimmedAndGetFallbackIds() {
        val call =
            StreamFrame
                .ToolCallComplete(null, " search_documents ", "{}", null)
                .toUsableToolCall(3)

        call?.name shouldBe "search_documents"
        call?.id shouldBe "call_3"
        call?.argsJson shouldBe "{}"
    }
}
