@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import dev.npn.app.English
import dev.npn.app.LocaleRestoringTest
import dev.npn.app.resourcesIn
import dev.npn.app.ui.closing
import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.common.hintText
import dev.npn.app.ui.nodes
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.texts
import dev.npn.app.ui.themedScene
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldParse
import dev.npn.core.testing.doc
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.detail_field_seed_hint_correspondent
import no_papers_needed.composeapp.generated.resources.detail_field_seed_hint_doc_date
import no_papers_needed.composeapp.generated.resources.detail_field_seed_hint_title
import no_papers_needed.composeapp.generated.resources.detail_title_hint
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import java.util.Locale
import kotlin.test.Test

/**
 * A seeded field's guidance is the app's own text, so the document panel shows it in the app's
 * language; a hint the library wrote is the user's, shown as written. A model is told the stored
 * hint either way.
 */
class FieldHintTest : LocaleRestoringTest() {
    private val seeds = FieldKeys.seedDefinitions { FieldKeys.SEED_LABELS.getValue(it) }

    @Test
    fun theSeededHintsShowInTheAppLanguage() {
        for (language in listOf("nl", "de", "fr", "es", "pt")) {
            val translated = translated(SEEDED.values + Res.string.detail_title_hint, language)
            for (res in SEEDED.values) translated.getValue(res) shouldNotBe english(res)
            onUiThread {
                val shown = panelTexts(seeds, language)
                for (res in SEEDED.values) shown shouldContain translated.getValue(res)
                for (english in FieldKeys.SEED_HINTS.values) shown.none { english in it } shouldBe true
                // One hint under the title: its own, which says how the file is renamed, and no note besides.
                shown shouldNotContain translated.getValue(Res.string.detail_title_hint)
            }
        }
    }

    @Test
    fun aHintTheLibraryWroteShowsAsWritten() {
        val own = "Wie de rekening stuurde"
        val edited = seeds.map { if (it.key == FieldKeys.CORRESPONDENT) it.copy(hint = own) else it }
        val dutch = translated(listOf(Res.string.detail_field_seed_hint_correspondent))
        onUiThread {
            val shown = panelTexts(edited)
            shown shouldContain own
            shown shouldNotContain dutch.getValue(Res.string.detail_field_seed_hint_correspondent)
        }
    }

    @Test
    fun aTitleWithoutAHintStillSaysHowTheFileIsRenamed() {
        val bare = seeds.map { if (it.key == FieldKeys.TITLE) it.copy(hint = null) else it }
        val dutch = translated(listOf(Res.string.detail_title_hint))
        onUiThread { panelTexts(bare) shouldContain dutch.getValue(Res.string.detail_title_hint) }
    }

    @Test
    fun onlyASeededFieldStillHoldingItsSeedHintIsTranslated() {
        val byKey = seeds.associateBy { it.key }
        for ((key, res) in SEEDED) byKey.getValue(key).hintText() shouldBe UiText.Res(res)
        // Every seeded hint has the app's words for it.
        SEEDED.keys shouldBe FieldKeys.SEED_HINTS.keys
        byKey.getValue(FieldKeys.DOC_TYPE).hintText() shouldBe null
        byKey.getValue(FieldKeys.TITLE).copy(hint = "Kort").hintText() shouldBe UiText.Raw("Kort")
        // Another field holding a seed's words wrote them itself.
        val sender = byKey.getValue(FieldKeys.CORRESPONDENT).copy(key = "sender", label = "Sender")
        sender.hintText().shouldBeInstanceOf<UiText.Raw>()
    }

    /** Every text the document panel's fields show for [defs], in [language]. */
    private fun panelTexts(
        defs: List<FieldDef>,
        language: String = "nl",
    ): List<String> =
        themedScene(WIDTH, HEIGHT, appLocale = language) {
            Box(Modifier.verticalScroll(rememberScrollState())) {
                FieldsSection(
                    doc = doc("invoice", title = "Elektriciteit"),
                    refused = emptySet(),
                    defs = defs,
                    vocabulary = VocabularyState(),
                    actions = NoFieldEdits,
                )
            }
        }.closing {
            repeat(2) { render() }
            nodes().flatMap { it.texts() }
        }

    /** [resources] in [language], read before a scene sets the JVM's locale. */
    private fun translated(
        resources: Collection<StringResource>,
        language: String = "nl",
    ): Map<StringResource, String> {
        val environment = resourcesIn(Locale.forLanguageTag(language))
        return runBlocking { resources.associateWith { getString(environment, it) } }
    }

    private fun english(res: StringResource): String = runBlocking { getString(English, res) }

    private object NoFieldEdits : MetadataActions, DocumentWrites by NoWrites {
        override fun commitField(
            key: String,
            entry: FieldParse,
        ) = Unit

        override fun removeField(key: String) = Unit

        override fun setReviewed(reviewed: Boolean) = Unit
    }

    private companion object {
        const val WIDTH = 900
        const val HEIGHT = 2400

        val SEEDED: Map<String, StringResource> =
            mapOf(
                FieldKeys.TITLE to Res.string.detail_field_seed_hint_title,
                FieldKeys.CORRESPONDENT to Res.string.detail_field_seed_hint_correspondent,
                FieldKeys.DOC_DATE to Res.string.detail_field_seed_hint_doc_date,
            )
    }
}
