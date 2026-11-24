package dev.npn.core.app.aiedit

import dev.npn.core.ai.AiEditRequest
import dev.npn.core.ai.AiRunBudget
import dev.npn.core.testing.FakeAiSuggest
import dev.npn.core.testing.edit
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** The per-run request cap: it stops dispatch exactly at the budget and reports what it skipped. */
class SuggestionRunnerCapTest {
    @Test
    fun theCapStopsTheRunAndLeavesTheRestUnasked() =
        runTest {
            val ai = FakeAiSuggest().apply { edit = edit() }
            val outcomes =
                SuggestionRunner(ai).suggest(
                    docIds = List(5) { "d$it" },
                    request = AiEditRequest(tags = true),
                    budget = AiRunBudget(cap = 2),
                ) { _, suggestion -> suggestion }

            outcomes.requests shouldBe 2
            outcomes.capReached shouldBe true
            outcomes.skipped(5) shouldBe 3
            ai.suggestEditsCalls.size shouldBe 2
        }

    @Test
    fun noCapAsksEveryDocument() =
        runTest {
            val ai = FakeAiSuggest().apply { edit = edit() }
            val outcomes =
                SuggestionRunner(ai).suggest(
                    docIds = List(3) { "d$it" },
                    request = AiEditRequest(tags = true),
                ) { _, suggestion -> suggestion }

            outcomes.requests shouldBe 3
            outcomes.capReached shouldBe false
            outcomes.skipped(3) shouldBe 0
        }

    @Test
    fun aBudgetSeededFromTheLedgerDoesNotAskAgain() =
        runTest {
            val ai = FakeAiSuggest().apply { edit = edit() }
            val outcomes =
                SuggestionRunner(ai).suggest(
                    docIds = List(2) { "d$it" },
                    request = AiEditRequest(tags = true),
                    budget = AiRunBudget(cap = 2, initiallyUsed = 2),
                ) { _, suggestion -> suggestion }

            outcomes.requests shouldBe 0
            outcomes.capReached shouldBe true
            ai.suggestEditsCalls shouldBe emptyList()
        }
}
