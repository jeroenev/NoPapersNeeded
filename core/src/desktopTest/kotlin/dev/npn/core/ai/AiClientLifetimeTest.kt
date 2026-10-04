package dev.npn.core.ai

import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.npnDatabase
import dev.npn.core.util.runCatchingCancellable
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.URLParserException
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Every HTTP client the AI layer makes is released, including when a runtime never comes to be. */
class AiClientLifetimeTest {
    private val dir: File = Files.createTempDirectory("npn-ai-clients").toFile()
    private val db: NpnDatabase = npnDatabase(File(dir, "index.db"))

    @AfterTest
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    /** A client whose plugin counts the clients carrying it that have been closed, derived ones too. */
    private fun probedClient(closed: AtomicInteger): HttpClient =
        HttpClient { install(createClientPlugin("close-probe") { onClose { closed.incrementAndGet() } }) }

    private fun custom(baseUrl: String) =
        AiConfig(enabled = true, provider = AiProvider.CUSTOM, baseUrl = baseUrl, apiKey = "unused", model = "m")

    @Test
    fun aProviderFactoryThatThrowsClosesTheClientMadeForIt() {
        val closed = AtomicInteger()
        assertFailsWith<URLParserException> {
            AiRuntime.build(custom("http://exa mple.com"), newTransport = { probedClient(closed) })
        }
        closed.get() shouldBe 1
    }

    @Test
    fun closingTheFacadeReleasesItsRuntimesClient() =
        runTest {
            val gateway = FakeSseGateway(FakeSseGateway.zenGoResponsesStream()).start()
            val closed = AtomicInteger()
            val transport = probedClient(closed)
            try {
                val facade = AiFacadeImpl(db, transport) { custom(gateway.baseUrl) }
                // Any call that reaches the endpoint builds the runtime; how this one ends does not matter.
                runCatchingCancellable { facade.transcribePage(ByteArray(1), "eng") }
                closed.get() shouldBe 0

                facade.close()

                (closed.get() > 0) shouldBe true
            } finally {
                transport.close()
                gateway.stop()
            }
        }
}
