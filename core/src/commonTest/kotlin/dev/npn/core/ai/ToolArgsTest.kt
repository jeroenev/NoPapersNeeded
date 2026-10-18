package dev.npn.core.ai

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** A tool call's arguments as the chat shows them: model output, read leniently and never failing. */
class ToolArgsTest {
    @Test
    fun textArgumentsAreReadByNameAndBlankOnesLeftOut() {
        toolArgs("""{"query":"tax","tag":"  ","limit":5,"filter":{"a":1},"none":null}""") shouldBe
            mapOf("query" to "tax", "limit" to "5")
    }

    @Test
    fun whatAModelSendsLooselyStillReads() {
        toolArgs("{query: invoices}") shouldBe mapOf("query" to "invoices")
    }

    @Test
    fun anythingButAnObjectIsNoArgument() {
        toolArgs("") shouldBe emptyMap()
        toolArgs("[\"tax\"]") shouldBe emptyMap()
        toolArgs("{\"query\":") shouldBe emptyMap()
    }
}
