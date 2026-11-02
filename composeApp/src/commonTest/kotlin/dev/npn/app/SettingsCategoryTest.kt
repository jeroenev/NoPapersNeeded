package dev.npn.app

import dev.npn.app.ui.settings.SettingsCategories
import dev.npn.app.ui.settings.SettingsCategoryId
import dev.npn.app.ui.settings.SettingsCategoryLabels
import dev.npn.app.ui.settings.searchableLabel
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Guards the settings table of contents: the registry is the single source of truth for which
 * categories exist, and its route keys are persisted in the back stack, so drift here silently
 * breaks navigation rather than failing to compile.
 */
class SettingsCategoryTest {
    private fun labels(
        title: String,
        vararg keywords: String,
    ) = SettingsCategoryLabels(
        category = SettingsCategories.first(),
        title = title,
        keywords = keywords.toList(),
    )

    @Test
    fun registryCoversEveryCategoryExactlyOnce() {
        SettingsCategories.map { it.id } shouldContainExactly SettingsCategoryId.entries.toList()
    }

    @Test
    fun everyCategoryOffersSearchKeywords() {
        SettingsCategories.filter { it.keywords.isEmpty() }.map { it.id } shouldContainExactly emptyList()
    }

    @Test
    fun routeKeysRoundTrip() {
        SettingsCategoryId.entries.forEach { id ->
            SettingsCategoryId.fromRouteKey(id.routeKey) shouldBe id
        }
    }

    @Test
    fun unknownRouteKeyResolvesToNoCategory() {
        SettingsCategoryId.fromRouteKey("nope").shouldBeNull()
        SettingsCategoryId.fromRouteKey(null).shouldBeNull()
    }

    @Test
    fun blankQueryMatchesEveryCategory() {
        labels("Library").matches("") shouldBe true
        labels("Library").matches("   ") shouldBe true
    }

    @Test
    fun queryMatchesTitleCaseInsensitively() {
        labels("Import & OCR").matches("ocr") shouldBe true
    }

    @Test
    fun queryMatchesTheLabelOfAContainedSetting() {
        val entry = labels("Library", "Clear thumbnail cache", "Path template")
        entry.matches("thumbnail") shouldBe true
        entry.matchedKeyword("thumbnail") shouldBe "Clear thumbnail cache"
    }

    @Test
    fun unrelatedQueryMatchesNothing() {
        val entry = labels("Library", "Clear thumbnail cache")
        entry.matches("bluetooth") shouldBe false
        entry.matchedKeyword("bluetooth").shouldBeNull()
    }

    @Test
    fun aLabelWithAValueInItIsSearchedByItsName() {
        searchableLabel("Default view: %1${'$'}s") shouldBe "Default view"
        searchableLabel("Vue par défaut : %1${'$'}s") shouldBe "Vue par défaut"
        searchableLabel("Clear thumbnail cache") shouldBe "Clear thumbnail cache"
    }

    @Test
    fun blankQueryHasNoMatchedKeywordToExplain() {
        labels("Library", "Clear thumbnail cache").matchedKeyword("").shouldBeNull()
    }

    /** A search that finds a setting behind the pane's Advanced disclosure opens it; one that does not, not. */
    @Test
    fun aSearchHitBehindAdvancedRevealsIt() {
        val entry =
            labels("Library", "Path template", "Clear thumbnail cache")
                .copy(advancedKeywords = listOf("Advanced", "Clear thumbnail cache"))
        entry.revealsAdvanced("thumbnail") shouldBe true
        entry.revealsAdvanced(" ADVANCED ") shouldBe true
        entry.revealsAdvanced("template") shouldBe false
        entry.revealsAdvanced("") shouldBe false
    }

    /** Every setting behind a disclosure is searchable at all: an advanced keyword is a keyword. */
    @Test
    fun everyAdvancedKeywordIsAKeyword() {
        SettingsCategories.flatMap { category ->
            (category.advancedKeywords - category.keywords.toSet()).map { "${category.id}: $it" }
        } shouldContainExactly emptyList()
    }
}
