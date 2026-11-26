package dev.npn.core.ai

import dev.npn.core.db.DeviceDatabase
import dev.npn.core.db.deviceDatabase
import dev.npn.core.index.MutableClock
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

/** When the price book fetches a catalog, what it keeps, and what it answers meanwhile. */
class AiPriceBookTest {
    private val dir: File = Files.createTempDirectory("npn-price-book").toFile()
    private val db: DeviceDatabase = deviceDatabase(File(dir, "device.db"))
    private val clock = MutableClock(start = 1_000_000L)

    @AfterTest
    fun teardown() {
        db.close()
        dir.deleteRecursively()
    }

    private class FakeSource : AiPriceSource {
        var modelsDev: () -> String = { MODELS_DEV }
        var openRouter: (String) -> String = { OPEN_ROUTER }
        val modelsDevCalls = mutableListOf<Unit>()
        val openRouterCalls = mutableListOf<String>()

        override suspend fun modelsDev(): String {
            modelsDevCalls += Unit
            return modelsDev.invoke()
        }

        override suspend fun openRouter(baseUrl: String): String {
            openRouterCalls += baseUrl
            return openRouter.invoke(baseUrl)
        }
    }

    private val source = FakeSource()

    private fun TestScope.book() = AiPriceBook(db.keyValueDao(), source, clock, scope = this)

    /** Run the refresh [book] started, whose store write runs on Room's own dispatcher. */
    private suspend fun settle(
        test: TestScope,
        book: AiPriceBook,
    ) {
        test.advanceUntilIdle()
        book.awaitRefresh()
    }

    private fun config(
        provider: AiProvider = AiProvider.ANTHROPIC,
        model: String = "claude-opus-4-5",
        enabled: Boolean = true,
    ) = AiConfig(enabled = enabled, provider = provider, model = model, apiKey = "secret-key", baseUrl = "")

    @Test
    fun aConfiguredCloudEndpointFetchesOnceAndThenUsesTheFetchedPrice() =
        runTest {
            val book = book()

            // The first answer comes from the snapshot while the fetch runs in the background.
            book.priceOf(config()) shouldBe AiPrice(5.0, 25.0)
            settle(this, book)

            book.priceOf(config()) shouldBe AiPrice(4.0, 20.0)
            settle(this, book)
            source.modelsDevCalls.size shouldBe 1
        }

    @Test
    fun theStoredCatalogOutlivesTheSessionAndIsRefreshedOnlyAfterAWeek() =
        runTest {
            val first = book()
            first.priceOf(config())
            settle(this, first)

            val reopened = book()
            reopened.priceOf(config()) shouldBe AiPrice(4.0, 20.0)
            settle(this, reopened)
            source.modelsDevCalls.size shouldBe 1

            clock.advance(AiPriceBook.MAX_AGE_MS)
            reopened.priceOf(config())
            settle(this, reopened)
            source.modelsDevCalls.size shouldBe 2
        }

    @Test
    fun nothingIsFetchedForAnUnconfiguredALocalOrACustomEndpoint() =
        runTest {
            val book = book()

            book.priceOf(config(enabled = false))
            book.priceOf(config(provider = AiProvider.OLLAMA, model = "qwen3:8b")) shouldBe AiPrice.FREE
            book.priceOf(config(provider = AiProvider.CUSTOM, model = "claude-opus-4-5")) shouldBe null
            settle(this, book)

            source.modelsDevCalls.size shouldBe 0
            source.openRouterCalls.size shouldBe 0
        }

    @Test
    fun openRouterIsPricedFromItsOwnCatalog() =
        runTest {
            val book = book()
            val openRouter = config(provider = AiProvider.OPENROUTER, model = "anthropic/claude-opus-4.5")

            book.priceOf(openRouter)
            settle(this, book)

            book.priceOf(openRouter) shouldBe AiPrice(5.0, 25.0)
            source.openRouterCalls shouldBe listOf("https://openrouter.ai")
            source.modelsDevCalls.size shouldBe 0
        }

    @Test
    fun aFailedFetchKeepsTheSnapshotAndIsRetriedOnlyAfterAnHour() =
        runTest {
            source.modelsDev = { error("offline") }
            val book = book()

            book.priceOf(config()) shouldBe AiPrice(5.0, 25.0)
            settle(this, book)
            book.priceOf(config()) shouldBe AiPrice(5.0, 25.0)
            settle(this, book)
            source.modelsDevCalls.size shouldBe 1

            clock.advance(AiPriceBook.RETRY_MS)
            source.modelsDev = { MODELS_DEV }
            book.priceOf(config())
            settle(this, book)

            source.modelsDevCalls.size shouldBe 2
            book.priceOf(config()) shouldBe AiPrice(4.0, 20.0)
        }

    @Test
    fun aModelTheFetchedCatalogDoesNotListFallsBackToTheSnapshot() =
        runTest {
            val book = book()
            book.priceOf(config())
            settle(this, book)

            book.priceOf(config(model = "claude-sonnet-4-5")) shouldBe AiPrice(3.0, 15.0)
        }

    private companion object {
        const val MODELS_DEV =
            """{"anthropic": {"models": {"claude-opus-4-5": {"cost": {"input": 4, "output": 20}}}}}"""
        const val OPEN_ROUTER =
            """{"data": [{"id": "anthropic/claude-opus-4.5",
               "pricing": {"prompt": "0.000005", "completion": "0.000025"}}]}"""
    }
}
