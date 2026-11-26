import org.gradle.api.GradleException
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ModelPricesTest {
    private val api =
        """
        {
          "anthropic": {"models": {
            "claude-opus-4-5": {"cost": {"input": 5, "output": 25, "cache_read": 0.5}},
            "claude-haiku-4-5": {"cost": {"input": 1.0, "output": 5.00}},
            "claude-no-price": {}
          }},
          "openai": {"models": {
            "o3-pro": {"cost": {"input": 20, "output": 80}},
            "weird \"id\"": {"cost": {"input": 1, "output": 1}},
            "free": {"cost": {"input": 0, "output": 0}}
          }},
          "elsewhere": {"models": {"x": {"cost": {"input": 1, "output": 1}}}}
        }
        """.trimIndent()

    private fun trim(providers: List<String> = listOf("openai", "anthropic")) =
        ModelPrices.trim(api, providers, LocalDate.of(2026, 11, 26), "https://models.dev/api.json")

    @Test
    fun theSnapshotKeepsOnlyPricedModelsOfTheChosenProvidersSorted() {
        val lines = trim().lines().filter { it.isNotBlank() && !it.startsWith("#") }

        assertEquals(
            listOf(
                "anthropic\tclaude-haiku-4-5\t1\t5",
                "anthropic\tclaude-opus-4-5\t5\t25",
                "openai\tfree\t0\t0",
                "openai\to3-pro\t20\t80",
            ),
            lines,
        )
    }

    @Test
    fun theHeaderSaysWhereAndWhenThePricesCameFrom() {
        assertContains(trim(), "# From https://models.dev/api.json, fetched 2026-11-26.")
    }

    @Test
    fun aProviderMissingFromTheCatalogFailsTheUpdate() {
        assertFailsWith<GradleException> { trim(listOf("openai", "gone")) }
    }

    @Test
    fun theGeneratedSourceCarriesEveryLineAsAnEscapedConstant() {
        val source = ModelPrices.kotlinSource(trim())

        assertContains(source, "internal object AiPriceSnapshot")
        assertContains(source, "anthropic\\tclaude-opus-4-5\\t5\\t25\\n")
        assertFalse(source.contains("cache_read"))
    }

    @Test
    fun aLargeSnapshotIsSplitIntoConstantsTheJvmAccepts() {
        val tsv = (1..3_000).joinToString("\n") { "openai\tmodel-$it\t1.25\t10" }

        val source = ModelPrices.kotlinSource(tsv)

        assertContains(source, "PART_0 + PART_1")
        source
            .lines()
            .filter { it.trimStart().startsWith("\"") }
            .forEach { literal -> assert(literal.length < 40_000) { "constant of ${literal.length} chars" } }
    }

    @Test
    fun aMalformedLineFailsTheBuild() {
        assertFailsWith<GradleException> { ModelPrices.kotlinSource("openai\tgpt\tcheap\t1\n") }
        assertFailsWith<GradleException> { ModelPrices.kotlinSource("openai\tgpt\"x\t1\t1\n") }
        assertFailsWith<GradleException> { ModelPrices.kotlinSource("openai\tgpt\t-1\t1\n") }
    }
}
