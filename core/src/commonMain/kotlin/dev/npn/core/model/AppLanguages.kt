package dev.npn.core.model

/**
 * The platform's current language tag (the JVM's default locale on both apps). Read on every call:
 * it changes while the app runs, and it is what the app's language picker sets.
 */
expect fun systemLanguage(): String

/**
 * The app language as the layers below the UI see it: a platform language tag, reduced to its
 * ISO 639-1 subtag, and what is known about the languages the app plausibly runs in. The prompts
 * name the language to the model, and OCR picks the matching Tesseract model; both read it here.
 */
object AppLanguages {
    /** A language the app plausibly runs in. */
    data class Known(
        /** How a prompt names it, e.g. `Dutch`. */
        val englishName: String,
        /** Its Tesseract model, for the languages the app is translated into; null for the others. */
        val tesseractModel: String?,
    )

    /**
     * By subtag. Deliberately not exhaustive: a caller falls back for any language not listed. In
     * the order the OCR language picker lists their models ([tesseractModels]).
     */
    private val KNOWN: Map<String, Known> =
        mapOf(
            "en" to Known("English", "eng"),
            "nl" to Known("Dutch", "nld"),
            "de" to Known("German", "deu"),
            "fr" to Known("French", "fra"),
            "es" to Known("Spanish", "spa"),
            "it" to Known("Italian", null),
            "pt" to Known("Portuguese", "por"),
        )

    /** The Tesseract models of the languages the app is translated into, in [KNOWN]'s order. */
    val tesseractModels: List<String> get() = KNOWN.values.mapNotNull(Known::tesseractModel)

    /** The language subtag of [tag] (`nl` for `nl`, `nl-NL` or `nl_NL`), lowercased; null when blank. */
    fun subtag(tag: String?): String? =
        tag
            ?.trim()
            ?.substringBefore('-')
            ?.substringBefore('_')
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }

    /** What is known about the language of [tag], or null when it is blank or not listed. */
    fun known(tag: String?): Known? = subtag(tag)?.let(KNOWN::get)
}
