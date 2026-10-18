package dev.npn.core.ai

import dev.npn.core.db.TempIndexDb
import dev.npn.core.util.runCatchingCancellable
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.URLParserException
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Every HTTP client the AI layer makes is released, including when a runtime never comes to be. */
class AiClientLifetimeTest {
    private val temp = TempIndexDb("npn-ai-clients")
    private val db = temp.db

    @AfterTest
    fun tearDown() = temp.close()

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
    fun closingTheCacheReleasesItsRuntimesClient() =
        runTest {
            val closed = AtomicInteger()
            val runtimes = AiRuntimeCache { AiRuntime.build(it, newTransport = { probedClient(closed) }) }
            runtimes.runtimeFor(custom("http://localhost:1"))
            closed.get() shouldBe 0

            runtimes.close()

            (closed.get() > 0) shouldBe true
        }

    @Test
    fun anEndpointChangeReleasesTheStaleRuntimesClient() =
        runTest {
            val closed = AtomicInteger()
            val runtimes = AiRuntimeCache { AiRuntime.build(it, newTransport = { probedClient(closed) }) }
            val first = runtimes.runtimeFor(custom("http://localhost:1"))
            runtimes.runtimeFor(custom("http://localhost:1").copy(maxTagsPerDoc = 3)) shouldBe first
            closed.get() shouldBe 0

            runtimes.runtimeFor(custom("http://localhost:2"))

            (closed.get() > 0) shouldBe true
            runtimes.close()
        }

    /**
     * Switching to an endpoint whose runtime cannot be built (a malformed URL) changes nothing: the
     * runtime of the endpoint that works stays cached and open, so going back to it carries on.
     */
    @Test
    fun aFailedEndpointChangeKeepsTheWorkingRuntimeOpen() =
        runTest {
            val working = custom("http://localhost:1")
            val closedWorking = AtomicInteger()
            val closedBroken = AtomicInteger()
            val runtimes =
                AiRuntimeCache { config ->
                    val closed = if (config == working) closedWorking else closedBroken
                    AiRuntime.build(config, newTransport = { probedClient(closed) })
                }
            val first = runtimes.runtimeFor(working)

            assertFailsWith<URLParserException> { runtimes.runtimeFor(custom("http://exa mple.com")) }

            runtimes.runtimeFor(working) shouldBe first
            closedWorking.get() shouldBe 0
            runtimes.close()
        }

    /** Closing is final: a call racing the close builds no client that nobody would close. */
    @Test
    fun aCallAfterCloseIsRefusedAndBuildsNothing() =
        runTest {
            val built = AtomicInteger()
            val runtimes =
                AiRuntimeCache {
                    built.incrementAndGet()
                    AiRuntime.build(it)
                }
            runtimes.runtimeFor(custom("http://localhost:1"))

            runtimes.close()

            val failure = assertFailsWith<IllegalStateException> { runtimes.runtimeFor(custom("http://localhost:1")) }
            failure.message shouldBe AiRuntimeCache.CLOSED
            built.get() shouldBe 1
        }

    @Test
    fun aFacadeCallAfterCloseReachesNoEndpoint() =
        runTest {
            val gateway = FakeSseGateway(FakeSseGateway.zenGoResponsesStream()).start()
            try {
                val facade = AiFacadeImpl(db, db.keyValueDao()) { custom(gateway.baseUrl) }
                // Any call that reaches the endpoint builds the runtime; how this one ends does not matter.
                runCatchingCancellable { facade.ocrEngine.recognizePng(ByteArray(1), "eng") }
                val before = gateway.requestedPaths.size
                (before > 0) shouldBe true

                facade.close()
                runCatchingCancellable { facade.ocrEngine.recognizePng(ByteArray(1), "eng") }

                gateway.requestedPaths.size shouldBe before
            } finally {
                gateway.stop()
            }
        }
}
