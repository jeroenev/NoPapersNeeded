package dev.npn.app.ui.library

import dev.npn.app.English
import dev.npn.app.resourcesIn
import dev.npn.app.ui.common.resolve
import dev.npn.core.app.ScanProgressUi
import dev.npn.core.index.IndexProgress
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.test.runTest
import java.util.Locale
import kotlin.test.Test

/** The scan banner says where a rescan is in the app's language, never by the phase's name in code. */
class ScanProgressTextTest {
    private val german = resourcesIn(Locale.GERMAN)

    @Test
    fun everyPhaseReadsAsASentenceInTheAppLanguage() =
        runTest {
            for (language in listOf("nl", "de", "fr", "es", "pt")) {
                val translated = resourcesIn(Locale.forLanguageTag(language))
                for (phase in IndexProgress.Phase.entries) {
                    val progress = ScanProgressUi(phase, done = 1, total = 10)
                    val shown = progress.bannerText().resolve(translated)
                    shown.lowercase() shouldNotContain phase.name.lowercase()
                    shown shouldNotBe progress.bannerText().resolve(English)
                }
            }
        }

    @Test
    fun hashingCountsTheFilesRead() =
        runTest {
            ScanProgressUi(IndexProgress.Phase.HASHING, done = 1, total = 10)
                .bannerText()
                .resolve(german) shouldBe "Dateien werden gelesen (1 von 10)…"
        }
}
