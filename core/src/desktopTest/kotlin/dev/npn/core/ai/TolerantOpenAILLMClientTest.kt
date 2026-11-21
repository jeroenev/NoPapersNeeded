package dev.npn.core.ai

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class TolerantOpenAILLMClientTest {
    private fun client() =
        TolerantOpenAILLMClient(
            apiKey = "unused",
            settings = OpenAIClientSettings(baseUrl = "https://example.test"),
            httpClientFactory = KtorKoogHttpClient.Factory(),
        ).also { it.close() }

    @Test
    fun dropsNonStandardCostChunkWithoutThrowing() {
        // The exact malformed trailing chunk the opencode.ai/zen/go gateway appends: no
        // id/created/model/object, empty choices. Strict Koog decoding would throw
        // MissingFieldException; the tolerant client must return a benign empty-choices response.
        val chunk = """{"choices":[],"x-opencode-type":"inference-cost","cost":"0.00008246"}"""
        val decoded = client().decodeStreamingResponseTolerant(chunk)
        decoded.choices.shouldBeEmpty()
    }

    @Test
    fun dropsTrailingZeroCostChunk() {
        val decoded = client().decodeStreamingResponseTolerant("""{"choices":[],"cost":"0"}""")
        decoded.choices.shouldBeEmpty()
    }

    @Test
    fun dropsChunkWithAbsentChoices() {
        val decoded = client().decodeStreamingResponseTolerant("""{"cost":"0"}""")
        decoded.choices.shouldBeEmpty()
    }

    @Test
    fun anInBandErrorChunkFailsWithTheServersText() {
        // LiteLLM and vLLM end a stream that failed mid-way with an error chunk and no choices; it
        // must fail the turn with the server's text, not pass for an empty frame.
        val chunk = """{"error":{"message":"upstream timed out","type":"server_error","code":504}}"""
        shouldThrow<IllegalStateException> { client().decodeStreamingResponseTolerant(chunk) }
            .message shouldBe "upstream timed out"
    }

    @Test
    fun aBareStringErrorChunkFailsWithThatString() {
        shouldThrow<IllegalStateException> { client().decodeStreamingResponseTolerant("""{"error":"overloaded"}""") }
            .message shouldBe "overloaded"
    }

    @Test
    fun aNullErrorIsNoError() {
        client().decodeStreamingResponseTolerant("""{"choices":[],"error":null}""").choices.shouldBeEmpty()
    }

    @Test
    fun stillDecodesAWellFormedContentChunk() {
        // A normal delta chunk must decode as usual (non-empty choices with the text delta).
        val chunk =
            """
            {"id":"c1","created":1,"model":"m","object":"chat.completion.chunk",
             "choices":[{"index":0,"delta":{"content":"hi"}}]}
            """.trimIndent().replace("\n", "")
        val decoded = client().decodeStreamingResponseTolerant(chunk)
        decoded.choices shouldHaveSize 1
        decoded.choices
            .first()
            .delta.content shouldBe "hi"
    }
}
