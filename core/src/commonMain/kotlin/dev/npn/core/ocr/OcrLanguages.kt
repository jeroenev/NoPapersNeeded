package dev.npn.core.ocr

import dev.npn.core.model.AppLanguages

/**
 * The OCR language setting: one or more Tesseract language codes joined with `+`, Tesseract's own
 * syntax for recognising several languages at once (`nld+eng`), or null for automatic, which
 * follows the app language.
 */
object OcrLanguages {
    const val ENGLISH = "eng"

    /**
     * Models for the languages the app UI is translated into ([AppLanguages.tesseractModels]), in
     * picker order, English first. Windows builds and the Flatpak bundle exactly these
     * (desktopApp/tessdata-fast.txt, which a desktopApp test holds this list to); other Linux
     * installs have whichever the user installed.
     */
    val BUNDLED: List<String> = (listOf(ENGLISH) + AppLanguages.tesseractModels).distinct()

    /**
     * The automatic choice: the app language's model plus English, which most paperwork mixes in
     * (product names, foreign invoices). An app language without a model falls back to English.
     */
    fun forAppLanguage(tag: String?): List<String> =
        listOfNotNull(AppLanguages.known(tag)?.tesseractModel, ENGLISH).distinct()

    /** Codes in a stored value; also accepts commas and spaces, as typed by hand. */
    fun parse(value: String): List<String> =
        value
            .split('+', ',', ' ')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()

    fun format(codes: List<String>): String = codes.joinToString("+")

    /** The languages OCR runs with: the stored [setting], or the automatic choice for [appLanguage]. */
    fun effective(
        setting: String?,
        appLanguage: String?,
    ): String = format(setting?.let(::parse)?.ifEmpty { null } ?: forAppLanguage(appLanguage))
}
