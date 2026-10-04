package dev.npn.core.ai

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.streaming.StreamFrame
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The OpenCode Go gateway serves OpenAI-family models on the Responses API and everything else on
 * chat/completions, and answers HTTP 500 when asked on the wrong one. These tests pin both halves
 * of that: which endpoint a config is routed to, and that the gateway's non-standard trailing
 * `event: ping` frame does not destroy an otherwise complete turn.
 *
 * The gateway is a local [FakeSseGateway] replaying a captured stream, so this runs offline with
 * no API key.
 */
class ZenGoResponsesStreamTest {
    @Test
    fun openAiFamilyModelsOnOpenCodeGoUseTheResponsesApi() {
        listOf(
            "gpt-5.6-luna",
            "gpt-4o-mini",
            "GPT-5.1",
            "o3-mini",
            "o1",
            "gpt-5.1-codex-max",
            "openai/gpt-5",
        ).forEach { model ->
            AiRuntime.usesResponsesApi(AiProvider.OPENCODE_GO, model) shouldBe true
        }
    }

    @Test
    fun otherModelsOnOpenCodeGoStayOnChatCompletions() {
        listOf(
            "kimi-k2.6",
            "glm-5.1",
            "deepseek-v4-pro",
            "qwen3.8-flash",
            "minimax-m3",
            "hy3",
            "grok-4.5",
            "",
        ).forEach { model ->
            AiRuntime.usesResponsesApi(AiProvider.OPENCODE_GO, model) shouldBe false
        }
    }

    @Test
    fun otherProvidersKeepChatCompletionsEvenForOpenAiModelIds() {
        // Only the OpenCode Go gateway splits its models across the two APIs. api.openai.com serves
        // both, and a self-hosted OpenAI-compatible server named "gpt-..." serves only completions.
        listOf(AiProvider.OPENAI, AiProvider.CUSTOM).forEach { provider ->
            AiRuntime.usesResponsesApi(provider, "gpt-5.6-luna") shouldBe false
        }
    }

    @Test
    fun responsesModelGetsResponsesShapedReasoningParams() {
        // The params type has to agree with the endpoint, or Koog's determineParams routes the call
        // back to chat/completions — which the gateway answers with a 500 for these models.
        val params =
            AiRuntime.reasoningParams(
                provider = AiProvider.OPENCODE_GO,
                model = "gpt-5.6-luna",
                level = AiReasoningLevel.LOW,
                toolLoop = true,
            )
        params.shouldBeInstanceOf<OpenAIResponsesParams>().reasoning?.effort shouldBe ReasoningEffort.LOW
    }

    private fun zenGoConfig(model: String) =
        AiConfig(enabled = true, provider = AiProvider.OPENCODE_GO, apiKey = "unused", model = model)

    /** One turn against the real gateway URL, sent to [gateway] instead. */
    private suspend fun streamOneTurn(
        model: String,
        gateway: FakeSseGateway,
    ): Result<List<StreamFrame>> {
        val transport = gateway.redirectingTransport()
        val runtime = AiRuntime.build(zenGoConfig(model), transport)
        val frames = mutableListOf<StreamFrame>()
        val outcome =
            runCatching {
                runtime.executor
                    .executeStreaming(prompt("p") { user("hi") }, runtime.model, emptyList())
                    .collect { frames += it }
                frames.toList()
            }
        runtime.close()
        transport.close()
        return outcome
    }

    @Test
    fun openAiFamilyModelIsCalledOnTheResponsesApi() =
        runTest {
            val gateway = FakeSseGateway(FakeSseGateway.zenGoResponsesStream()).start()
            try {
                streamOneTurn("gpt-5.6-luna", gateway)
                gateway.requestedPaths shouldContainExactly listOf("/zen/go/v1/responses")
            } finally {
                gateway.stop()
            }
        }

    @Test
    fun nonOpenAiModelStaysOnChatCompletions() =
        runTest {
            val gateway = FakeSseGateway(FakeSseGateway.zenGoResponsesStream()).start()
            try {
                streamOneTurn("kimi-k2.6", gateway)
                gateway.requestedPaths shouldContainExactly listOf("/zen/go/v1/chat/completions")
            } finally {
                gateway.stop()
            }
        }

    @Test
    fun trailingPingFrameDoesNotDiscardTheCompletedTurn() =
        runTest {
            val gateway = FakeSseGateway(FakeSseGateway.zenGoResponsesStream()).start()
            try {
                val frames = streamOneTurn("gpt-5.6-luna", gateway).getOrThrow()

                frames.filterIsInstance<StreamFrame.TextDelta>().joinToString("") { it.text } shouldBe
                    "Hi there friend!"
                // The terminal frame survives: without it Koog's toMessageResponse() drops the turn.
                (frames.last() is StreamFrame.End) shouldBe true
            } finally {
                gateway.stop()
            }
        }

    @Test
    fun aTruncatedStreamStillFails() =
        runTest {
            // Same stream cut off before `response.completed`: no End frame was emitted, so the
            // decode failure at the end is a real one and must NOT be swallowed as a finished turn.
            val truncated =
                FakeSseGateway.zenGoResponsesStream().substringBefore("event: response.completed") +
                    "event: ping\ndata: {\"type\":\"ping\"}\n"
            val gateway = FakeSseGateway(truncated).start()
            try {
                streamOneTurn("gpt-5.6-luna", gateway).isFailure shouldBe true
            } finally {
                gateway.stop()
            }
        }
}
