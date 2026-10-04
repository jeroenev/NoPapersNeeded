package dev.npn.core.ai

import dev.npn.core.app.SettingsRepositoryImpl
import dev.npn.core.app.toAiConfig
import dev.npn.core.db.npnDatabase
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Probes that talk to a REAL AI endpoint, for the questions no offline test can answer: whether a
 * provider/gateway actually accepts the request shapes this app sends. Everything else about the AI
 * code is covered offline (see [ZenGoResponsesStreamTest], which replays a captured gateway stream)
 * — these exist for verifying a provider change against the live thing, and for diagnosing "works
 * in another client, not here" reports.
 *
 * ## They are skipped by default, and must stay that way
 *
 * They cost money (one vision call per page, a multi-turn tool-calling chat), need credentials, and
 * depend on a third party being up — none of which belongs in `mise test` or CI. So every probe
 * skips unless [ENABLE_VAR] is set:
 *
 * ```
 * NPN_LIVE_AI=1 ./gradlew :core:desktopTest --tests 'dev.npn.core.ai.LiveEndpointProbeTest' --rerun
 * ```
 *
 * JUnit's assumption mechanism reports them as *skipped*, not passed, so a normal run cannot
 * quietly look greener than it is.
 *
 * ## Credentials
 *
 * By default the probes read this device's own app settings — the endpoint you have already
 * configured in Settings > AI assistant — so there is no second place to keep a key in sync, and no
 * key is ever passed on a command line (where it would land in shell history). Override any field
 * to probe a different endpoint without touching your settings:
 *
 * ```
 * NPN_LIVE_AI=1 NPN_LIVE_AI_MODEL=kimi-k2.6 ./gradlew ...
 * ```
 *
 * Never print [AiConfig.apiKey] from a probe: this output is routinely pasted into issues.
 */
class LiveEndpointProbeTest {
    @BeforeTest
    fun skipUnlessExplicitlyEnabled() {
        assumeTrue(
            "Live endpoint probes are opt-in: set $ENABLE_VAR=1 to run them (spends real money).",
            !System.getenv(ENABLE_VAR).isNullOrBlank(),
        )
    }

    @Test
    fun endpointListsItsModels() {
        val config = liveConfig()
        val models = runBlocking { AiModelCatalog.listModels(config) }

        models.shouldNotBeEmpty()
        // The configured model has to be one the endpoint admits to serving, or every other probe
        // here is testing a typo.
        (config.model in models) shouldBe true
    }

    @Test
    fun chatAnswersAndCallsLibraryTools() {
        val config = liveConfig()
        withFacade(config) { facade ->
            val events =
                runBlocking {
                    val session = facade.createChatSession()
                    facade.ask(session.id, "How many documents do I have? Use your tools.").toList()
                }

            val toolCalls = events.filterIsInstance<AiChatEvent.ToolCallStarted>()
            val done = events.filterIsInstance<AiChatEvent.Done>().single()
            println("PROBE chat tools=${toolCalls.map { it.name }}")
            println("PROBE chat answer=${done.fullText}")

            // A streamed turn that survives to Done proves the whole path: request shape accepted,
            // tool calls parsed and replayed, and any trailing gateway frames tolerated.
            toolCalls.shouldNotBeEmpty()
            done.fullText.isNotBlank() shouldBe true
        }
    }

    @Test
    fun visionModelTranscribesAPage() {
        val config = liveConfig()
        withFacade(config) { facade ->
            val text = runBlocking { facade.transcribePage(pagePng(), "eng") }
            println("PROBE ocr=$text")

            // Graded against text this test drew itself, so a plausible-but-invented transcription
            // fails instead of passing on vibes.
            text shouldContain "INVOICE 2026-0142"
            text shouldContain "Dynapps"
            text shouldContain "1730.50"
        }
    }

    @Test
    fun selfTestPassesAgainstAWorkingEndpoint() {
        val config = liveConfig()
        val report = runBlocking { AiSelfTest.run(config) }
        report.outcomes.forEach { println("PROBE selftest ${it.check}=${it.status} ${it.detail.orEmpty()}") }

        // The self-test is what the settings screen shows, and a failing vision check switches AI
        // page reading off — so a false negative here would turn off a working feature. The probe
        // above already proved this endpoint transcribes a page, so all three must pass.
        AiCheck.entries.forEach { report.statusOf(it) shouldBe AiCheckStatus.PASSED }
        report.provesAiOcrCannotWork shouldBe false
    }

    /**
     * This device's configured endpoint, with per-field env overrides. Fails loudly rather than
     * skipping when the endpoint is half-configured: the probe was explicitly asked for, so silence
     * would be the wrong answer.
     */
    private fun liveConfig(): AiConfig {
        val stored = runBlocking { SettingsRepositoryImpl().settings.first() }.toAiConfig()
        val config =
            stored.copy(
                enabled = true,
                provider = env("PROVIDER")?.let { AiProvider.valueOf(it) } ?: stored.provider,
                baseUrl = env("BASE_URL") ?: stored.baseUrl,
                apiKey = env("KEY") ?: stored.apiKey,
                model = env("MODEL") ?: stored.model,
            )
        check(config.configured) {
            "No usable endpoint: configure Settings > AI assistant, or set ${ENABLE_VAR}_MODEL / " +
                "${ENABLE_VAR}_KEY / ${ENABLE_VAR}_BASE_URL / ${ENABLE_VAR}_PROVIDER."
        }
        println(
            "PROBE endpoint provider=${config.provider} model=${config.model} " +
                "responsesApi=${AiRuntime.usesResponsesApi(config.provider, config.model)}",
        )
        return config
    }

    private fun env(suffix: String): String? = System.getenv("${ENABLE_VAR}_$suffix")?.ifBlank { null }

    /** A facade over a throwaway index, so probes never read or write the real library. */
    private fun withFacade(
        config: AiConfig,
        block: (AiFacadeImpl) -> Unit,
    ) {
        val dir = Files.createTempDirectory("npn-live-probe").toFile()
        val db = npnDatabase(File(dir, "index.db"))
        try {
            block(AiFacadeImpl(db) { config })
        } finally {
            db.close()
            dir.deleteRecursively()
        }
    }

    /**
     * A synthetic "scanned page" carrying text this test knows, drawn rather than checked in: a
     * committed scan of a real document would be either a fake or someone's actual paperwork.
     */
    private fun pagePng(): ByteArray {
        val lines =
            listOf(
                "INVOICE 2026-0142",
                "Dynapps BV",
                "Date: 7 September 2026",
                "Description        Amount",
                "Odoo consulting      1250.00",
                "Support contract      480.50",
                "TOTAL               1730.50",
            )
        val image = BufferedImage(PAGE_WIDTH, PAGE_HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, image.width, image.height)
        g.color = Color.BLACK
        g.font = Font(Font.MONOSPACED, Font.PLAIN, FONT_SIZE)
        lines.forEachIndexed { i, line -> g.drawString(line, LEFT_MARGIN, FIRST_BASELINE + i * LINE_HEIGHT) }
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    private companion object {
        /** Set to any non-blank value to run these probes; `_MODEL`, `_KEY`, ... override fields. */
        const val ENABLE_VAR = "NPN_LIVE_AI"

        const val PAGE_WIDTH = 1000
        const val PAGE_HEIGHT = 700
        const val FONT_SIZE = 34
        const val LEFT_MARGIN = 60
        const val FIRST_BASELINE = 90
        const val LINE_HEIGHT = 70
    }
}
