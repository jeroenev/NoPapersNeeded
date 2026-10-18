package dev.npn.app.ui.common

import dev.npn.core.model.AppLanguages
import io.kotest.matchers.collections.shouldContainExactly
import kotlin.test.Test

/**
 * The languages the app is translated into are listed twice: here, for their names and the
 * app-language picker, and in core, for their OCR models and the OCR language picker. Both lists
 * name the same languages in the same order.
 */
class UiLanguageTest {
    @Test
    fun everyAppLanguageHasItsOcrModelInTheOrderCoreListsThem() {
        UiLanguage.entries.map { it.ocrModel } shouldContainExactly AppLanguages.tesseractModels
    }
}
