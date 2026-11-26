package dev.npn.core.db

import dev.npn.core.ai.AiLedger
import dev.npn.core.ai.AiUsageKind
import dev.npn.core.ai.AiUsageRecord
import dev.npn.core.ai.RoomAiLedger
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

/** The device-local ledger: what it aggregates, how a run resumes, and what it refuses to store. */
class AiUsageLedgerTest {
    private val dir: File = Files.createTempDirectory("npn-ai-ledger").toFile()
    private val db: DeviceDatabase = deviceDatabase(File(dir, "device.db"))
    private val ledger: AiLedger = RoomAiLedger(db.aiUsageDao())

    @AfterTest
    fun teardown() {
        db.close()
        dir.deleteRecursively()
    }

    private fun record(
        provider: String = "OPENAI",
        model: String = "gpt-4.1-mini",
        input: Long = 1_000,
        output: Long = 100,
        cost: Double? = 0.01,
        at: Long = 1_000,
        runId: String? = "run-1",
    ) = AiUsageRecord(
        provider = provider,
        model = model,
        kind = AiUsageKind.SUGGEST,
        inputTokens = input,
        outputTokens = output,
        estimatedCostUsd = cost,
        estimated = false,
        recordedAt = at,
        runId = runId,
    )

    @Test
    fun totalsAggregatePerProviderAndModel() =
        runTest {
            ledger.record(record())
            ledger.record(record(input = 2_000, output = 200, cost = 0.02))
            ledger.record(
                record(provider = "ANTHROPIC", model = "claude-sonnet-4-5", input = 500, output = 50, cost = 0.003),
            )

            val totals = ledger.totals(null)
            totals.map { it.provider to it.model } shouldContainExactlyInAnyOrder
                listOf("OPENAI" to "gpt-4.1-mini", "ANTHROPIC" to "claude-sonnet-4-5")
            val openAi = totals.single { it.provider == "OPENAI" }
            openAi.requests shouldBe 2
            openAi.inputTokens shouldBe 3_000
            openAi.outputTokens shouldBe 300
            openAi.estimatedCostUsd shouldBe 0.03
            openAi.unpricedRequests shouldBe 0
        }

    @Test
    fun theRollingWindowLeavesOlderRequestsInTheAllTimeTotal() =
        runTest {
            ledger.record(record(at = 1_000))
            ledger.record(record(at = 10_000))

            ledger.totals(sinceMillis = 5_000).single().requests shouldBe 1
            ledger.totals(null).single().requests shouldBe 2
        }

    @Test
    fun anUnpricedModelIsCountedAsTokensOnly() =
        runTest {
            ledger.record(record(provider = "CUSTOM", model = "local", cost = null))

            val totals = ledger.totals(null).single()
            totals.estimatedCostUsd shouldBe null
            totals.unpricedRequests shouldBe 1
            totals.inputTokens shouldBe 1_000
        }

    @Test
    fun resetDeletesEveryRecordedRequest() =
        runTest {
            ledger.record(record())
            ledger.reset()

            ledger.totals(null) shouldBe emptyList()
        }

    @Test
    fun theLedgerSchemaHasNoPromptOrDocumentTextColumn() {
        val schema = File("schemas/dev.npn.core.db.DeviceDatabase/3.json").readText()
        schema shouldNotContain "\"prompt\""
        schema shouldNotContain "\"content\""
        schema shouldNotContain "\"documentText\""
    }
}
