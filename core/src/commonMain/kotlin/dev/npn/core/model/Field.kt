package dev.npn.core.model

import kotlinx.serialization.Serializable

/**
 * How a field's value is entered, validated, stored and asked for.
 *
 * Only two places are allowed to switch exhaustively on this enum: [FieldValues] (canonical form)
 * and the UI's `FieldEditor` (input control). Everything else reads the capabilities each constant
 * declares — that is what keeps adding a type a two-file change, and why a new type has to state
 * every one of them rather than silently get `false`.
 */
@Serializable
enum class FieldType(
    val wire: String,
    /** The nullable JSON literal a model is asked to answer with for a field of this type. */
    val aiLiteral: String,
    /** Values are text: they belong in the full-text index and compare case-insensitively. */
    val isTextual: Boolean,
    /** The canonical value has a numeric shadow ([FieldValues.numeric]). */
    val isNumeric: Boolean,
    /** Values are dates: a storage path may format them, and a model gets date guidance. */
    val isDate: Boolean,
    /** A value is one line, so it can be sorted by, set by a rule, or shown in a row. Prose is not. */
    val singleLine: Boolean,
    /**
     * Documents share a vocabulary of distinct values worth listing: a filter picks from it, an
     * editor completes from it, and a model may reuse it (unless the field is
     * [FieldDef.perDocument]).
     */
    val hasVocabulary: Boolean,
) {
    /** Single line, autocompleted from existing values. */
    TEXT(
        "text",
        "string|null",
        isTextual = true,
        isNumeric = false,
        isDate = false,
        singleLine = true,
        hasVocabulary = true,
    ),

    /** Multi-line prose (what `notes` is). */
    LONG_TEXT(
        "longText",
        "string|null",
        isTextual = true,
        isNumeric = false,
        isDate = false,
        singleLine = false,
        hasVocabulary = false,
    ),

    /** Integer. */
    NUMBER(
        "number",
        "integer|null",
        isTextual = false,
        isNumeric = true,
        isDate = false,
        singleLine = true,
        hasVocabulary = false,
    ),

    /** Fractional number; canonical form always uses `.`. */
    DECIMAL(
        "decimal",
        "number|null",
        isTextual = false,
        isNumeric = true,
        isDate = false,
        singleLine = true,
        hasVocabulary = false,
    ),

    /** ISO-8601 local date, `yyyy-MM-dd`. */
    DATE(
        "date",
        "\"YYYY-MM-DD\"|null",
        isTextual = false,
        isNumeric = false,
        isDate = true,
        singleLine = true,
        hasVocabulary = false,
    ),
    ;

    companion object {
        fun fromWire(value: String?): FieldType? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * One field in the library's registry. Synced through [dev.npn.core.journal.JournalOp.FieldDefSet]
 * and folded last-writer-wins per [key]. The key is the stable identity and is never renamed; the
 * [label] carries display casing and is freely editable.
 */
data class FieldDef(
    /** Stable identity, normalized by [FieldKeys.normalize]. */
    val key: String,
    /** Display name, user-editable. */
    val label: String,
    val type: FieldType,
    /** Renders on every document, empty or not. Purely a UI decision. */
    val showByDefault: Boolean,
    /** Offered to AI edit runs and followed by automatic enrichment. A cost/quality decision. */
    val aiEnabled: Boolean,
    /** Display order. */
    val position: Int,
    /** Helper text in the UI and guidance in the AI prompt. */
    val hint: String? = null,
    /**
     * Each document's value is its own, as a title is: written for the document rather than picked
     * from what other documents carry. So no other document's values are offered for reuse (a
     * model's vocabulary, the search box's completions), and a model writes the value in the app's
     * language, as a single short answer.
     */
    val perDocument: Boolean = false,
) {
    init {
        require(key.isNotBlank()) { "FieldDef key must not be blank" }
    }

    /** Keys other subsystems name directly (XMP, storage-path templates, auto-match rules). */
    val isWellKnown: Boolean get() = key in FieldKeys.WELL_KNOWN

    /** Whether other documents' values of this field are worth offering for reuse. */
    val sharesVocabulary: Boolean get() = type.hasVocabulary && !perDocument
}

/**
 * The field keys other subsystems name directly. XMP writes `dc:title`, storage-path templates
 * resolve `{correspondent}`, auto-match rules write `docType`. This set is deliberately small and
 * deliberately load bearing: deleting it breaks file naming, not just a UI label.
 */
object FieldKeys {
    const val TITLE = "title"
    const val CORRESPONDENT = "correspondent"
    const val DOC_TYPE = "docType"
    const val DOC_DATE = "docDate"
    const val NOTES = "notes"
    val WELL_KNOWN: Set<String> = setOf(TITLE, CORRESPONDENT, DOC_TYPE, DOC_DATE, NOTES)

    /** Fields that a split does not carry onto its parts: they describe the whole, not each piece. */
    val NOT_INHERITED_ON_SPLIT: Set<String> = setOf(TITLE, NOTES)

    /** Synced setting holding the [SEED_VERSION] the library's well-known definitions were seeded at. */
    const val SEEDED_SETTING = "fieldsSeeded"

    /**
     * Raised whenever [seedDefinitions] gains something a library seeded earlier should get too
     * ([upgradeSeeded]). 1: the five definitions. 2: title's [FieldDef.perDocument] and the hints.
     */
    const val SEED_VERSION = 2

    private val invalidKeyChars = Regex("[^A-Za-z0-9_]")
    private val whitespace = Regex("\\s+")

    /**
     * The one place a free-form key becomes a field identity: trim, collapse whitespace to `_`,
     * drop everything outside `[A-Za-z0-9_]`. Returns null when nothing survives. Casing is
     * preserved (the first casing seen wins in the registry); compare with [sameKey].
     */
    fun normalize(raw: String): String? {
        val collapsed = raw.trim().replace(whitespace, "_")
        val cleaned = collapsed.replace(invalidKeyChars, "")
        return cleaned.ifEmpty { null }
    }

    /** Case-insensitive key equality, so `Amount` and `amount` cannot become two fields. */
    fun sameKey(
        a: String,
        b: String,
    ): Boolean = a.equals(b, ignoreCase = true)

    /**
     * The hints the seeded definitions start with, by key: what a model is told about each field, so
     * written to read well to it, in English. An editor shows the app's own translation of one while
     * the definition still holds it, and a hint the library changed since as it was written.
     */
    val SEED_HINTS: Map<String, String> =
        mapOf(
            TITLE to
                "What the document is, in a few words (e.g. Electricity invoice January 2024), not its " +
                "file name. Keep company names, reference numbers and other proper nouns as printed",
            CORRESPONDENT to
                "The other party: the company, institution or person that sent or issued the document, " +
                "never the archive's owner",
            DOC_DATE to "The date the document was issued (invoice, letter or issue date), not when it was scanned",
        )

    /** The registry definitions a fresh library starts with. Positions follow list order. */
    fun seedDefinitions(labels: (String) -> String = { it }): List<FieldDef> =
        listOf(
            FieldDef(
                TITLE,
                labels(TITLE),
                FieldType.TEXT,
                showByDefault = true,
                aiEnabled = true,
                position = 0,
                hint = SEED_HINTS[TITLE],
                perDocument = true,
            ),
            FieldDef(
                CORRESPONDENT,
                labels(CORRESPONDENT),
                FieldType.TEXT,
                showByDefault = true,
                aiEnabled = true,
                position = 1,
                hint = SEED_HINTS[CORRESPONDENT],
            ),
            FieldDef(DOC_TYPE, labels(DOC_TYPE), FieldType.TEXT, showByDefault = true, aiEnabled = true, position = 2),
            FieldDef(
                DOC_DATE,
                labels(DOC_DATE),
                FieldType.DATE,
                showByDefault = true,
                aiEnabled = true,
                position = 3,
                hint = SEED_HINTS[DOC_DATE],
            ),
            // Not AI-enabled on purpose: automatic enrichment must not start generating prose into
            // the user's notes on every OCR completion, which is exactly today's behaviour.
            FieldDef(NOTES, labels(NOTES), FieldType.LONG_TEXT, showByDefault = true, aiEnabled = false, position = 4),
        )

    /**
     * What a library seeded at an earlier [SEED_VERSION] gains from [seeds]: each definition still
     * registered under a seeded key takes the seed's [FieldDef.perDocument], and its hint where it has
     * none. Everything else the library decided stays: a label, type, flag or position changed since,
     * a hint of its own, and a deleted definition, which stays deleted. Only the changed definitions
     * are returned.
     */
    fun upgradeSeeded(
        current: List<FieldDef>,
        seeds: List<FieldDef>,
    ): List<FieldDef> {
        val seedsByKey = seeds.associateBy { it.key }
        return current.mapNotNull { def ->
            val seed = seedsByKey[def.key] ?: return@mapNotNull null
            def
                .copy(perDocument = def.perDocument || seed.perDocument, hint = def.hint ?: seed.hint)
                .takeIf { it != def }
        }
    }

    /** English seed labels; the UI re-labels through the registry, so these are only a fallback. */
    val SEED_LABELS: Map<String, String> =
        mapOf(
            TITLE to "Title",
            CORRESPONDENT to "Correspondent",
            DOC_TYPE to "Document type",
            DOC_DATE to "Document date",
            NOTES to "Notes",
        )
}
