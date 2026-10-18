package dev.npn.app.ui.common

import androidx.compose.runtime.Composable
import dev.npn.core.model.AppLanguages
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_settings_language_dutch
import no_papers_needed.composeapp.generated.resources.common_settings_language_english
import no_papers_needed.composeapp.generated.resources.common_settings_language_french
import no_papers_needed.composeapp.generated.resources.common_settings_language_german
import no_papers_needed.composeapp.generated.resources.common_settings_language_portuguese
import no_papers_needed.composeapp.generated.resources.common_settings_language_spanish
import no_papers_needed.composeapp.generated.resources.common_settings_language_system
import no_papers_needed.composeapp.generated.resources.common_settings_ocr_language_portuguese
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The languages the app is translated into, in picker order, each named in itself: the one table
 * both the app-language picker and the OCR language chips read. A language's OCR model comes from
 * [AppLanguages], which lists the same languages in the same order (`UiLanguageTest` holds them to
 * it). [ocrName] differs only where the app speaks a regional variant: its Portuguese is
 * Brazilian, while the OCR model reads any Portuguese.
 */
enum class UiLanguage(
    /** The app-language setting's value: the ISO 639-1 subtag. */
    val tag: String,
    val nativeName: StringResource,
    val ocrName: StringResource = nativeName,
) {
    ENGLISH("en", Res.string.common_settings_language_english),
    DUTCH("nl", Res.string.common_settings_language_dutch),
    GERMAN("de", Res.string.common_settings_language_german),
    FRENCH("fr", Res.string.common_settings_language_french),
    SPANISH("es", Res.string.common_settings_language_spanish),
    PORTUGUESE(
        "pt",
        Res.string.common_settings_language_portuguese,
        Res.string.common_settings_ocr_language_portuguese,
    ),
    ;

    /** The Tesseract model for this language. */
    val ocrModel: String? get() = AppLanguages.known(tag)?.tesseractModel
}

/** An app-language setting as the picker shows it; null follows the system. */
@Composable
fun appLanguageName(tag: String?): String {
    if (tag == null) return stringResource(Res.string.common_settings_language_system)
    return UiLanguage.entries.firstOrNull { it.tag == tag }?.let { stringResource(it.nativeName) } ?: tag
}

/** An OCR model by its language's own name, for the languages the app speaks; any other by its code. */
@Composable
fun ocrLanguageName(code: String): String =
    UiLanguage.entries.firstOrNull { it.ocrModel == code }?.let { stringResource(it.ocrName) } ?: code
