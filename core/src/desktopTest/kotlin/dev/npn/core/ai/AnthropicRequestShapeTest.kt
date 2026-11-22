package dev.npn.core.ai

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.params.LLMParams
import dev.npn.core.util.runCatchingCancellable
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The request body Koog sends a current Claude model for each kind of call, read off the wire.
 * These models answer `budget_tokens` with HTTP 400, and the newest answer `thinking: disabled` the
 * same way, so neither may appear; the effort and adaptive thinking that replace them travel as
 * additional properties, which this checks Koog puts at the top level of the request.
 */
class AnthropicRequestShapeTest {
    @Test
    fun editAtTheDefaultLevelSendsAdaptiveThinkingAndEffort() =
        runTest {
            val body = sentBody(AiRuntime.suggestionParams(config()))
            body shouldContain "\"thinking\":{\"type\":\"adaptive\"}"
            body shouldContain "\"output_config\":{\"effort\":\"low\"}"
            body shouldContain "\"max_tokens\":16000"
            body shouldNotContain "budget_tokens"
        }

    @Test
    fun pageTranscriptionNeverSendsThinkingDisabled() =
        runTest {
            val body = sentBody(AiRuntime.ocrParams(config()))
            body shouldNotContain "\"thinking\""
            body shouldContain "\"output_config\":{\"effort\":\"low\"}"
            body shouldContain "\"max_tokens\":16000"
        }

    @Test
    fun chatAtTheDefaultLevelRaisesMaxTokensOnly() =
        runTest {
            val body = sentBody(AiRuntime.chatParams(config()) ?: LLMParams())
            body shouldNotContain "\"thinking\""
            body shouldNotContain "output_config"
            body shouldContain "\"max_tokens\":16000"
        }

    private fun config() =
        AiConfig(enabled = true, provider = AiProvider.ANTHROPIC, apiKey = "unused", model = "claude-opus-5-5")
            .also { it.reasoningEdit shouldBe AiReasoningLevel.LOW }

    /** The one request body a streamed call with [params] sends; the canned empty reply fails it. */
    private suspend fun sentBody(params: LLMParams): String {
        val gateway = FakeSseGateway("").start()
        val transport = gateway.redirectingTransport()
        val runtime = AiRuntime.build(config(), transport)
        try {
            runCatchingCancellable {
                runtime.executor
                    .executeStreaming(prompt("p", params) { user("hi") }, runtime.model, emptyList())
                    .collect()
            }
        } finally {
            runtime.close()
            transport.close()
            gateway.stop()
        }
        return gateway.requestBodies.single()
    }
}
