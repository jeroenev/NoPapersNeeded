package dev.npn.core.ai

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The self-test's run order and the conclusions it draws, with the three endpoint calls faked.
 * What is pinned here is the part a user feels: which checks are attempted after a failure (each
 * one costs a request), what counts as a pass, and when a failing vision check is allowed to
 * switch AI page reading off.
 */
class AiSelfTestTest {
    /**
     * [AiSelfTest.AiProbes] that records what was called. A probe returns its scripted answer or
     * throws its scripted failure.
     */
    private class FakeProbes(
        private val models: Result<List<String>> = Result.success(listOf("a", "b")),
        private val message: Result<String> = Result.success("pong"),
        private val page: Result<String> = Result.success("7482"),
    ) : AiSelfTest.AiProbes {
        val called = mutableListOf<AiCheck>()

        override suspend fun listModels(): List<String> {
            called += AiCheck.API
            return models.getOrThrow()
        }

        override suspend fun answerMessage(): String {
            called += AiCheck.MESSAGES
            return message.getOrThrow()
        }

        override suspend fun readProbePage(): String {
            called += AiCheck.VISION
            return page.getOrThrow()
        }
    }

    private fun statuses(report: AiSelfTestReport) = AiCheck.entries.map { report.statusOf(it) }

    @Test
    fun aHealthyEndpointPassesAllThreeChecks() =
        runTest {
            val probes = FakeProbes()

            val report = AiSelfTest.run(probes)

            statuses(report) shouldContainExactly List(3) { AiCheckStatus.PASSED }
            probes.called shouldContainExactly listOf(AiCheck.API, AiCheck.MESSAGES, AiCheck.VISION)
            report.outcomes.mapNotNull { it.detail } shouldContainExactly emptyList()
        }

    @Test
    fun anUnreachableEndpointSkipsTheTwoPaidChecks() =
        runTest {
            val probes = FakeProbes(models = Result.failure(IllegalStateException("401 Unauthorized")))

            val report = AiSelfTest.run(probes)

            statuses(report) shouldContainExactly
                listOf(AiCheckStatus.FAILED, AiCheckStatus.SKIPPED, AiCheckStatus.SKIPPED)
            // The point of stopping: no request is paid for once the endpoint is known to be down.
            probes.called shouldContainExactly listOf(AiCheck.API)
            report.outcomes.first().detail shouldBe "401 Unauthorized"
        }

    @Test
    fun aModelThatCannotAnswerSkipsTheVisionCheck() =
        runTest {
            val probes = FakeProbes(message = Result.failure(IllegalStateException("model not found")))

            val report = AiSelfTest.run(probes)

            statuses(report) shouldContainExactly
                listOf(AiCheckStatus.PASSED, AiCheckStatus.FAILED, AiCheckStatus.SKIPPED)
            probes.called shouldContainExactly listOf(AiCheck.API, AiCheck.MESSAGES)
        }

    @Test
    fun aWrongAnswerFailsTheCheckAndIsQuotedBack() =
        runTest {
            // A 200 with the wrong body is still a broken endpoint, and the body is the diagnosis.
            val probes = FakeProbes(message = Result.success("  I am unable to help with that.  "))

            val report = AiSelfTest.run(probes)

            report.statusOf(AiCheck.MESSAGES) shouldBe AiCheckStatus.FAILED
            report.outcomes.first { it.check == AiCheck.MESSAGES }.detail shouldBe "I am unable to help with that."
        }

    @Test
    fun theAnswerOnlyHasToContainTheExpectedWord() =
        runTest {
            // Models pad one-word answers; "pong." and "Pong!" are the model working, not failing.
            val report = AiSelfTest.run(FakeProbes(message = Result.success("Pong!")))

            report.statusOf(AiCheck.MESSAGES) shouldBe AiCheckStatus.PASSED
        }

    @Test
    fun theVisionCheckReadsThroughFormattingButNotThroughAWrongCode() =
        runTest {
            AiSelfTest
                .run(
                    FakeProbes(page = Result.success("The digits are 7 4 8 2.")),
                ).statusOf(AiCheck.VISION) shouldBe
                AiCheckStatus.PASSED
            AiSelfTest.run(FakeProbes(page = Result.success("1234"))).statusOf(AiCheck.VISION) shouldBe
                AiCheckStatus.FAILED
        }

    @Test
    fun aModelThatDescribesTheImageInsteadOfReadingItFails() =
        runTest {
            // The check has to be unfaked by a plausible-sounding answer, or it proves nothing.
            val blind = "I'm sorry, I can't view images."

            val report = AiSelfTest.run(FakeProbes(page = Result.success(blind)))

            report.statusOf(AiCheck.VISION) shouldBe AiCheckStatus.FAILED
            report.outcomes.last().detail shouldContain "can't view images"
        }

    @Test
    fun onlyAVisionCheckThatRanAndFailedTurnsOffAiOcr() =
        runTest {
            AiSelfTest.run(FakeProbes(page = Result.success("1234"))).provesAiOcrCannotWork shouldBe true
            // Skipped proves nothing: the endpoint was down, which says nothing about the model.
            AiSelfTest
                .run(FakeProbes(models = Result.failure(IllegalStateException("down"))))
                .provesAiOcrCannotWork shouldBe false
            AiSelfTest.run(FakeProbes()).provesAiOcrCannotWork shouldBe false
        }

    @Test
    fun aDeadEndpointComesBackAsAReportRatherThanAnException() =
        runTest {
            // The real entry point, against a closed local port (no outside network, no key): the
            // settings screen must always get a report to render, never an exception to catch.
            val report =
                AiSelfTest.run(
                    AiConfig(
                        enabled = true,
                        provider = AiProvider.CUSTOM,
                        baseUrl = "http://127.0.0.1:1",
                        model = "nothing-listens-here",
                    ),
                )

            statuses(report) shouldContainExactly
                listOf(AiCheckStatus.FAILED, AiCheckStatus.SKIPPED, AiCheckStatus.SKIPPED)
        }
}
