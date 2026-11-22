package dev.npn.core.ai

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.URLProtocol
import java.net.InetSocketAddress

/**
 * A local stand-in for an OpenAI-compatible gateway that replays one canned `text/event-stream`
 * body, so streaming-decode behaviour can be exercised without a network or an API key.
 *
 * Captured gateway streams are the only honest fixture for gateway quirks: the frames a real
 * endpoint emits (including the non-standard ones) are the thing under test, and hand-written
 * approximations of them drift from what the gateway actually sends.
 */
internal class FakeSseGateway(
    private val body: String,
) {
    companion object {
        /**
         * A real `opencode.ai/zen/go` Responses-API stream for `gpt-5.6-luna`, captured verbatim
         * (ids anonymised) in `zen-go-responses-stream.sse`. It ends with the gateway's own
         * non-standard trailer, AFTER `response.completed`:
         *
         * ```
         * event: ping
         * data: {"type":"ping","cost":"0"}
         * ```
         *
         * `ping` is not a member of Koog's closed `OpenAIStreamEvent` polymorphic scope, so
         * decoding it strictly throws. It is the Responses-API sibling of the chat-completions cost
         * frames [TolerantOpenAILLMClient] already absorbs.
         */
        fun zenGoResponsesStream(): String =
            checkNotNull(
                FakeSseGateway::class.java.getResource("/zen-go-responses-stream.sse"),
            ) { "captured gateway stream fixture is missing" }.readText()
    }

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    /** Paths the client asked for, in order — lets a test assert WHICH endpoint was called. */
    val requestedPaths = mutableListOf<String>()

    /** Request bodies, in order — lets a test assert the shape of what the client sent. */
    val requestBodies = mutableListOf<String>()

    val baseUrl: String
        get() = "http://127.0.0.1:${server.address.port}"

    fun start(): FakeSseGateway {
        server.createContext("/") { exchange ->
            requestedPaths += exchange.requestURI.path
            requestBodies += exchange.requestBody.readBytes().decodeToString()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            // 0 = chunked transfer encoding, which is how a real SSE endpoint answers; a fixed
            // Content-Length makes the Ktor SSE client abort with a length mismatch.
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use {
                it.write(body.toByteArray())
                it.flush()
            }
        }
        server.start()
        return this
    }

    fun stop() = server.stop(0)

    /**
     * A base client that sends every request here, whatever host it names, after adding that host
     * to [hosts]. For providers whose server root is fixed: the runtime is built exactly as for the
     * real endpoint, and nothing leaves the machine. Redirected at the send stage, after every
     * plugin of the provider client has settled the URL.
     */
    fun redirectingTransport(hosts: MutableList<String> = mutableListOf()): HttpClient {
        val redirect =
            createClientPlugin("redirect-to-fake-gateway") {
                on(Send) { request ->
                    hosts += request.url.host
                    request.url.protocol = URLProtocol.HTTP
                    request.url.host = "127.0.0.1"
                    request.url.port = server.address.port
                    proceed(request)
                }
            }
        return HttpClient { install(redirect) }
    }
}
