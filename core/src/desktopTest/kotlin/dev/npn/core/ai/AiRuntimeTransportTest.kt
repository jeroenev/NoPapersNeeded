package dev.npn.core.ai

import ai.koog.prompt.dsl.prompt
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.http.Url
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * A host that hands [AiRuntime.build] its own transport sees every call the runtime makes — that
 * is the seam a metering or logging wrapper hangs on — and keeps ownership of that client.
 */
class AiRuntimeTransportTest {
    private fun config(
        provider: AiProvider,
        baseUrl: String,
    ) = AiConfig(enabled = true, provider = provider, baseUrl = baseUrl, apiKey = "unused", model = "kimi-k2.6")

    /** A base client whose only plugin records the path of every request it carries. */
    private fun recordingTransport(seen: MutableList<String>): HttpClient =
        HttpClient {
            install("record-paths") {
                requestPipeline.intercept(HttpRequestPipeline.Before) {
                    seen += Url(context.url.buildString()).encodedPath
                }
            }
        }

    @Test
    fun pluginsOnTheSuppliedTransportObserveTheRuntimesCalls() =
        runTest {
            val gateway = FakeSseGateway(FakeSseGateway.zenGoResponsesStream()).start()
            val seen = mutableListOf<String>()
            val transport = recordingTransport(seen)
            try {
                val runtime = AiRuntime.build(config(AiProvider.CUSTOM, gateway.baseUrl), transport)
                runCatching {
                    runtime.executor
                        .executeStreaming(prompt("p") { user("hi") }, runtime.model, emptyList())
                        .collect()
                }
                runtime.close()
                seen shouldContainExactly listOf("/v1/chat/completions")
                gateway.requestedPaths shouldContainExactly listOf("/v1/chat/completions")
            } finally {
                transport.close()
                gateway.stop()
            }
        }

    @Test
    fun providerDecorationLayersOnTopOfTheSuppliedTransport() =
        runTest {
            // OpenCode Go needs its session header; that must not displace what the host installed.
            val gateway = FakeSseGateway(FakeSseGateway.zenGoResponsesStream()).start()
            val seen = mutableListOf<String>()
            val transport = recordingTransport(seen)
            try {
                val runtime = AiRuntime.build(config(AiProvider.OPENCODE_GO, gateway.baseUrl), transport)
                runCatching {
                    runtime.executor
                        .executeStreaming(prompt("p") { user("hi") }, runtime.model, emptyList())
                        .collect()
                }
                runtime.close()
                seen shouldContainExactly listOf("/v1/chat/completions")
            } finally {
                transport.close()
                gateway.stop()
            }
        }

    @Test
    fun closingTheRuntimeLeavesASuppliedTransportUsable() =
        runTest {
            val gateway = FakeSseGateway(FakeSseGateway.zenGoResponsesStream()).start()
            val seen = mutableListOf<String>()
            val transport = recordingTransport(seen)
            try {
                AiRuntime.build(config(AiProvider.CUSTOM, gateway.baseUrl), transport).close()
                // The facade rebuilds runtimes on a config change; the host's client must survive that.
                val second = AiRuntime.build(config(AiProvider.CUSTOM, gateway.baseUrl), transport)
                runCatching {
                    second.executor
                        .executeStreaming(prompt("p") { user("hi") }, second.model, emptyList())
                        .collect()
                }
                second.close()
                seen.size shouldBe 1
            } finally {
                transport.close()
                gateway.stop()
            }
        }
}
