package dev.npn.core.ai

import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.get
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import kotlin.test.Test

/**
 * The OpenCode Go gateway errors on requests without an `x-opencode-session` header, so this
 * drives the real client against a loopback server and reads the headers it actually sent.
 */
class AiGatewaySessionTest {
    private val header = "x-opencode-session"

    @Test
    fun everyRequestReportsTheConversationIdInScopeAndFallsBackToOneStableIdWithout() =
        runTest {
            val sent = mutableListOf<String?>()
            val server = echoServer(sent)
            val url = "http://127.0.0.1:${server.address.port}/"
            val client = AiRuntime.openCodeGoClient()
            try {
                client.get(url)
                withContext(AiGatewaySession("chat-42")) { client.get(url) }
                withContext(AiGatewaySession("chat-42")) { client.get(url) }
                client.get(url)
            } finally {
                client.close()
                server.stop(0)
            }
            // Both turns of one conversation report that conversation, not the fallback.
            sent[1] shouldBe "chat-42"
            sent[2] shouldBe "chat-42"
            // Conversation-less requests still carry a header, and one stable one per client.
            sent[0] shouldNotBe null
            sent[0] shouldNotBe "chat-42"
            sent[3] shouldBe sent[0]
        }

    private fun echoServer(sent: MutableList<String?>): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                sent += exchange.requestHeaders.getFirst(header)
                exchange.sendResponseHeaders(HTTP_NO_CONTENT, -1L)
                exchange.close()
            }
            start()
        }

    private companion object {
        const val HTTP_NO_CONTENT = 204
    }
}
