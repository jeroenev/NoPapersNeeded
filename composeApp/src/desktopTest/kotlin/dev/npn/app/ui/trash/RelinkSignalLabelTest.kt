package dev.npn.app.ui.trash

import dev.npn.app.English
import dev.npn.app.resourcesIn
import dev.npn.app.ui.common.resolve
import dev.npn.core.index.RelinkCandidate
import dev.npn.core.index.RelinkSignal
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.test.runTest
import java.util.Locale
import kotlin.test.Test

/** A relink candidate says why it may be the missing document's file, a label per reason, in the app's language. */
class RelinkSignalLabelTest {
    private val german = resourcesIn(Locale.GERMAN)

    @Test
    fun aCandidateShowsALabelPerSignalInTheAppLanguage() =
        runTest {
            val candidate =
                RelinkCandidate("old", "new", setOf(RelinkSignal.SAME_FOLDER, RelinkSignal.SIMILAR_NAME), score = 25)

            candidate.signalLabels().map { it.resolve(german) } shouldContainExactly
                listOf("Ähnlicher Name", "Gleicher Ordner")
        }

    @Test
    fun noLabelIsAMachineTag() =
        runTest {
            for (language in listOf("nl", "de", "fr", "es", "pt")) {
                val translated = resourcesIn(Locale.forLanguageTag(language))
                for (signal in RelinkSignal.entries) {
                    val shown = signal.label().resolve(translated)
                    shown shouldNotBe signal.label().resolve(English)
                    shown.lowercase() shouldNotContain signal.name.lowercase()
                    shown.lowercase() shouldNotBe "dir"
                    for (tag in listOf("path-match", "fuzzy", "+", "_")) shown shouldNotContain tag
                }
            }
        }
}
