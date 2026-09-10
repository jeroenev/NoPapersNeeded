package dev.npn.core.model

import kotlinx.serialization.Serializable

/**
 * How a field's value is entered, validated, stored and asked for.
 *
 * Only two places are allowed to switch exhaustively on this enum: [FieldValues] (canonical form)
 * and the UI's `FieldEditor` (input control). Everything else branches on data looked up from the
 * [FieldDef] — that is what keeps adding a type a two-file change.
 */
@Serializable
enum class FieldType(
    val wire: String,
) {
    /** Single line, autocompleted from existing values. */
    TEXT("text"),

    /** Multi-line prose (what `notes` is). */
    LONG_TEXT("longText"),

    /** Integer. */
    NUMBER("number"),

    /** Fractional number; canonical form always uses `.`. */
    DECIMAL("decimal"),

    /** ISO-8601 local date, `yyyy-MM-dd`. */
    DATE("date"),
    ;

    /** True for types whose values belong in the full-text index. */
    val isTextual: Boolean get() = this == TEXT || this == LONG_TEXT

    /** True for types whose canonical value has a numeric shadow ([FieldValues.numeric]). */
    val isNumeric: Boolean get() = this == NUMBER || this == DECIMAL

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
) {
    init {
        require(key.isNotBlank()) { "FieldDef key must not be blank" }
    }

    /** Keys other subsystems name directly (XMP, storage-path templates, auto-match rules). */
    val isWellKnown: Boolean get() = key in FieldKeys.WELL_KNOWN
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

    /** Synced setting marking that the well-known definitions have been seeded once. */
    const val SEEDED_SETTING = "fieldsSeeded"

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

    /** The registry definitions a fresh library starts with. Positions follow list order. */
    fun seedDefinitions(labels: (String) -> String = { it }): List<FieldDef> =
        listOf(
            FieldDef(TITLE, labels(TITLE), FieldType.TEXT, showByDefault = true, aiEnabled = true, position = 0),
            FieldDef(
                CORRESPONDENT,
                labels(CORRESPONDENT),
                FieldType.TEXT,
                showByDefault = true,
                aiEnabled = true,
                position = 1,
            ),
            FieldDef(DOC_TYPE, labels(DOC_TYPE), FieldType.TEXT, showByDefault = true, aiEnabled = true, position = 2),
            FieldDef(DOC_DATE, labels(DOC_DATE), FieldType.DATE, showByDefault = true, aiEnabled = true, position = 3),
            // Not AI-enabled on purpose: automatic enrichment must not start generating prose into
            // the user's notes on every OCR completion, which is exactly today's behaviour.
            FieldDef(NOTES, labels(NOTES), FieldType.LONG_TEXT, showByDefault = true, aiEnabled = false, position = 4),
        )

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
