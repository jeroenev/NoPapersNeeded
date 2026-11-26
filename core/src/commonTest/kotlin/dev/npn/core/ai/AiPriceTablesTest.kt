package dev.npn.core.ai

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** Matching a configured model to its list price: exact ids only, with one documented normalisation. */
class AiPriceTablesTest {
    private fun config(
        provider: AiProvider,
        model: String,
    ) = AiConfig(enabled = true, provider = provider, model = model, apiKey = "k")

    private val table: AiPriceTable =
        mapOf(
            "openai" to
                mapOf(
                    "o3" to AiPrice(2.0, 8.0),
                    "o3-pro" to AiPrice(20.0, 80.0),
                    "gpt-4o" to AiPrice(2.5, 10.0),
                    "gpt-4o-2024-05-13" to AiPrice(5.0, 15.0),
                ),
            "anthropic" to
                mapOf(
                    "claude-opus-4-5" to AiPrice(5.0, 25.0),
                    "claude-sonnet-4-5" to AiPrice(3.0, 15.0),
                ),
            "google" to
                mapOf(
                    "gemini-2.5-flash" to AiPrice(0.3, 2.5),
                    "gemini-2.5-flash-lite" to AiPrice(0.1, 0.4),
                ),
            "mistral" to mapOf("magistral-medium-latest" to AiPrice(1.5, 7.5)),
        )

    private fun price(
        provider: AiProvider,
        model: String,
    ) = AiPriceTables.lookup(config(provider, model), table)

    @Test
    fun aModelIsPricedByItsExactIdNeverByAFamilyPrefix() {
        price(AiProvider.OPENAI, "o3-pro") shouldBe AiPrice(20.0, 80.0)
        price(AiProvider.OPENAI, "o3") shouldBe AiPrice(2.0, 8.0)
        price(AiProvider.GOOGLE, "gemini-2.5-flash-lite") shouldBe AiPrice(0.1, 0.4)
        // Not listed: a family or size word in the id is no price.
        price(AiProvider.OPENAI, "o3-mini").shouldBeNull()
        price(AiProvider.MISTRAL, "magistral-small-latest").shouldBeNull()
        price(AiProvider.ANTHROPIC, "claude-opus-9").shouldBeNull()
    }

    @Test
    fun aDatedIdFallsBackToItsAliasButAnExactDatedPriceWins() {
        price(AiProvider.ANTHROPIC, "claude-sonnet-4-5-20250929") shouldBe AiPrice(3.0, 15.0)
        price(AiProvider.OPENAI, "gpt-4o-2024-08-06") shouldBe AiPrice(2.5, 10.0)
        price(AiProvider.OPENAI, "gpt-4o-2024-05-13") shouldBe AiPrice(5.0, 15.0)
    }

    @Test
    fun googlesResourcePrefixCaseAndWhitespaceAreNormalised() {
        price(AiProvider.GOOGLE, "models/gemini-2.5-flash") shouldBe AiPrice(0.3, 2.5)
        price(AiProvider.ANTHROPIC, " Claude-Opus-4-5 ") shouldBe AiPrice(5.0, 25.0)
    }

    @Test
    fun aModelIsPricedOnlyUnderItsOwnProvider() {
        price(AiProvider.DEEPSEEK, "claude-opus-4-5").shouldBeNull()
    }

    @Test
    fun ollamaIsFreeAndACustomServerIsNeverPriced() {
        price(AiProvider.OLLAMA, "qwen3:8b") shouldBe AiPrice.FREE
        price(AiProvider.CUSTOM, "claude-opus-4-5").shouldBeNull()
    }

    @Test
    fun theFirstTableThatKnowsTheModelWins() {
        val fresh = mapOf("anthropic" to mapOf("claude-opus-4-5" to AiPrice(4.0, 20.0)))

        AiPriceTables.lookup(config(AiProvider.ANTHROPIC, "claude-opus-4-5"), fresh, table) shouldBe AiPrice(4.0, 20.0)
        AiPriceTables.lookup(config(AiProvider.ANTHROPIC, "claude-sonnet-4-5"), fresh, table) shouldBe
            AiPrice(3.0, 15.0)
    }

    @Test
    fun theBundledSnapshotPricesEveryCatalogProvider() =
        runTest {
            AiPriceTables.snapshot.keys shouldContainAll AiPriceTables.catalogIds
            // The prices the old hardcoded table got wrong.
            AiPrices.Snapshot.priceOf(config(AiProvider.ANTHROPIC, "claude-opus-4-5")) shouldBe AiPrice(5.0, 25.0)
            AiPrices.Snapshot.priceOf(config(AiProvider.OPENAI, "o3-pro")) shouldBe AiPrice(20.0, 80.0)
        }

    @Test
    fun modelsDevIsReadPerProviderAndModelsWithoutAPriceAreSkipped() {
        val json =
            """
            {
              "anthropic": {"id": "anthropic", "models": {
                "claude-opus-4-5": {"cost": {"input": 5, "output": 25, "cache_read": 0.5}},
                "claude-unpriced": {"name": "no cost"},
                "claude-half": {"cost": {"input": 1}}
              }},
              "someone-else": {"models": {"x": {"cost": {"input": 1, "output": 1}}}}
            }
            """.trimIndent()

        AiPriceTables.parseModelsDev(json) shouldBe
            mapOf("anthropic" to mapOf("claude-opus-4-5" to AiPrice(5.0, 25.0)))
    }

    @Test
    fun openRoutersPerTokenPricesBecomePerMillionAndVariablePricesAreSkipped() {
        val json =
            """
            {"data": [
              {"id": "anthropic/claude-opus-4.5", "pricing": {"prompt": "0.000005", "completion": "0.000025"}},
              {"id": "openrouter/auto", "pricing": {"prompt": "-1", "completion": "-1"}},
              {"id": "meta/free:free", "pricing": {"prompt": "0", "completion": "0"}}
            ]}
            """.trimIndent()

        val prices = AiPriceTables.parseOpenRouter(json).getValue("openrouter")

        prices.keys shouldBe setOf("anthropic/claude-opus-4.5", "meta/free:free")
        prices.getValue("anthropic/claude-opus-4.5").inputPerMillionUsd shouldBe (5.0 plusOrMinus 1e-9)
        prices.getValue("anthropic/claude-opus-4.5").outputPerMillionUsd shouldBe (25.0 plusOrMinus 1e-9)
        prices.getValue("meta/free:free") shouldBe AiPrice.FREE
    }
}
