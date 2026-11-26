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

            outcomes.outcomes.size shouldBe 2
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

            outcomes.outcomes.size shouldBe 3
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

            outcomes.outcomes.size shouldBe 0
            outcomes.capReached shouldBe true
            ai.suggestEditsCalls shouldBe emptyList()
        }

    @Test
    fun aCallThatFailedBeforeAnyResponseGivesItsSlotBack() =
        runTest {
            val ai =
                FakeAiSuggest().apply {
                    edit = edit()
                    failWith = { docId -> if (docId == "d0") error("429 Too Many Requests") }
                }
            val budget = AiRunBudget(cap = 2)
            val outcomes =
                SuggestionRunner(ai).suggest(
                    docIds = List(3) { "d$it" },
                    request = AiEditRequest(tags = true),
                    budget = budget,
                ) { _, suggestion -> suggestion }

            // The failed call was not paid for, so the cap of two still buys two answers.
            outcomes.outcomes.count { it.outcome.isSuccess } shouldBe 2
            outcomes.capReached shouldBe false
            budget.usedRequests() shouldBe 2
        }

    @Test
    fun aFailureAfterTheAnswerArrivedKeepsItsSlot() =
        runTest {
            val ai = FakeAiSuggest().apply { edit = edit() }
            val budget = AiRunBudget(cap = 2)
            val outcomes =
                SuggestionRunner(ai).suggest(
                    docIds = List(3) { "d$it" },
                    request = AiEditRequest(tags = true),
                    budget = budget,
                ) { index, suggestion -> if (index == 0) error("projection failed") else suggestion }

            // The answer was paid for even though what came after it failed.
            ai.suggestEditsCalls.size shouldBe 2
            outcomes.capReached shouldBe true
            budget.usedRequests() shouldBe 2
        }
}
